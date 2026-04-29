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
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import nativeL1._
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

  val nativeL1Clients = (0 until nrL2).map(i => LazyModule(new NativeL1DCacheClient(s"L0_$i", 32)))
  val l1_nodes = nativeL1Clients.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alter((_, here, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = 4,
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
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = 6,
      inclusive = false,
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

  l1_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1, l2), i) => l2 := 
        TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
        TLBuffer() := l1
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
      // Native-L1-like inputs: acquire/release requests + B/D ready controls.
      val inputAcquireValid = Input(Bool())
      val inputAcquireAddr = Input(UInt(nativeL1Clients.head.module.io.acquireAddr.getWidth.W))
      val inputAcquireSize = Input(UInt(nativeL1Clients.head.module.io.acquireSize.getWidth.W))
      val inputAcquireCmd = Input(UInt(nativeL1Clients.head.module.io.acquireCmd.getWidth.W))
      val inputAcquireNeedT = Input(Bool())
      val inputAcquireSource = Input(UInt(nativeL1Clients.head.module.io.acquireSource.getWidth.W))

      val inputReleaseValid = Input(Bool())
      val inputReleaseAddr = Input(UInt(nativeL1Clients.head.module.io.releaseAddr.getWidth.W))
      val inputReleaseSize = Input(UInt(nativeL1Clients.head.module.io.releaseSize.getWidth.W))
      val inputReleaseData = Input(UInt(nativeL1Clients.head.module.io.releaseData.getWidth.W))
      val inputReleaseToB = Input(Bool())
      val inputReleaseSource = Input(UInt(nativeL1Clients.head.module.io.releaseSource.getWidth.W))

      // LSU-like native requests (load/store/atomics)
      val lsuLoadValid = Input(Bool())
      val lsuLoadAddr = Input(UInt(nativeL1Clients.head.module.io.lsuLoadAddr.getWidth.W))
      val lsuLoadCmd = Input(UInt(nativeL1Clients.head.module.io.lsuLoadCmd.getWidth.W))
      val lsuLoadId = Input(UInt(nativeL1Clients.head.module.io.lsuLoadId.getWidth.W))

      val lsuStoreValid = Input(Bool())
      val lsuStoreAddr = Input(UInt(nativeL1Clients.head.module.io.lsuStoreAddr.getWidth.W))
      val lsuStoreCmd = Input(UInt(nativeL1Clients.head.module.io.lsuStoreCmd.getWidth.W))
      val lsuStoreId = Input(UInt(nativeL1Clients.head.module.io.lsuStoreId.getWidth.W))
      val lsuStoreData = Input(UInt(nativeL1Clients.head.module.io.lsuStoreData.getWidth.W))
      val lsuStoreMask = Input(UInt(nativeL1Clients.head.module.io.lsuStoreMask.getWidth.W))

      val lsuAmoValid = Input(Bool())
      val lsuAmoAddr = Input(UInt(nativeL1Clients.head.module.io.lsuAmoAddr.getWidth.W))
      val lsuAmoCmd = Input(UInt(nativeL1Clients.head.module.io.lsuAmoCmd.getWidth.W))
      val lsuAmoId = Input(UInt(nativeL1Clients.head.module.io.lsuAmoId.getWidth.W))
      val lsuAmoData = Input(UInt(nativeL1Clients.head.module.io.lsuAmoData.getWidth.W))
      val lsuAmoMask = Input(UInt(nativeL1Clients.head.module.io.lsuAmoMask.getWidth.W))

      val inputBReady = Input(Bool())
      val inputDReady = Input(Bool())
    }))

    nativeL1Clients.zipWithIndex.foreach {
      case (l1, i) =>
        l1.module.io.acquireValid := io(i).inputAcquireValid
        l1.module.io.acquireAddr := io(i).inputAcquireAddr
        l1.module.io.acquireSize := io(i).inputAcquireSize
        l1.module.io.acquireCmd := io(i).inputAcquireCmd
        l1.module.io.acquireNeedT := io(i).inputAcquireNeedT
        l1.module.io.acquireSource := io(i).inputAcquireSource

        l1.module.io.releaseValid := io(i).inputReleaseValid
        l1.module.io.releaseAddr := io(i).inputReleaseAddr
        l1.module.io.releaseSize := io(i).inputReleaseSize
        l1.module.io.releaseData := io(i).inputReleaseData
        l1.module.io.releaseToB := io(i).inputReleaseToB
        l1.module.io.releaseSource := io(i).inputReleaseSource

        l1.module.io.lsuLoadValid := io(i).lsuLoadValid
        l1.module.io.lsuLoadAddr := io(i).lsuLoadAddr
        l1.module.io.lsuLoadCmd := io(i).lsuLoadCmd
        l1.module.io.lsuLoadId := io(i).lsuLoadId

        l1.module.io.lsuStoreValid := io(i).lsuStoreValid
        l1.module.io.lsuStoreAddr := io(i).lsuStoreAddr
        l1.module.io.lsuStoreCmd := io(i).lsuStoreCmd
        l1.module.io.lsuStoreId := io(i).lsuStoreId
        l1.module.io.lsuStoreData := io(i).lsuStoreData
        l1.module.io.lsuStoreMask := io(i).lsuStoreMask

        l1.module.io.lsuAmoValid := io(i).lsuAmoValid
        l1.module.io.lsuAmoAddr := io(i).lsuAmoAddr
        l1.module.io.lsuAmoCmd := io(i).lsuAmoCmd
        l1.module.io.lsuAmoId := io(i).lsuAmoId
        l1.module.io.lsuAmoData := io(i).lsuAmoData
        l1.module.io.lsuAmoMask := io(i).lsuAmoMask

        l1.module.io.inputBReady := io(i).inputBReady
        l1.module.io.inputDReady := io(i).inputDReady
    }

    coupledL2(0).module.slices.head match {
      case tlSlice: L2Slice =>
        val dir_resetFinish = BoringUtils.bore(tlSlice.directory.resetFinish)
        assume(verify_timer < 200.U || dir_resetFinish)
    }

    val timer = 800
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
      }
    }

    // ========== Mutual, Inclusive, Consistency Properties ==========
    
    // Address parsing functions
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

    val l3_offsetBits = 1
    val l3_bankBits = 0
    val l3_setBits = 2
    val l3_tagBits = 2

    def parseL3Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l3_offsetBits + l3_bankBits)
      val tag = set >> l3_setBits
      (tag(l3_tagBits - 1, 0), set(l3_setBits - 1, 0), offset(l3_offsetBits - 1, 0))
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

    // Get L3 arrays from directorySync
    val l3_selfStateArrays: Vec[Vec[UInt]] = {
      val slice = l3.module.slices.head
      slice.directorySync.map { sync =>
        val bored = BoringUtils.bore(sync.io.selfDirArray)
        VecInit(bored.toSeq.map(set => VecInit(set.toSeq.map(_.state))))
      }.getOrElse(VecInit(Seq.fill(4)(VecInit(Seq.fill(2)(0.U(2.W))))))
    }

    val l3_selfTagArrays: Vec[Vec[UInt]] = {
      val slice = l3.module.slices.head
      slice.directorySync.map { sync =>
        val bored = BoringUtils.bore(sync.io.selfTagArray)
        VecInit(bored.toSeq.map(set => VecInit(set.toSeq: Seq[UInt])))
      }.getOrElse(VecInit(Seq.fill(4)(VecInit(Seq.fill(2)(0.U(2.W))))))
    }

    // Get MSHR signals for property patches
    val mshrs = 4
    val l3_mshrs = 6

    class MSHRSignal(setBits: Int) extends Bundle {
      val status_bits_set = UInt(setBits.W)
      val status_valid = Bool()
    }

    val L2_MSHR_signals = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          (0 until mshrs).map { i =>
            val mshr_sig = Wire(new MSHRSignal(l2_setBits))
            mshr_sig.status_bits_set := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.set)
            mshr_sig.status_valid := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.valid)
            mshr_sig
          }
      }
    }

    // Get L3 MSHR signals for L2-L3 mutual exclusivity patch
    val L3_MSHR_signals = l3.module.slices.head.ms.map { mshr =>
      val mshr_sig = Wire(new MSHRSignal(l3_setBits))
      mshr_sig.status_bits_set := BoringUtils.bore(mshr.io.status.bits.set)
      mshr_sig.status_valid := BoringUtils.bore(mshr.io.status.valid)
      mshr_sig
    }

    val l2_MSHR_prop_patch = coupledL2.indices.map { indx =>
      Cat(L2_MSHR_signals(indx).map { m =>
        m.status_bits_set === 0.U &&
        m.status_valid === true.B
      }).orR
    }

    val l3_MSHR_prop_patch = Cat(L3_MSHR_signals.map { m =>
      m.status_bits_set === 0.U && 
      m.status_valid === true.B
    }).orR

    // l2l3mutual: 补l2 mainpipe task_s1(request_arb)/s2/s3(mainpipe) set=0
    val l2_reqarb_prop_patch_s1 = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice => 
          val req_arb_vaild = BoringUtils.bore(tlSlice.reqArb.task_s1.valid)
          val req_arb_set = BoringUtils.bore(tlSlice.reqArb.task_s1.bits.set)
          req_arb_vaild && req_arb_set === 0.U
        case _ => false.B
      }
    }
    val l2_mainpipe_prop_patch_s2 = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice => 
          val mainpipe_s2_vaild = BoringUtils.bore(tlSlice.mainPipe.task_s2.valid)
          val mainpipe_s2_set = BoringUtils.bore(tlSlice.mainPipe.task_s2.bits.set)
          mainpipe_s2_vaild && mainpipe_s2_set === 0.U
        case _ => false.B
      }
    }
    val l2_mainpipe_prop_patch_s3 = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice => 
          val mainpipe_s3_vaild = BoringUtils.bore(tlSlice.mainPipe.task_s3.valid)
          val mainpipe_s3_set = BoringUtils.bore(tlSlice.mainPipe.task_s3.bits.set)
          mainpipe_s3_vaild && mainpipe_s3_set === 0.U
        case _ => false.B
      }
    }
    // l2.module.slices.head.reqarb.mshr_task_s1.valid && ???.bits.set === 0.U
    // l2.module.slices.head.reqarb.task_s1.valid && ???.bits.set === 0.U
    // l2.module.slices.mainpipe.task_s2.valid && ???.bits.set === 0.U
    // l2.module.slices.mainpipe.task_s3.valid && ???.bits.set === 0.U

    // Mutual exclusivity property (L2-L2)
    def l2_mutual(addr: UInt, state1: UInt, state2: UInt): Unit = {
      if(state1 == INVALID || state2 == INVALID) {
        l2_mutual_with_invalid(addr, state1, state2)
        return
      }

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

    def l2_mutual_with_invalid(addr: UInt, state1: UInt, state2: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val hit0 = Mux(state1 === INVALID,
        !l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
          case (tag0, state0) =>
            tag === tag0 && state0 =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
          case (tag0, state0) =>
            tag === tag0 && state0 === state1
        }.reduce(_ || _)
      )

      val hit1 = Mux(state2 === INVALID,
        !l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
          case (tag1, state1_actual) =>
            tag === tag1 && state1_actual =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
          case (tag1, state1_actual) =>
            tag === tag1 && state1_actual === state2
        }.reduce(_ || _)
      )

      fvAssert(!(hit0 && hit1))
    }

    def l2_mutual_specs(): Unit = {
      l2_mutual(0.U(32.W), TIP, TIP)
      l2_mutual(0.U(32.W), TIP, BRANCH)
    }

    // L2-L3 mutual exclusivity property
    def l2_l3_mutual(addr: UInt, l2_state: UInt, l3_state: UInt, l2_idx: Int = 0): Unit = {
      if(l2_state == INVALID || l3_state == INVALID) {
        l2_l3_mutual_with_invalid(addr, l2_state, l3_state, l2_idx)
        return
      }

      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      val (l3_tag, l3_set, l3_offset) = parseL3Address(addr)

      val l2_hit_vec = l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      val l3_hit_vec = l3_selfTagArrays(l3_set).zip(l3_selfStateArrays(l3_set)).map {
        case (tag, state) =>
          tag === l3_tag && state =/= INVALID
      }
      val l3_hit = l3_hit_vec.reduce(_ || _)
      val l3_way = OHToUInt(l3_hit_vec)

      val mutualViolation =
        l2_hit && l2_stateArrays(l2_idx)(l2_set)(l2_way) === l2_state &&
        l3_hit && l3_selfStateArrays(l3_set)(l3_way) === l3_state

      fvAssert(
        !mutualViolation || 
        l3_MSHR_prop_patch || 
        l2_MSHR_prop_patch(l2_idx) ||
        l2_reqarb_prop_patch_s1(l2_idx) || 
        l2_mainpipe_prop_patch_s2(l2_idx) ||
        l2_mainpipe_prop_patch_s3(l2_idx)
      )
    }

    def l2_l3_mutual_with_invalid(addr: UInt, l2_state: UInt, l3_state: UInt, l2_idx: Int = 0): Unit = {
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      val (l3_tag, l3_set, l3_offset) = parseL3Address(addr)

      val l2_hit = Mux(l2_state === INVALID,
        !l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
          case (tag2, state2) =>
            tag2 === l2_tag && state2 =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
          case (tag2, state2) =>
            tag2 === l2_tag && state2 === l2_state
        }.reduce(_ || _)
      )

      val l3_hit = Mux(l3_state === INVALID,
        !l3_selfTagArrays(l3_set).zip(l3_selfStateArrays(l3_set)).map {
          case (tag3, state3) =>
            tag3 === l3_tag && state3 =/= INVALID
        }.reduce(_ || _)
      ,
        l3_selfTagArrays(l3_set).zip(l3_selfStateArrays(l3_set)).map {
          case (tag3, state3) =>
            tag3 === l3_tag && state3 === l3_state
        }.reduce(_ || _)
      )

      fvAssert(
        !(l2_hit && l3_hit) || 
        l3_MSHR_prop_patch || 
        l2_MSHR_prop_patch(l2_idx) ||
        l2_reqarb_prop_patch_s1(l2_idx) || 
        l2_mainpipe_prop_patch_s2(l2_idx) ||
        l2_mainpipe_prop_patch_s3(l2_idx)
      )
    }

    def l2_l3_mutual_specs(): Unit = {
      // From the table, impossible combinations (marked as 0):
      // L3=TT, L2=TT
      l2_l3_mutual(0.U(32.W), TIP, TIP, 0)
      l2_l3_mutual(0.U(32.W), TIP, TIP, 1)
      // L3=TT, L2=T
      l2_l3_mutual(0.U(32.W), TRUNK, TIP, 0)
      l2_l3_mutual(0.U(32.W), TRUNK, TIP, 1)
      // // L3=T, L2=T
      // l2_l3_mutual(0.U(32.W), TRUNK, TRUNK, 0)
      // l2_l3_mutual(0.U(32.W), TRUNK, TRUNK, 1)
      // L3=T, L2=B
      l2_l3_mutual(0.U(32.W), BRANCH, TRUNK, 0)
      l2_l3_mutual(0.U(32.W), BRANCH, TRUNK, 1)
      // L3=T, L2=N
      l2_l3_mutual(0.U(32.W), INVALID, TRUNK, 0)
      l2_l3_mutual(0.U(32.W), INVALID, TRUNK, 1)
      // L3=B, L2=T
      l2_l3_mutual(0.U(32.W), TRUNK, BRANCH, 0)
      l2_l3_mutual(0.U(32.W), TRUNK, BRANCH, 1)
    }

    // Consistency property
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
               l2_MSHR_prop_patch(0) || 
               l2_MSHR_prop_patch(1))
    }

    def consistency_spec(): Unit = {
      l2_consistency(0.U(32.W))
    }

    // Enable verification properties (comment out as needed)
    l2_mutual_specs()
    l2_l3_mutual_specs()
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