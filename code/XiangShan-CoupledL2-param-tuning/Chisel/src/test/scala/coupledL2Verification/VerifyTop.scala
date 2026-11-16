package coupledL2Verification

import chisel3._
import circt.stage.ChiselStage
import chisel3.ltl._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2._
import coupledL2.MetaData._
import coupledL2.tl2tl.{Slice => L2Slice, _}
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import org.chipsalliance.cde.config._
import utility._

import java.io.File


object baseConfig {
  def apply(maxHartIdBits: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
    })
  }
}

case class TuningParams(
  l1Ways: Int = 2, l1Sets: Int = 2, l1Mshrs: Int = 4,
  l2Ways: Int = 2, l2Sets: Int = 4, l2Mshrs: Int = 4,
  l3Ways: Int = 2, l3Sets: Int = 4, l3Mshrs: Int = 6,
  ramWidth: Int = 5
) {
  private def power2(x: Int): Boolean = x > 0 && (x & (x - 1)) == 0
  require(ramWidth >= 5 && ramWidth <= 32, "ram.width must be in 5..32")
  require(l1Mshrs <= 16, "l1.mshrs must be <= 16 (Message Generator source IDs)")
  for ((layer, ways, sets, mshrs, minimum) <- Seq(
    ("l1", l1Ways, l1Sets, l1Mshrs, 4),
    ("l2", l2Ways, l2Sets, l2Mshrs, 4),
    ("l3", l3Ways, l3Sets, l3Mshrs, 6)
  )) {
    require(ways >= 2, s"$layer.ways must be >= 2")
    require(sets >= 2 && power2(sets), s"$layer.sets must be a power of two >= 2")
    require(mshrs >= minimum, s"$layer.mshrs must be >= $minimum")
    require(ramWidth - log2Ceil(sets) - 1 > 0, s"$layer.tagBits must be positive")
    require((BigInt(1) << ramWidth) >= 4 * sets, s"ram.width cannot cover $layer sets and two tags")
  }
}

