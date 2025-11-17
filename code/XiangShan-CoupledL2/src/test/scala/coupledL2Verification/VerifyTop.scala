package coupledL2Verification

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2._
import coupledL2.tl2tl.{Slice => L2Slice, _}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import org.chipsalliance.cde.config._
import utility._
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}

import java.io.File


object baseConfig {
  def apply(maxHartIdBits: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
    })
  }
}

class VerifyTop()(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL2 = 2
  val msgGenBlockBytes = 2  // MessageGenerator的blockBytes参数

  // Replace previous TLCoupledL2AsL1 (complex prefetch based) with simplified TLMessageGenerator
  val msgGens = (0 until nrL2).map { i =>
    val genParams = MessageGeneratorParam(
      name = s"L1_$i",
      sets = 2,
      ways = 2,
      blockBytes = msgGenBlockBytes,
      channelBytes = TLChannelBeatBytes(1),
      sourceIdRange = IdRange(0, 16),
      reqField = Seq(AliasField(2)),
      respKey = cacheParams.respKey
    )
    LazyModule(new TLMessageGenerator(genParams))
  }
  val l1d_nodes = msgGens.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alter((_, here, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      sets = 4,
      ways = 2,
      blockBytes = 2,
      mshrs = 4,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      hartId = i,
    )
    case huancun.BankBitsKey => 0
    case LogUtilsOptionsKey => LogUtilsOptions(
      false,
      here(L2ParamKey).enablePerf,
      here(L2ParamKey).FPGAPlatform
    )
    case PerfCounterOptionsKey => PerfCounterOptions(
      here(L2ParamKey).enablePerf && !here(L2ParamKey).FPGAPlatform,
      here(L2ParamKey).enableRollingDB && !here(L2ParamKey).FPGAPlatform,
      XSPerfLevel.withName("VERBOSE"),
      i
    )
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alter((_, here, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      inclusive = false,
      sets = 4,
      ways = 2,
      channelBytes = TLChannelBeatBytes(1),
      blockBytes = 2,
      mshrs = 6,
      clientCaches = (0 until nrL2).map(_ =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(4)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true
    )
    case LogUtilsOptionsKey => LogUtilsOptions(
      here(HCCacheParamsKey).enableDebug,
      here(HCCacheParamsKey).enablePerf,
      here(HCCacheParamsKey).FPGAPlatform
    )
    case PerfCounterOptionsKey => PerfCounterOptions(
      here(HCCacheParamsKey).enablePerf && !here(HCCacheParamsKey).FPGAPlatform,
      false,
      XSPerfLevel.withName("VERBOSE"),
      0
    )
  })))

  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1fL), beatBytes = 1))

  l1d_nodes.zip(l2_nodes).zipWithIndex foreach { case ((l1d, l2), i) =>
    l2 := TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case(l2, i) => xbar := 
      TLLogger(s"L3_L2[${i}]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
      TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    coupledL2.foreach {
      l2 => {
        l2.module.io.debugTopDown <> DontCare
        l2.module.io.hartId := DontCare
        l2.module.io.pfCtrlFromCore := DontCare
        l2.module.io.l2_tlb_req <> DontCare
      }
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U

    val io = IO(Vec(nrL2, new Bundle() {
      // External control to drive simplified generator
      val reqAddr = Input(UInt(ram.node.in.head._2.bundle.addressBits.W))
      val reqIsAcquire = Input(Bool())
      val reqParam = Input(Bool())
      val reqData = Input(UInt((msgGenBlockBytes * 8).W))  // blockBytes * 8 bits
    }))

    msgGens.zipWithIndex.foreach { case (gen, i) =>
      gen.module.io_in_addr := io(i).reqAddr
      gen.module.io_in_isAcquire := io(i).reqIsAcquire
      gen.module.io_in_param := io(i).reqParam
      gen.module.io_in_data := io(i).reqData
    }

    coupledL2(0).module.slices.head match {
      case tlSlice: L2Slice =>
        val dir_resetFinish = BoringUtils.bore(tlSlice.directory.resetFinish)
        assume(verify_timer < 200.U || dir_resetFinish)
    }

    // Deadlock Freeness Assertions
    val timer = 10000
    coupledL2.foreach { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          val w_rprobeacklast = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_rprobeacklast)
          astRelaxedLiveness(!w_rprobeacklast, w_rprobeacklast, timer)
          val w_pprobeacklast = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_pprobeacklast)
          astRelaxedLiveness(!w_pprobeacklast, w_pprobeacklast, timer)
          val w_grantlast = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_grantlast)
          astRelaxedLiveness(!w_grantlast, w_grantlast, timer)
          val w_releaseack = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_releaseack)
          astRelaxedLiveness(!w_releaseack, w_releaseack, timer)

          // Acquire Liveness
          val w_acquire = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.acquire_period)
          fvAssert(w_acquire < timer)
          // Probe Liveness
          val w_probe = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.probe_period)
          fvAssert(w_probe < timer)
          // Release Liveness
          val w_release = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.release_period)
          fvAssert(w_release < timer)
      }
    }

    // Address parsing functions
    val l1_offsetBits = 1
    val l1_bankBits = 0
    val l1_setBits = 1
    val l1_tagBits = 3

    def parseL1Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l1_offsetBits + l1_bankBits)
      val tag = set >> l1_setBits
      (tag(l1_tagBits - 1, 0), set(l1_setBits - 1, 0), offset(l1_offsetBits - 1, 0))
    }

    val l2_offsetBits = 1
    val l2_bankBits = 0
    val l2_setBits = 2
    val l2_tagBits = 2

    def parseL2Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l2_offsetBits + l2_bankBits)
      val tag = set >> l2_setBits
      (tag(l2_tagBits - 1, 0), set(l2_setBits - 1, 0), offset(l2_offsetBits - 1, 0))
    }

    // Get arrays from L1 (CoupledL2AsL1) and L2 (CoupledL2)
    val l1_stateArrays = coupledL2AsL1.map { l1 =>
      val slice = l1.module.slices.head.asInstanceOf[L1Slice]
      BoringUtils.bore(slice.directoryTest.io.stateArray)
    }

    val l1_tagArrays = coupledL2AsL1.map { l1 =>
      val slice = l1.module.slices.head.asInstanceOf[L1Slice]
      BoringUtils.bore(slice.directoryTest.io.tagArray)
    }

    val l2_stateArrays = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          BoringUtils.bore(tlSlice.directoryTest.io.stateArray)
      }
    }

    val l2_tagArrays = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          BoringUtils.bore(tlSlice.directoryTest.io.tagArray)
      }
    }

    val l2_dataArrays = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          BoringUtils.bore(tlSlice.dataStorageTest.io.dataArray)
      }
    }

    // Get MSHR signals for property patches
    val mshrs = 4
    class MSHR_signal_bundle extends Bundle {
      val status_bits_set = UInt(l2_setBits.W)
      val status_bits_metaTag = UInt(l2_tagBits.W)
      val status_bits_reqTag = UInt(l2_tagBits.W)
      val status_bits_needRepl = Bool()
      val status_bits_w_c_resp = Bool()
      val status_bits_w_d_resp = Bool()
      val status_valid = Bool()
    }

    val MSHR_signals = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          (0 until mshrs).map { i =>
            val mshr_sig = Wire(new MSHR_signal_bundle)
            mshr_sig.status_bits_set := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.set)
            mshr_sig.status_bits_metaTag := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.metaTag)
            mshr_sig.status_bits_reqTag := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.reqTag)
            mshr_sig.status_bits_needRepl := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.needsRepl)
            mshr_sig.status_bits_w_c_resp := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.w_c_resp)
            mshr_sig.status_bits_w_d_resp := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.w_d_resp)
            mshr_sig.status_valid := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.valid)
            mshr_sig
          }
      }
    }

    val l2_MSHR_prop_patch_inclusive = Cat(MSHR_signals(0).map { m =>
      m.status_bits_set === 0.U &&
      m.status_valid === true.B
    }).orR

    val l2_MSHR_prop_patch_consistency = coupledL2.indices.map { indx =>
      Cat(MSHR_signals(indx).map { m =>
        m.status_bits_set === 0.U &&
        m.status_valid === true.B
      }).orR
    }

    // TileLink State Coherence property (L2-L2)
    def l2_mutual(addr: UInt, state1: UInt, state2: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      fvAssert(!(hit0 && l2_stateArrays(0)(set)(way0) === state1 &&
                 hit1 && l2_stateArrays(1)(set)(way1) === state2))
    }

    // TileLink State Coherence property (L1-L2)
    def l1_l2_mutual(addr: UInt, l1_state: UInt, l2_state: UInt): Unit = {
      val (l1_tag, l1_set, l1_offset) = parseL1Address(addr)
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      
      val l1_hit_vec = l1_tagArrays(0)(l1_set).zip(l1_stateArrays(0)(l1_set)).map {
        case (tag, state) =>
          tag === l1_tag && state =/= INVALID
      }
      val l1_hit = l1_hit_vec.reduce(_ || _)
      val l1_way = OHToUInt(l1_hit_vec)

      val l2_hit_vec = l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      fvAssert(!(l1_hit && l1_stateArrays(0)(l1_set)(l1_way) === l1_state &&
                 l2_hit && l2_stateArrays(0)(l2_set)(l2_way) === l2_state))
    }

    def l2_mutual_specs(): Unit = {
      l2_mutual(0.U(32.W), TIP, TIP)
      l2_mutual(0.U(32.W), TIP, BRANCH)
    }

    def l1l2_mutual_specs(): Unit = { 
      // L1-L2 mutual exclusivity (all impossible combinations from table)
      // L2=TT, L1=TT
      l1_l2_mutual(0.U(32.W), TIP, TIP)
      // L2=TT, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, TIP)
      // L2=T, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, TRUNK)
      // L2=T, L1=B
      l1_l2_mutual(0.U(32.W), BRANCH, TRUNK)
      // L2=T, L1=N
      l1_l2_mutual(0.U(32.W), INVALID, TRUNK)
      // L2=B, L1=TT
      l1_l2_mutual(0.U(32.W), TIP, BRANCH)
      // L2=B, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, BRANCH)
      // L2=N, L1=TT
      l1_l2_mutual(0.U(32.W), TIP, INVALID)
      // L2=N, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, INVALID)
      // L2=N, L1=B
      l1_l2_mutual(0.U(32.W), BRANCH, INVALID)
      // L2=N, L1=N
      l1_l2_mutual(0.U(32.W), INVALID, INVALID)
    }

    // Inclusive property
    def l1l2_inclusive(addr: UInt): Unit = {
      val (l1_tag, l1_set, l1_offset) = parseL1Address(addr)
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      
      val l1_hit_vec = l1_tagArrays(0)(l1_set).zip(l1_stateArrays(0)(l1_set)).map {
        case (tag, state) =>
          tag === l1_tag && state =/= INVALID
      }
      val l1_hit = l1_hit_vec.reduce(_ || _)
      val l1_way = OHToUInt(l1_hit_vec)

      val l2_hit_vec = l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      val valid_line = l1_hit && l1_stateArrays(0)(l1_set)(l1_way) =/= INVALID

      // assume(verify_timer < 800.U || (l1_hit && l1_stateArrays(0)(l1_set)(l1_way) =/= INVALID))
      val t1: Sequence = verify_timer > 800.U
      val t2: Sequence = valid_line
      AssumeProperty(t1 |-> t2.eventually)

      fvAssert(!valid_line || (l2_hit && l2_stateArrays(0)(l2_set)(l2_way) =/= INVALID) || l2_MSHR_prop_patch_inclusive)
    }

    def inclusive_spec(): Unit = {
      l1l2_inclusive(0.U(32.W))
    }

    // Data Consistency property
    def l2_consistency(addr: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      val arrayIdx0 = Cat(way0, set)
      val arrayIdx1 = Cat(way1, set)

      val valid_state = hit0 && l2_stateArrays(0)(set)(way0) === BRANCH && 
                        hit1 && l2_stateArrays(1)(set)(way1) === BRANCH
      // assume(verify_timer < 2500.U || valid_state)
      val t1: Sequence = verify_timer > 2500.U
      val t2: Sequence = valid_state
      AssumeProperty(t1 |-> t2.eventually)

      fvAssert(!valid_state ||
               l2_dataArrays(0)(arrayIdx0) === l2_dataArrays(1)(arrayIdx1) || 
               l2_MSHR_prop_patch_consistency(0) || 
               l2_MSHR_prop_patch_consistency(1))
    }

    def consistency_spec(): Unit = {
      l2_consistency(0.U(32.W))
    }

    // Enable verification properties (comment out as needed)
    l1l2_mutual_specs()
    l2_mutual_specs()
    inclusive_spec()
    consistency_spec()
  }
}

object VerifyTop extends App {
  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop()(p)))(config)
  val directory = new File("./Verilog")

  if (!directory.exists()) {
    directory.mkdirs()
  }
  FileRegisters.writeOutputFile(
    "Verilog",
    "VerifyTop.sv",
    ChiselStage.emitSystemVerilog(top.module,
                                  args = Array("--warn-conf", "id=4:s"),
                                  firtoolOpts = Array("--disable-annotation-unknown"))
  )
}