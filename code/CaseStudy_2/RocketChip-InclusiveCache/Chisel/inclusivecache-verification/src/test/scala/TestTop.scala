package verifyl2

import chisel3._
import org.chipsalliance.cde.config._
import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, CIRCTTargetAnnotation, CIRCTTarget, FirtoolOption}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import sifive.blocks.inclusivecache._
import freechips.rocketchip.subsystem.InclusiveCacheParams
import messageGenerator._
import chisel3.util.experimental.BoringUtils
import chisel3.util.log2Ceil
import chisel3.util._
import chiselFv._

/** Minimal parameters for InclusiveCache instantiation */
case object MiniL2Key extends Field[InclusiveCacheParams]

/** Which property group to enable */
case object PropertyKey extends Field[String]("all")

class MiniL2Config extends Config((site, here, up) => {
  case MiniL2Key => InclusiveCacheParams(
    ways = 2,           // reduce to create more conflicts and evictions
    sets = 2,           // keep small for faster state space exploration
    writeBytes = 2,     // minimum = blockBytes
    portFactor = 2,     // minimum = 2
    memCycles = 16      // increase to create more timing pressure and queuing
  )
})

/**
 * TestTop: 2 MessageGenerators (L1 stand-ins) -> XBar -> InclusiveCache (L2) -> TLRAM
 *
 * MessageGenerators maintain their own dir_valid/dir_tag/dir_state/dir_data
 * arrays, acting as L1 caches. We bore into these plus the L2 directory
 * shadow arrays to check cross-level coherence properties.
 */
class TestTop(implicit p: Parameters) extends LazyModule {
  val l2p = p(MiniL2Key)
  val enabledProperty = p(PropertyKey)

  val blockBytes = 2
  val numMsgGens = 2

  private val msgGenParams = Seq.tabulate(numMsgGens) { i =>
    MessageGeneratorParam(
      sets = 4,
      ways = 2,
      blockBytes = blockBytes,
      sourceIdRange = IdRange(i, i + 1),
      supportsProbe = Some(TransferSizes(blockBytes))
    )
  }

  val msgGenerators = msgGenParams.map(params => LazyModule(new TLMessageGenerator(params)))

  val l2 = LazyModule(new InclusiveCache(
    CacheParameters(
      level = 2,
      ways = l2p.ways,
      sets = l2p.sets,
      blockBytes = blockBytes,
      beatBytes = blockBytes,
      hintsSkipProbe = l2p.hintsSkipProbe
    ), 
    InclusiveCacheMicroParameters(
      writeBytes = l2p.writeBytes,
      portFactor = l2p.portFactor,
      memCycles = l2p.memCycles,
      innerBuf = l2p.bufInnerInterior,
      outerBuf = l2p.bufOuterInterior
    )))