class VerifyTop(tuning: TuningParams = TuningParams(l3Sets = 8))
               (implicit p: Parameters) extends LazyModule {
  require(tuning.l3Sets > tuning.l2Sets,
    "l3.sets must be > l2.sets (larger L3 set-index space)")

  // Two Message Generators, each connected to a private L2, share one L3 and RAM.
  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)
  val nrL2 = 2

  // Keep l1.* as the search interface for generator geometry and transaction slots.
  val messageGenerators = (0 until nrL2).map(i => LazyModule(new TLMessageGenerator(
    MessageGeneratorParam(
      name = s"msgGen$i",
      sets = tuning.l1Sets,
      ways = tuning.l1Ways,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      sourceIdRange = IdRange(0, tuning.l1Mshrs),
      ordinaryEntries = tuning.l1Mshrs,
      retainedGrantCopy = true,
      reqField = Seq(AliasField(2)),
      respKey = cacheParams.respKey,
      supportsProbe = Some(TransferSizes(2)),
      minLatency = 1
    )
  )))
  val req_nodes = messageGenerators.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alter((_, here, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = tuning.l2Ways,
      sets = tuning.l2Sets,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = tuning.l2Mshrs,
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
      ways = tuning.l3Ways,
      sets = tuning.l3Sets,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = tuning.l3Mshrs,
      inclusive = false,
      clientCaches = (0 until nrL2).map(_ =>
        CacheParameters(
          name = s"l2",
          sets = tuning.l2Sets,
          ways = tuning.l2Ways + 2,
          blockGranularity = log2Ceil(tuning.l2Sets),
          blockBytes = 2
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
  val ram = LazyModule(new TLRAM(AddressSet(0, (BigInt(1) << tuning.ramWidth) - 1), beatBytes = 1))

  req_nodes.zip(l2_nodes).zipWithIndex.foreach {
    case ((req, l2), i) => l2 :=
      TLLogger(s"L2_MsgGen[$i]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=
      TLBuffer() := req
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
    coupledL2.foreach { l2 =>
      l2.module.io.debugTopDown <> DontCare
      l2.module.io.hartId := DontCare
      l2.module.io.pfCtrlFromCore := DontCare
      l2.module.io.l2_tlb_req <> DontCare
    }

    val io = IO(Vec(nrL2, new Bundle {
      val inputValid = Input(Bool())
      val inputAddr = Input(UInt(tuning.ramWidth.W))
      val inputNeedT = Input(Bool())
      val inputRequestType = Input(Bool()) // false: Acquire, true: Release
      val inputStore = Input(Bool())
      val inputData = Input(UInt(16.W))
    }))
    messageGenerators.zipWithIndex.foreach { case (generator, i) =>
      generator.module.io.in_valid := io(i).inputValid
      generator.module.io.in_addr := io(i).inputAddr
      generator.module.io.in_isAcquire := !io(i).inputRequestType
      generator.module.io.in_param := io(i).inputNeedT
      generator.module.io.in_store := io(i).inputStore
      generator.module.io.in_data := io(i).inputData
      dontTouch(generator.module.io)
    }

    def checkSlice(slice: L2Slice, layer: String, index: Int,
                   ways: Int, sets: Int, mshrs: Int): Unit = {
      val tagWrite = BoringUtils.bore(slice.directory.io.tagWReq)
      // A valid tag write must select a configured way.
      fvAssert(!tagWrite.valid || tagWrite.bits.way < ways.U,
        s"param_way_index__${layer}_$index")

      val tags = BoringUtils.bore(slice.directoryTest.io.tagArray)
      val states = BoringUtils.bore(slice.directoryTest.io.stateArray)
      val selectedSet = BoringUtils.bore(slice.sinkA.io.task.bits.set)
      val oldTags = RegNext(tags(tagWrite.bits.set))
      val wrote = RegNext(tagWrite.valid, false.B)
      val writtenSet = RegNext(tagWrite.bits.set)
      val writtenWay = RegNext(tagWrite.bits.way)
      // A tag write must preserve the other ways in its set.
      for (way <- 0 until ways) {
        fvAssert(!wrote || writtenWay === way.U ||
          tags(writtenSet)(way) === oldTags(way),
          s"param_way_isolation__${layer}_${index}_$way")
      }
      // Two live ways in the observed set must have different tags.
      for (left <- 0 until ways; right <- left + 1 until ways) {
        fvAssert(states(selectedSet)(left) === INVALID ||
          states(selectedSet)(right) === INVALID ||
          tags(selectedSet)(left) =/= tags(selectedSet)(right),
          s"param_unique_live_tag__${layer}_${index}_${left}_$right")
      }

      val inA = slice.sinkA.io.a
      val inValid = BoringUtils.bore(inA.valid)
      val inReady = BoringUtils.bore(inA.ready)
      val address = BoringUtils.bore(inA.bits.address)
      val taskValid = BoringUtils.bore(slice.sinkA.io.task.valid)
      val taskReady = BoringUtils.bore(slice.sinkA.io.task.ready)
      // Cover an internal request handshake for every configured set.
      for (set <- 0 until sets) {
        chisel3.ltl.CoverProperty(taskValid && taskReady && selectedSet === set.U,
          s"param_cover_set__${layer}_${index}_$set")
      }
      // Cover ways 0 and 1 being live in the observed set.
      chisel3.ltl.CoverProperty(
        states(selectedSet)(0) =/= INVALID && states(selectedSet)(1) =/= INVALID,
        s"param_cover_two_ways__${layer}_$index")
      val task = slice.sinkA.io.task
      val taskSet = BoringUtils.bore(task.bits.set)
      val taskTag = BoringUtils.bore(task.bits.tag)
      val taskOff = BoringUtils.bore(task.bits.off)
      val decodedA = !inValid || !inReady ||
        (taskSet === address(log2Ceil(sets), 1) &&
         taskTag === (address >> (log2Ceil(sets) + 1)).asUInt &&
         taskOff === address(0))
      // On an A handshake, task set/tag/offset must match the address split.
      fvAssert(decodedA, s"param_address_decode__${layer}_$index")

      val outA = slice.io.out.a
      val outValid = BoringUtils.bore(outA.valid)
      val outBits = BoringUtils.bore(outA.bits)
      val blockRequest = outValid &&
        (outBits.opcode === TLMessages.AcquireBlock ||
         outBits.opcode === TLMessages.AcquirePerm)
      // A valid AcquireBlock/AcquirePerm must be aligned to a two-byte block.
      fvAssert(!blockRequest || outBits.address(0) === 0.U,
        s"param_block_boundary__${layer}_$index")
      // Block Acquires use size=1, a two-byte transfer.
      fvAssert(!blockRequest || outBits.size === 1.U,
        s"param_block_size__${layer}_$index")
      // Block Acquires use mask=1 on the one-byte beat.
      fvAssert(!blockRequest || outBits.mask === 1.U,
        s"param_beat_shape__${layer}_$index")

      val slots = (0 until mshrs).map { i =>
        (BoringUtils.bore(slice.mshrCtl.mshrs(i).io.status.valid),
         BoringUtils.bore(slice.mshrCtl.mshrs(i).io.status.bits.set),
         BoringUtils.bore(slice.mshrCtl.mshrs(i).io.status.bits.reqTag))
      }
      val occupied = PopCount(slots.map(_._1))
      // The observed ordinary MSHR occupancy must fit the configured count.
      fvAssert(occupied <= mshrs.U, s"param_mshr_capacity__${layer}_$index")
      val alloc = BoringUtils.bore(slice.mshrCtl.io.fromMainPipe.mshr_alloc_s3.valid)
      val allocId = BoringUtils.bore(slice.mshrCtl.io.toMainPipe.mshr_alloc_ptr)
      // Check allocation range and a free target slot, without eventual completion.
      fvAssert(!alloc || (allocId < mshrs.U && !VecInit(slots.map(_._1))(allocId)),
        s"param_mshr_progress__${layer}_$index")
      if (layer == "l2") {
        val allocation = BoringUtils.bore(slice.mshrCtl.io.fromMainPipe.mshr_alloc_s3)
        // Cover this L2 allocating an MSHR for an A-channel directory miss.
        chisel3.ltl.CoverProperty(allocation.valid && allocation.bits.task.fromA &&
          !allocation.bits.dirResult.hit, s"param_cover_miss__l2_$index")
      }
      // Two valid MSHRs must not track the same set/tag.
      for (left <- 0 until mshrs; right <- left + 1 until mshrs) {
        val (validA, setA, tagA) = slots(left)
        val (validB, setB, tagB) = slots(right)
        fvAssert(!(validA && validB && setA === setB && tagA === tagB),
          s"param_mshr_unique__${layer}_${index}_${left}_$right")
      }
      // Cover at least two ordinary MSHRs being valid together.
      chisel3.ltl.CoverProperty(occupied >= 2.U,
        s"param_cover_two_mshrs__${layer}_$index")

      val aValid = BoringUtils.bore(slice.io.out.a.valid)
      val aReady = BoringUtils.bore(slice.io.out.a.ready)
      val aBits = BoringUtils.bore(slice.io.out.a.bits).asUInt
      val stalled = RegNext(aValid && !aReady, false.B)
      val previous = RegNext(aBits)
      // Keep A valid and its payload stable after a stalled cycle.
      fvAssert(!stalled || (aValid && aBits === previous),
        s"param_beat_stable__${layer}_$index")
      val cValid = BoringUtils.bore(slice.io.out.c.valid)
      val cReady = BoringUtils.bore(slice.io.out.c.ready)
      val cBits = BoringUtils.bore(slice.io.out.c.bits)
      val cStalled = RegNext(cValid && !cReady, false.B)
      val cPrevious = RegNext(cBits.asUInt)
      // Keep C valid and its payload stable after a stalled cycle.
      fvAssert(!cStalled || (cValid && cBits.asUInt === cPrevious),
        s"param_beat_stable__${layer}_${index}_c")
      // Valid C messages must transfer at most two bytes.
      fvAssert(!cValid || cBits.size <= 1.U,
        s"param_beat_shape__${layer}_${index}_c")
      val dValid = BoringUtils.bore(slice.io.in.d.valid)
      val dReady = BoringUtils.bore(slice.io.in.d.ready)
      val dBits = BoringUtils.bore(slice.io.in.d.bits)
      val dStalled = RegNext(dValid && !dReady, false.B)
      val dPrevious = RegNext(dBits.asUInt)
      // Keep D valid and its payload stable after a stalled cycle.
      fvAssert(!dStalled || (dValid && dBits.asUInt === dPrevious),
        s"param_beat_stable__${layer}_${index}_d")
      // Valid D messages must transfer at most two bytes.
      fvAssert(!dValid || dBits.size <= 1.U,
        s"param_beat_shape__${layer}_${index}_d")
    }

    // The l1 property suffix denotes Message Generator state, matching the search keys.
    def checkGenerator(generator: TLMessageGenerator, index: Int): Unit = {
      val m = generator.module
      val ways = tuning.l1Ways
      val sets = tuning.l1Sets
      val entries = tuning.l1Mshrs
      val lines = ways * sets
      val addresses = BoringUtils.bore(m.address)
      val valid = BoringUtils.bore(m.valid)
      val states = BoringUtils.bore(m.state)
      val releases = BoringUtils.bore(m.releaseState)
      val requestAddresses = BoringUtils.bore(m.reqAddr)
      val commit = BoringUtils.bore(m.commit)
      val commitLine = BoringUtils.bore(m.commitLine)
      val commitIndex = BoringUtils.bore(m.commitIndex)
      val bufferPermissions = BoringUtils.bore(m.bufferPerm)
      val allocate = BoringUtils.bore(m.allocate)
      val explicitRelease = BoringUtils.bore(m.explicitRelease)
      val allocationIndex = BoringUtils.bore(m.alloc)
      val busy = VecInit((0 until entries).map(i => states(i) =/= 0.U || releases(i) =/= 0.U))
      def addressSet(address: UInt): UInt = address(log2Ceil(sets), 1)
      val selectedSet = addressSet(io(index).inputAddr)

      fvAssert(!commit || commitLine < lines.U, s"param_way_index__l1_$index")
      val writesAddress = commit && bufferPermissions(commitIndex) =/= 0.U && !valid(commitLine)
      val wrote = RegNext(writesAddress, false.B)
      val writtenLine = RegNext(commitLine)
      val oldAddresses = RegNext(addresses)
      // Only the selected row can change its stored address during a directory commit.
      for (way <- 0 until ways) {
        val row = (writtenLine / ways.U) * ways.U + way.U
        fvAssert(!wrote || writtenLine === row || addresses(row) === oldAddresses(row),
          s"param_way_isolation__l1_${index}_$way")
      }
      for (left <- 0 until ways; right <- left + 1 until ways) {
        val a = selectedSet * ways.U + left.U
        val b = selectedSet * ways.U + right.U
        fvAssert(!valid(a) || !valid(b) || addresses(a) =/= addresses(b),
          s"param_unique_live_tag__l1_${index}_${left}_$right")
      }
      for (set <- 0 until sets) {
        chisel3.ltl.CoverProperty((allocate || explicitRelease) && selectedSet === set.U,
          s"param_cover_set__l1_${index}_$set")
      }
      chisel3.ltl.CoverProperty(valid(selectedSet * ways.U) &&
        valid(selectedSet * ways.U + 1.U), s"param_cover_two_ways__l1_$index")
      val allocated = RegNext(allocate, false.B)
      val allocatedIndex = RegNext(allocationIndex)
      val allocatedAddress = RegNext((io(index).inputAddr >> 1) << 1)
      val rowsMatchSets = (0 until lines).map(row =>
        !valid(row) || (addressSet(addresses(row)) === (row / ways).U && addresses(row)(0) === 0.U))
      fvAssert((!allocated || requestAddresses(allocatedIndex) === allocatedAddress) &&
        rowsMatchSets.reduce(_ && _), s"param_address_decode__l1_$index")

      val occupied = PopCount(busy)
      fvAssert(occupied <= entries.U, s"param_mshr_capacity__l1_$index")
      // Allocation safety only; this property does not require eventual completion.
      fvAssert(!(allocate || explicitRelease) ||
        (allocationIndex < entries.U && !busy(allocationIndex)),
        s"param_mshr_progress__l1_$index")
      for (left <- 0 until entries; right <- left + 1 until entries) {
        fvAssert(states(left) === 0.U || states(right) === 0.U ||
          requestAddresses(left) =/= requestAddresses(right),
          s"param_mshr_unique__l1_${index}_${left}_$right")
      }
      chisel3.ltl.CoverProperty(occupied >= 2.U, s"param_cover_two_mshrs__l1_$index")

      val aValid = BoringUtils.bore(m.out.a.valid)
      val aReady = BoringUtils.bore(m.out.a.ready)
      val aBits = BoringUtils.bore(m.out.a.bits)
      val acquire = aValid && (aBits.opcode === TLMessages.AcquireBlock ||
        aBits.opcode === TLMessages.AcquirePerm)
      fvAssert(!acquire || aBits.address(0) === 0.U, s"param_block_boundary__l1_$index")
      fvAssert(!acquire || aBits.size === 1.U, s"param_block_size__l1_$index")
      fvAssert(!acquire || aBits.mask === 1.U, s"param_beat_shape__l1_$index")
      val aStalled = RegNext(aValid && !aReady, false.B)
      val aPrevious = RegNext(aBits.asUInt)
      fvAssert(!aStalled || (aValid && aBits.asUInt === aPrevious),
        s"param_beat_stable__l1_$index")
      val cValid = BoringUtils.bore(m.out.c.valid)
      val cReady = BoringUtils.bore(m.out.c.ready)
      val cBits = BoringUtils.bore(m.out.c.bits)
      val cStalled = RegNext(cValid && !cReady, false.B)
      val cPrevious = RegNext(cBits.asUInt)
      fvAssert(!cStalled || (cValid && cBits.asUInt === cPrevious),
        s"param_beat_stable__l1_${index}_c")
      fvAssert(!cValid || cBits.size <= 1.U, s"param_beat_shape__l1_${index}_c")
      val dValid = BoringUtils.bore(m.out.d.valid)
      val dReady = BoringUtils.bore(m.out.d.ready)
      val dBits = BoringUtils.bore(m.out.d.bits)
      val dStalled = RegNext(dValid && !dReady, false.B)
      val dPrevious = RegNext(dBits.asUInt)
      fvAssert(!dStalled || (dValid && dBits.asUInt === dPrevious),
        s"param_beat_stable__l1_${index}_d")
      fvAssert(!dValid || dBits.size <= 1.U, s"param_beat_shape__l1_${index}_d")
    }
    messageGenerators.zipWithIndex.foreach { case (generator, i) => checkGenerator(generator, i) }

    coupledL2.zipWithIndex.foreach { case (l2, i) =>
      checkSlice(l2.module.slices.head.asInstanceOf[L2Slice], "l2", i,
        tuning.l2Ways, tuning.l2Sets, tuning.l2Mshrs)
    }

    val l3Slice = l3.module.slices.head
    val l3Sync = l3Slice.directorySync.get
    val l3Tags = BoringUtils.bore(l3Sync.io.selfTagArray)
    val l3States = BoringUtils.bore(l3Sync.io.selfDirArray)
    val l3TagWrite = BoringUtils.bore(l3Sync.io.selfTagW)
    val l3InA = l3Slice.sinkA.io.a
    val l3InValid = BoringUtils.bore(l3InA.valid)
    val l3InReady = BoringUtils.bore(l3InA.ready)
    val l3Address = BoringUtils.bore(l3InA.bits.address)
    val l3Set = l3Address(log2Ceil(tuning.l3Sets), 1)
    // A valid L3 tag write must select a configured way.
    fvAssert(!l3TagWrite.valid || l3TagWrite.bits.way < tuning.l3Ways.U,
      "param_way_index__l3")
    val l3OldTags = RegNext(l3Tags(l3TagWrite.bits.set))
    val l3Wrote = RegNext(l3TagWrite.valid, false.B)
    val l3WrittenSet = RegNext(l3TagWrite.bits.set)
    val l3WrittenWay = RegNext(l3TagWrite.bits.way)
    // A tag write must preserve the other ways in its set.
    for (way <- 0 until tuning.l3Ways) {
      fvAssert(!l3Wrote || l3WrittenWay === way.U ||
        l3Tags(l3WrittenSet)(way) === l3OldTags(way),
        s"param_way_isolation__l3_$way")
    }
    // Two live ways in the observed set must have different tags.
    for (left <- 0 until tuning.l3Ways; right <- left + 1 until tuning.l3Ways) {
      fvAssert(l3States(l3Set)(left).state === 0.U ||
        l3States(l3Set)(right).state === 0.U ||
        l3Tags(l3Set)(left) =/= l3Tags(l3Set)(right),
        s"param_unique_live_tag__l3_${left}_$right")
    }
    // Cover an accepted A request for every L3 set.
    for (set <- 0 until tuning.l3Sets) {
      chisel3.ltl.CoverProperty(l3InValid && l3InReady && l3Set === set.U,
        s"param_cover_set__l3_$set")
    }
    // Cover ways 0 and 1 being live in the observed set.
    chisel3.ltl.CoverProperty(l3States(l3Set)(0).state =/= 0.U &&
      l3States(l3Set)(1).state =/= 0.U, "param_cover_two_ways__l3")
    val l3Alloc = l3Slice.sinkA.io.alloc
    val l3AllocSet = BoringUtils.bore(l3Alloc.bits.set)
    val l3AllocTag = BoringUtils.bore(l3Alloc.bits.tag)
    val l3AllocOff = BoringUtils.bore(l3Alloc.bits.off)
    // On an L3 A handshake, allocation set/tag/offset must match the address.
    fvAssert(!l3InValid || !l3InReady ||
      (l3AllocSet === l3Set &&
       l3AllocTag === (l3Address >> (log2Ceil(tuning.l3Sets) + 1)).asUInt &&
       l3AllocOff === l3Address(0)), "param_address_decode__l3")
    val l3Slots = (0 until tuning.l3Mshrs).map { i =>
      (BoringUtils.bore(l3Slice.ms(i).io.status.valid),
       BoringUtils.bore(l3Slice.ms(i).io.status.bits.set),
       BoringUtils.bore(l3Slice.ms(i).io.status.bits.tag))
    }
    val l3Occupied = PopCount(l3Slots.map(_._1))
    // The observed ordinary MSHR occupancy must fit the configured count.
    fvAssert(l3Occupied <= tuning.l3Mshrs.U, "param_mshr_capacity__l3")
    // Two valid MSHRs must not track the same set/tag.
    for (left <- 0 until tuning.l3Mshrs; right <- left + 1 until tuning.l3Mshrs) {
      val (validA, setA, tagA) = l3Slots(left)
      val (validB, setB, tagB) = l3Slots(right)
      fvAssert(!(validA && validB && setA === setB && tagA === tagB),
        s"param_mshr_unique__l3_${left}_$right")
    }
    val l3Allocations = (0 until tuning.l3Mshrs).map(i =>
      BoringUtils.bore(l3Slice.mshrAlloc.io.alloc(i).valid))
    // An MSHR allocation needs free capacity; eventual completion is not checked.
    fvAssert(!l3Allocations.reduce(_ || _) || l3Occupied < tuning.l3Mshrs.U,
      "param_mshr_progress__l3")
    // Cover at least two ordinary L3 MSHRs being valid together.
    chisel3.ltl.CoverProperty(l3Occupied >= 2.U, "param_cover_two_mshrs__l3")

    val l3OutA = l3Slice.io.out.a
    val l3OutValid = BoringUtils.bore(l3OutA.valid)
    val l3OutReady = BoringUtils.bore(l3OutA.ready)
    val l3OutBits = BoringUtils.bore(l3OutA.bits)
    val l3BlockRequest = l3OutValid &&
      (l3OutBits.opcode === TLMessages.AcquireBlock ||
       l3OutBits.opcode === TLMessages.AcquirePerm)
    // A valid AcquireBlock/AcquirePerm must be aligned to a two-byte block.
    fvAssert(!l3BlockRequest || l3OutBits.address(0) === 0.U,
      "param_block_boundary__l3")
    // Block Acquires use size=1, a two-byte transfer.
    fvAssert(!l3BlockRequest || l3OutBits.size === 1.U,
      "param_block_size__l3")
    // Block Acquires use mask=1 on the one-byte beat.
    fvAssert(!l3BlockRequest || l3OutBits.mask === 1.U,
      "param_beat_shape__l3")
    val l3OutStalled = RegNext(l3OutValid && !l3OutReady, false.B)
    val l3OutPrevious = RegNext(l3OutBits.asUInt)
    // Keep A valid and its payload stable after a stalled cycle.
    fvAssert(!l3OutStalled ||
      (l3OutValid && l3OutBits.asUInt === l3OutPrevious),
      "param_beat_stable__l3")
    val l3CValid = BoringUtils.bore(l3Slice.io.out.c.valid)
    val l3CReady = BoringUtils.bore(l3Slice.io.out.c.ready)
    val l3CBits = BoringUtils.bore(l3Slice.io.out.c.bits)
    val l3CStalled = RegNext(l3CValid && !l3CReady, false.B)
    val l3CPrevious = RegNext(l3CBits.asUInt)
    // Keep C valid and its payload stable after a stalled cycle.
    fvAssert(!l3CStalled || (l3CValid && l3CBits.asUInt === l3CPrevious),
      "param_beat_stable__l3_c")
    // Valid C messages must transfer at most two bytes.
    fvAssert(!l3CValid || l3CBits.size <= 1.U, "param_beat_shape__l3_c")
    // Observe the cache's D producer, before the environment's TLDelayer.
    val l3DValid = BoringUtils.bore(l3Slice.io.in.d.valid)
    val l3DReady = BoringUtils.bore(l3Slice.io.in.d.ready)
    val l3DBits = BoringUtils.bore(l3Slice.io.in.d.bits)
    val l3DStalled = RegNext(l3DValid && !l3DReady, false.B)
    val l3DPrevious = RegNext(l3DBits.asUInt)
    // Keep D valid and its payload stable after a stalled cycle.
    fvAssert(!l3DStalled || (l3DValid && l3DBits.asUInt === l3DPrevious),
      "param_beat_stable__l3_d")
    // Valid D messages must transfer at most two bytes.
    fvAssert(!l3DValid || l3DBits.size <= 1.U, "param_beat_shape__l3_d")

    val sourceA = l3.module.slices.head.sourceA
    val putValid = BoringUtils.bore(sourceA.io.a.valid)
    val putReady = BoringUtils.bore(sourceA.io.a.ready)
    val putBits = BoringUtils.bore(sourceA.io.a.bits)
    val assembled = BoringUtils.bore(sourceA.s1_pb_latch)
    val isPut = putValid && putBits.opcode === TLMessages.PutFullData
    // A valid PutFullData must match the assembly buffer data and mask.
    fvAssert(!isPut || (putBits.data === assembled.data &&
      putBits.mask === assembled.mask), "param_block_assembly__l3")
    val firstPut = RegInit(false.B)
    val firstAddress = Reg(UInt(putBits.address.getWidth.W))
    val firstSource = Reg(UInt(putBits.source.getWidth.W))
    when(isPut && putReady && putBits.size === 1.U) {
      when(!firstPut) {
        firstPut := true.B
        firstAddress := putBits.address
        firstSource := putBits.source
      }.otherwise {
        firstPut := false.B
      }
    }
    // After the first beat, the next accepted PutFullData keeps its address and source.
    fvAssert(!isPut || !putReady || !firstPut ||
      (putBits.address === firstAddress && putBits.source === firstSource),
      "param_block_assembly__l3_pair")

    // Watch one byte address chosen by the first masked-in write; this is a single-address monitor.
    val ramA = ram.module.in.a
    val ramD = ram.module.in.d
    val ramAValid = BoringUtils.bore(ramA.valid)
    val ramAReady = BoringUtils.bore(ramA.ready)
    val ramABits = BoringUtils.bore(ramA.bits)
    val ramDValid = BoringUtils.bore(ramD.valid)
    val ramDReady = BoringUtils.bore(ramD.ready)
    val ramDBits = BoringUtils.bore(ramD.bits)
    val write = ramAValid && ramAReady &&
      (ramABits.opcode === TLMessages.PutFullData ||
       ramABits.opcode === TLMessages.PutPartialData)
    val read = ramAValid && ramAReady && ramABits.opcode === TLMessages.Get
    val known = RegInit(false.B)
    val watchedAddress = Reg(UInt(ramABits.address.getWidth.W))
    val watchedData = Reg(UInt(8.W))
    when(write && !known && ramABits.mask(0)) {
      known := true.B
      watchedAddress := ramABits.address
      watchedData := ramABits.data
    }.elsewhen(write && known && ramABits.address === watchedAddress && ramABits.mask(0)) {
      watchedData := ramABits.data
    }
    val pending = RegInit(false.B)
    val pendingSource = Reg(UInt(ramABits.source.getWidth.W))
    val expectedData = Reg(UInt(8.W))
    val maskedWrite = RegInit(false.B)
    val pendingMaskedWrite = Reg(Bool())
    when(write && known && ramABits.address === watchedAddress) {
      maskedWrite := !ramABits.mask(0)
    }
    when(read && known && ramABits.address === watchedAddress && !pending) {
      pending := true.B
      pendingSource := ramABits.source
      expectedData := watchedData
      pendingMaskedWrite := maskedWrite
    }
    val matchedRead = pending && ramDValid && ramDReady &&
      ramDBits.source === pendingSource && ramDBits.opcode === TLMessages.AccessAckData
    when(matchedRead) { pending := false.B }
    // A source-matched read response must return the byte saved at request acceptance.
    fvAssert(!matchedRead || ramDBits.data === expectedData, "param_ram_data__readback")
    // A preceding mask=0 write to the watched byte must preserve readback data.
    fvAssert(!matchedRead || !pendingMaskedWrite || ramDBits.data === expectedData,
      "param_ram_lanes__masked_write")
    // Cover a matched response for the watched address; a mask=0 write is not required.
    chisel3.ltl.CoverProperty(matchedRead, "param_cover_ram_readback")
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

object ParamVerifyTop extends App {
  val entries = args.map(_.split("=", 2))
  require(entries.forall(_.length == 2), "expected key=value arguments")
  require(entries.map(_(0)).distinct.length == entries.length, "duplicate parameter")
  val values = entries.map(x => x(0) -> x(1)).toMap
  val keys = Seq("l1.ways", "l1.sets", "l1.blockBytes", "l1.mshrs", "l1.channelBytes",
    "l2.ways", "l2.sets", "l2.blockBytes", "l2.mshrs", "l2.channelBytes",
    "l3.ways", "l3.sets", "l3.blockBytes", "l3.mshrs", "l3.channelBytes", "ram.width")
  require(values.keySet == (keys :+ "output").toSet, "expected complete candidate and output")
  def get(key: String): Int = values(key).toInt
  for (layer <- Seq("l1", "l2", "l3")) {
    require(get(s"$layer.blockBytes") == 2, s"$layer.blockBytes is fixed at 2")
    require(get(s"$layer.channelBytes") == 1, s"$layer.channelBytes is fixed at 1")
  }
  val tuning = TuningParams(
    get("l1.ways"), get("l1.sets"), get("l1.mshrs"),
    get("l2.ways"), get("l2.sets"), get("l2.mshrs"),
    get("l3.ways"), get("l3.sets"), get("l3.mshrs"), get("ram.width")
  )
  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))))
    case HCCacheParamsKey => HCCacheParameters(echoField = Seq(DirtyField()))
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop(tuning)(p)))(config)
  val output = new File(values("output"))
  require(output.getName == "VerifyTop.sv", "output must be VerifyTop.sv")
  output.getParentFile.mkdirs()
  val emitted = ChiselStage.emitSystemVerilog(top.module,
    args = Array("--warn-conf", "id=4:s"),
    firtoolOpts = Array("--disable-annotation-unknown"))
  val resources = emitted.indexOf("// ----- 8< ----- FILE \"./ClockGate.sv\"")
  require(resources > 0 && emitted.substring(resources).contains("firrtl_black_box_resource_files.f"),
    "unexpected firtool resource appendix")
  FileRegisters.writeOutputFile(
    output.getParent,
    output.getName,
    emitted.substring(0, resources)
  )
}