  val xbar = TLXbar()
  msgGenerators.foreach { gen =>
    xbar := TLBuffer() := gen.node
  }

  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1fL), beatBytes = 2))
  ram.node := TLFragmenter(2, 2) := TLCacheCork() := l2.node := xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val addrWidth = msgGenerators.head.module.io.in_addr.getWidth

    val io = IO(new Bundle {
      val msg = Vec(msgGenerators.size, new Bundle {
        val addr      = Input(UInt(addrWidth.W))
        val isAcquire = Input(Bool())
        val param     = Input(Bool())
        val data      = Input(UInt((blockBytes * 8).W))
      })
    })

    msgGenerators.zipWithIndex.foreach { case (gen, idx) =>
      gen.module.io.in_addr      := io.msg(idx).addr
      gen.module.io.in_isAcquire := io.msg(idx).isAcquire
      gen.module.io.in_param     := io.msg(idx).param
      gen.module.io.in_data      := io.msg(idx).data
    }

    // ===================================================================
    // L1 (MessageGenerator) state arrays — bore into internal directory
    // ===================================================================
    // MsgGen state encoding: stateN=0 (None/Invalid), stateB=1 (Branch), stateT=2 (Trunk)
    // MsgGen directory: flat Vec(lines) indexed by addrIndex(addr) = addr(offBits+idxBits-1, offBits)

    val l1_dir_valid = msgGenerators.map { gen => BoringUtils.bore(gen.module.dir_valid) }
    val l1_dir_tag   = msgGenerators.map { gen => BoringUtils.bore(gen.module.dir_tag) }
    val l1_dir_state = msgGenerators.map { gen => BoringUtils.bore(gen.module.dir_state) }
    val l1_dir_data  = msgGenerators.map { gen => BoringUtils.bore(gen.module.dir_data) }

    // MsgGen address parsing
    val l1_offBits = log2Ceil(blockBytes) // 1
    val l1_lines   = msgGenParams.head.lines // 8
    val l1_idxBits = log2Ceil(l1_lines)  // 3
    val l1_tagBits = addrWidth - l1_idxBits - l1_offBits

    def l1_addrIndex(addr: UInt): UInt = (addr >> l1_offBits)(l1_idxBits - 1, 0)
    def l1_addrTag(addr: UInt): UInt   = (addr >> (l1_offBits + l1_idxBits))(l1_tagBits - 1, 0)

    // MsgGen state constants
    val mgStateN = 0.U(2.W)  // None/Invalid
    val mgStateB = 1.U(2.W)  // Branch
    val mgStateT = 2.U(2.W)  // Trunk

    // ===================================================================
    // L2 (InclusiveCache) directory shadow arrays
    // ===================================================================
    val l2_dirWriteValid = BoringUtils.bore(l2.module.mods.head.directory.io.write.valid)
    val l2_dirWriteReady = BoringUtils.bore(l2.module.mods.head.directory.io.write.ready)
    val l2_dirWriteSet   = BoringUtils.bore(l2.module.mods.head.directory.io.write.bits.set)
    val l2_dirWriteWay   = BoringUtils.bore(l2.module.mods.head.directory.io.write.bits.way)
    val l2_dirWriteTag   = BoringUtils.bore(l2.module.mods.head.directory.io.write.bits.data.tag)
    val l2_dirWriteState = BoringUtils.bore(l2.module.mods.head.directory.io.write.bits.data.state)
    val l2_dir_ready = BoringUtils.bore(l2.module.mods.head.directory.io.ready)

    // L2 address parsing
    val l2_offsetBits = log2Ceil(blockBytes) // 1
    val l2_setBits    = log2Ceil(l2p.sets)   // 1
    // L2 tagBits comes from InclusiveCacheParameters, but for our simple
    // contiguous address space it equals addrWidth - setBits - offsetBits
    val l2_tagBits    = addrWidth - l2_setBits - l2_offsetBits

    def l2_set(addr: UInt): UInt = addr(l2_offsetBits + l2_setBits - 1, l2_offsetBits)
    def l2_tag(addr: UInt): UInt = addr(l2_offsetBits + l2_setBits + l2_tagBits - 1, l2_offsetBits + l2_setBits)

    // L2 shadow directory model driven by io.write handshake.
    // This avoids false mismatches caused by array bore visibility lag.
    val l2_shadowState = RegInit(VecInit(Seq.fill(l2p.sets)(VecInit(Seq.fill(l2p.ways)(MetaData.INVALID)))))
    val l2_shadowTag = RegInit(VecInit(Seq.fill(l2p.sets)(VecInit(Seq.fill(l2p.ways)(0.U(l2_tagBits.W))))))

    when (l2_dirWriteValid && l2_dirWriteReady && l2_dir_ready) {
      l2_shadowState(l2_dirWriteSet)(l2_dirWriteWay) := l2_dirWriteState
      l2_shadowTag(l2_dirWriteSet)(l2_dirWriteWay) := l2_dirWriteTag
    }

    // ===================================================================
    // MSHR patch signals: skip checks during in-flight transactions
    // ===================================================================

    // MsgGen MSHR patches: check if any entry is active for the target address
    val l1_entryActive = msgGenerators.map { gen => BoringUtils.bore(gen.module.entryActive) }
    val l1_entryAddr   = msgGenerators.map { gen => BoringUtils.bore(gen.module.entryAddr) }
    val l1_releaseState = msgGenerators.map { gen => BoringUtils.bore(gen.module.releaseState) }
    val l1_releaseReqAddr = msgGenerators.map { gen => BoringUtils.bore(gen.module.releaseReqAddr) }
    val l1_probeFire = msgGenerators.map { gen => BoringUtils.bore(gen.module.probeFire) }
    val l1_probeAddr = msgGenerators.map { gen => BoringUtils.bore(gen.module.probeAddr) }
    val l1_probeHit = msgGenerators.map { gen => BoringUtils.bore(gen.module.probeHit) }

    def l1_sameLine(lhs: UInt, rhs: UInt): Bool = {
      (lhs >> l1_offBits) === (rhs >> l1_offBits)
    }

    val l1_probeFireHit = msgGenerators.indices.map { i =>
      l1_probeFire(i) && l1_probeHit(i)
    }
    val l1_probeAddrD1 = msgGenerators.indices.map { i =>
      RegEnable(l1_probeAddr(i), 0.U(addrWidth.W), l1_probeFireHit(i))
    }
    val l1_probeValidD1 = msgGenerators.indices.map { i =>
      RegNext(l1_probeFireHit(i), false.B)
    }
    val l1_probeAddrD2 = msgGenerators.indices.map { i =>
      RegEnable(l1_probeAddrD1(i), 0.U(addrWidth.W), l1_probeValidD1(i))
    }
    val l1_probeValidD2 = msgGenerators.indices.map { i =>
      RegNext(l1_probeValidD1(i), false.B)
    }
    val l1_probeAddrD3 = msgGenerators.indices.map { i =>
      RegEnable(l1_probeAddrD2(i), 0.U(addrWidth.W), l1_probeValidD2(i))
    }
    val l1_probeValidD3 = msgGenerators.indices.map { i =>
      RegNext(l1_probeValidD2(i), false.B)
    }

    def l1_MSHR_patch(genIdx: Int, addr: UInt): Bool = {
      val acquireActive = l1_entryActive(genIdx).zipWithIndex.map { case (active, i) =>
        active && l1_sameLine(l1_entryAddr(genIdx)(i), addr)
      }.reduce(_ || _)
      val releaseActive = l1_releaseState(genIdx) =/= 0.U &&
        l1_sameLine(l1_releaseReqAddr(genIdx), addr)
      val probeActive =
        (l1_probeFireHit(genIdx) && l1_sameLine(l1_probeAddr(genIdx), addr)) ||
        (l1_probeValidD1(genIdx) && l1_sameLine(l1_probeAddrD1(genIdx), addr)) ||
        (l1_probeValidD2(genIdx) && l1_sameLine(l1_probeAddrD2(genIdx), addr)) ||
        (l1_probeValidD3(genIdx) && l1_sameLine(l1_probeAddrD3(genIdx), addr))

      acquireActive || releaseActive || probeActive
    }

    // L2 MSHR patches
    // NOTE: When the MSHR's final schedule fires, request_valid drops to false
    // in the SAME clock cycle that the dir-RAM write is issued. Because the
    // directory is a synchronous SRAM, the write is only visible on the NEXT
    // cycle. We therefore extend the patch by one cycle (RegNext) to suppress
    // false coherence violations during this 1-cycle pipeline window.
    //
    // FPV analysis shows the stateArray bore has a 2-cycle pipeline:
    //   T+0: MSHR fires dir-write → write enqueued in Queue(io.write, 1)
    //   T+1: Queue dequeues → stateArray register latched  (blocked if concurrent read)
    //   T+2: stateArray visible; but if there was a read conflict at T+1,
    //        the register latch shifts one cycle further to T+3.
    // Keep the patch active while an MSHR owns the set and for three cycles
    // after the directory write handshake to cover the latest T+3 update.
    def l2_MSHR_patch(addr: UInt): Bool = {
      val set = l2_set(addr)
      val activeSet = l2.module.mods.head.mshrs.map { mshr =>
        BoringUtils.bore(mshr.io.status.valid) &&
          BoringUtils.bore(mshr.io.status.bits.set) === set
      }.reduce(_ || _)
      val dirWriteFire = l2_dirWriteValid && l2_dirWriteReady && l2_dirWriteSet === set
      val writeD1 = RegNext(dirWriteFire, false.B)
      val writeD2 = RegNext(writeD1, false.B)
      val writeD3 = RegNext(writeD2, false.B)
      activeSet || dirWriteFire || writeD1 || writeD2 || writeD3
    }

    // L2 directory ready (reset wipe complete)

    // ===================================================================
    // Helper: check L1 (MsgGen) hit at address
    // ===================================================================
    def l1_hit(genIdx: Int, addr: UInt): Bool = {
      val idx = l1_addrIndex(addr)
      val tag = l1_addrTag(addr)
      l1_dir_valid(genIdx)(idx) && l1_dir_tag(genIdx)(idx) === tag
    }

    def l1_state(genIdx: Int, addr: UInt): UInt = {
      l1_dir_state(genIdx)(l1_addrIndex(addr))
    }

    def l1_data(genIdx: Int, addr: UInt): UInt = {
      l1_dir_data(genIdx)(l1_addrIndex(addr))
    }

    // ===================================================================
    // Helper: check L2 hit at address
    // ===================================================================
    def l2_findHit(addr: UInt): (Bool, UInt) = {
      val set = l2_set(addr)
      val tag = l2_tag(addr)
      val hitVec = l2_shadowTag(set).zip(l2_shadowState(set)).map { case (t, s) =>
        t === tag && s =/= MetaData.INVALID
      }
      val hit = hitVec.reduce(_ || _)
      val way = OHToUInt(hitVec)
      (hit, way)
    }

    def l2_hitState(addr: UInt): (Bool, UInt) = {
      val (hit, way) = l2_findHit(addr)
      val st = l2_shadowState(l2_set(addr))(way)
      (hit, st)
    }

    // ========== Property: liveness ==========
    def liveness_spec(): Unit = {
      l2.module.mods.foreach { case sched =>
        sched.mshrs.foreach { case mshr =>
          val request_valid = BoringUtils.bore(mshr.request_valid)
          val allocate_valid = BoringUtils.bore(mshr.io.allocate.valid)
          val meta_valid = BoringUtils.bore(mshr.meta_valid)
          val schedule_valid = BoringUtils.bore(mshr.io.schedule.valid)
          val schedule_ready = BoringUtils.bore(mshr.io.schedule.ready)
          val w_grantlast = BoringUtils.bore(mshr.w_grantlast)
          val w_releaseack = BoringUtils.bore(mshr.w_releaseack)

          // MiniL2Config keeps memCycles high enough that probe/release serialization
          // can legitimately exceed the original 70/100-cycle bounds.
          astRelaxedLiveness(request_valid, !request_valid || allocate_valid, 96)
          astRelaxedLiveness(request_valid && meta_valid, !request_valid || schedule_valid, 192)
          astRelaxedLiveness(schedule_valid, !schedule_valid || schedule_ready, 128)
          astRelaxedLiveness(request_valid, !request_valid, 640)
          astRelaxedLiveness(request_valid && !w_grantlast, w_grantlast || !request_valid, 320)
          astRelaxedLiveness(request_valid && !w_releaseack, w_releaseack || !request_valid, 320)
        }
      }
    }

    // ===================================================================
    // NEW: L1-L1 mutual exclusivity
    // Two MessageGenerators cannot both hold the same address in
    // conflicting states. Forbidden pairs (same address):
    //   (T, T), (T, B), (B, T)
    // Only (B, B) is allowed when both are non-Invalid.
    // ===================================================================
    def l1_l1_mutual(addr: UInt, st0: UInt, st1: UInt): Unit = {
      val hit0 = l1_hit(0, addr) && l1_state(0, addr) === st0
      val hit1 = l1_hit(1, addr) && l1_state(1, addr) === st1

      fvAssert(
        l1_MSHR_patch(0, addr) ||
        l1_MSHR_patch(1, addr) ||
        l2_MSHR_patch(addr) ||
        !l2_dir_ready ||
        !(hit0 && hit1),
        s"L1-L1 mutual: (${st0.litValue}, ${st1.litValue})")
    }

    def l1_l1_mutual_specs(): Unit = {
      val a = 0.U(addrWidth.W)
      // T + T: both exclusive — impossible
      l1_l1_mutual(a, mgStateT, mgStateT)
      // T + B / B + T: exclusive conflicts with shared — impossible
      l1_l1_mutual(a, mgStateT, mgStateB)
      l1_l1_mutual(a, mgStateB, mgStateT)
    }

    // ===================================================================
    // NEW: L1-L2 mutual exclusivity
    // MessageGenerator (L1) and InclusiveCache (L2) must have consistent
    // coherence states. Impossible (L1_state, L2_state) combinations:
    //   (T, INVALID)  — L1 exclusive but L2 has nothing
    //   (T, BRANCH)   — L1 exclusive but L2 only has read
    //   (T, TIP)      — L2=TIP means all clients are branch
    //   (B, INVALID)  — L1 has branch but L2 has nothing (inclusive)
    //   (B, TRUNK)    — L2=TRUNK means one exclusive client, not branch
    // ===================================================================
    def l1_l2_mutual(addr: UInt, l1_st: UInt, l2_st: UInt, genIdx: Int): Unit = {
      val l1_present = l1_hit(genIdx, addr) && l1_state(genIdx, addr) === l1_st
      val (l2_hit, l2_way) = l2_findHit(addr)

      val l2_match = if (l2_st.litValue == MetaData.INVALID.litValue) {
        !l2_hit  // L2 INVALID means the line is NOT in L2
      } else {
        l2_hit && l2_shadowState(l2_set(addr))(l2_way) === l2_st
      }

      fvAssert(
        l1_MSHR_patch(genIdx, addr) ||
        l2_MSHR_patch(addr) ||
        !l2_dir_ready ||
        !(l1_present && l2_match),
        s"L1[$genIdx]-L2 mutual: (l1=${l1_st.litValue}, l2=${l2_st.litValue})")
    }

    def l1_l2_mutual_specs(): Unit = {
      val a = 0.U(addrWidth.W)
      for (i <- 0 until numMsgGens) {
        l1_l2_mutual(a, mgStateT, MetaData.INVALID, i)
        l1_l2_mutual(a, mgStateT, MetaData.BRANCH, i)
        l1_l2_mutual(a, mgStateT, MetaData.TIP, i)
        l1_l2_mutual(a, mgStateB, MetaData.INVALID, i)
        l1_l2_mutual(a, mgStateB, MetaData.TRUNK, i)
      }
    }

    // ===================================================================
    // NEW: L1-L2 inclusive property
    // If a MessageGenerator (L1) holds a valid line, the InclusiveCache
    // (L2) must also hold it (state != INVALID).
    // ===================================================================
    def l1_l2_inclusive(addr: UInt, genIdx: Int): Unit = {
      val l1_present = l1_hit(genIdx, addr)
      val (l2_present, _) = l2_findHit(addr)

      fvAssert(
        l1_MSHR_patch(genIdx, addr) ||
        l2_MSHR_patch(addr) ||
        !l2_dir_ready ||
        !l1_present || l2_present,
        s"L1[$genIdx]-L2 inclusive: L1 hit implies L2 hit")
    }

    def l1_l2_inclusive_specs(): Unit = {
      val a = 0.U(addrWidth.W)
      for (i <- 0 until numMsgGens) {
        l1_l2_inclusive(a, i)
      }
    }

    // ===================================================================
    // NEW: L1-L1 consistency
    // When both MessageGenerators hold the same address in Branch (B)
    // state, their cached data must be identical.
    // ===================================================================
    def l1_l1_consistency(addr: UInt): Unit = {
      val both_branch =
        l1_hit(0, addr) && l1_state(0, addr) === mgStateB &&
        l1_hit(1, addr) && l1_state(1, addr) === mgStateB

      fvAssert(
        l1_MSHR_patch(0, addr) ||
        l1_MSHR_patch(1, addr) ||
        l2_MSHR_patch(addr) ||
        !l2_dir_ready ||
        !both_branch ||
        l1_data(0, addr) === l1_data(1, addr),
        "L1-L1 consistency: BRANCH data must match")
    }

    def l1_l1_consistency_specs(): Unit = {
      l1_l1_consistency(0.U(addrWidth.W))
    }

    // ========== Enable properties based on PropertyKey ==========
    val prop = enabledProperty
    if (prop == "all" || prop == "liveness")           liveness_spec()
    if (prop == "all" || prop == "l1_l1_mutual")       l1_l1_mutual_specs()
    if (prop == "all" || prop == "l1_l2_mutual")       l1_l2_mutual_specs()
    if (prop == "all" || prop == "l1_l2_inclusive")    l1_l2_inclusive_specs()
    if (prop == "all" || prop == "l1_l1_consistency")  l1_l1_consistency_specs()
  }
}

object TestTop extends App {
  val cfg = new MiniL2Config
  val top = DisableMonitors(p => LazyModule(new TestTop()(p)))(cfg)
  (new ChiselStage).execute(args, Seq(
    ChiselGeneratorAnnotation(() => top.module),
    CIRCTTargetAnnotation(CIRCTTarget.SystemVerilog),
    FirtoolOption("--disable-annotation-unknown")
  ))
}
