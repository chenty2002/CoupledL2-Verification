package coupledL2

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2.tl2tl.TL2TLCoupledL2
import coupledL2.tl2tl.{Slice => TLSlice}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}
import org.chipsalliance.cde.config._
import utility._
import dataclass.data
import scala.sys

object VerifyMode {
  private val envName = "VERIFY_MODE"

  def resolveUseLarge(): Boolean = {
    sys.env.get(envName).map(_.trim.toLowerCase) match {
      case Some("large") => true
      case Some("small") => false
      case Some(other) =>
        throw new IllegalArgumentException(
          s"Unsupported $envName=$other, expected 'small' or 'large'"
        )
      case None =>
        throw new IllegalArgumentException(
          s"Set $envName=small or $envName=large before generating VerifyTop"
        )
    }
  }
}

class VerifyTop_L2L3L2(useLarge: Boolean = false)(implicit p: Parameters) extends LazyModule {

  /* MG0   MG1
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  println("class VerifyTop_L2L3L2:")

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL2 = 2

  val messageGenerators: Seq[TLMessageGenerator] =
    (0 until nrL2).map(i => LazyModule(new TLMessageGenerator(
      MessageGeneratorParam(
        name = s"msgGen$i",
        sets = if (useLarge) 64 else 2,
        ways = if (useLarge) 4 else 2,
        blockBytes = if (useLarge) 64 else 2,
        channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
        sourceIdRange = IdRange(0, if (useLarge) 16 else 4),
        ordinaryEntries = if (useLarge) 15 else 3,
        retainedGrantCopy = !useLarge,
        reqField = Seq(AliasField(2)),
        respKey = cacheParams.respKey,
        supportsProbe = Some(TransferSizes(if (useLarge) 64 else 2)),
        minLatency = 1
      )
    )))

  val req_nodes = messageGenerators.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = if (useLarge) 4 else 2,
      sets = if (useLarge) 128 else 4,
      blockBytes = if (useLarge) 64 else 2,
      channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = if (useLarge) 16 else 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = if (useLarge) 4 else 2,
      sets = if (useLarge) 128 else 4,
      blockBytes = if (useLarge) 64 else 2,
      channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = if (useLarge) 128 else 4,
          ways = if (useLarge) 4 + 2 else 2 + 2,
          blockGranularity = log2Ceil(if (useLarge) 128 else 2)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = if (useLarge) 14 else 6
    )
  })))

  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, if (useLarge) 0xff_ffffL else 0x1fL), beatBytes = if (useLarge) 32 else 1))

  req_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(if (useLarge) 32 else 1, if (useLarge) 64 else 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3") :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    coupledL2.foreach(_.module.io.debugTopDown := DontCare)

    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2.foreach(_.module.io.hartId <> DontCare)

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputValids = Input(Vec(nrL2, Bool()))
      val msggenRelease = Input(Vec(nrL2, Bool()))
      val msggenStore = Input(Vec(nrL2, Bool()))
      val msggenData = Input(Vec(nrL2, UInt((if (useLarge) 512 else 16).W)))
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(ram.node.in.head._2.bundle.addressBits.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    messageGenerators.zipWithIndex.foreach { case (msgGen, i) =>
      msgGen.module.io.in_valid := true.B
      msgGen.module.io.in_addr := io.topInputRandomAddrs(i)
      msgGen.module.io.in_isAcquire := !io.msggenRelease(i)
      msgGen.module.io.in_store := io.msggenStore(i)
      msgGen.module.io.in_data := io.msggenData(i)
      msgGen.module.io.in_param := io.topInputNeedT(i)
      dontTouch(msgGen.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val l2_offsetBits = if (useLarge) 6 else 1
    val l2_setBits = if (useLarge) 7 else 2

    def parseL2Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> l2_offsetBits
      val tag = set >> l2_setBits
      (tag, set(l2_setBits - 1, 0), offset(l2_offsetBits - 1, 0))
    }

    val l3_offsetBits = if (useLarge) 6 else 1
    val l3_setBits = if (useLarge) 7 else 2

    def parseL3Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> l3_offsetBits
      val tag = set >> l3_setBits
      (tag, set(l3_setBits - 1, 0), offset(l3_offsetBits - 1, 0))
    }

    def boreOut[T <: Data](source: T): T = {
      val sink = WireDefault(0.U.asTypeOf(chiselTypeOf(source)))
      BoringUtils.bore(source, Seq(sink))
      sink
    }

    val l2_slices = coupledL2.map(_.module.slices.head.asInstanceOf[TLSlice])

    val l2Sets = if (useLarge) 128 else 4
    val l2Ways = if (useLarge) 4 else 2
    val l2TagBits = if (useLarge) 11 else 2

    val l2_stateArrays = Seq.fill(nrL2)(WireDefault(VecInit.fill(l2Sets, l2Ways)(0.U(2.W))))
    val l2_tagArrays = Seq.fill(nrL2)(WireDefault(VecInit.fill(l2Sets, l2Ways)(0.U(l2TagBits.W))))

    for (i <- 0 until nrL2) {
      BoringUtils.bore(l2_slices(i).directoryTest.stateArray, Seq(l2_stateArrays(i)))
      BoringUtils.bore(l2_slices(i).directoryTest.tagArray, Seq(l2_tagArrays(i)))
    }

    val l3_directory = l3.module.slices.head.directory match {
      case dir: huancun.noninclusive.Directory => dir
      case _ => throw new IllegalStateException("L3 must be noninclusive for mutual assertions")
    }
    val l3_dirResult = boreOut(l3_directory.io.result)
    // HuanCun exposes stage1 valid; result bits arrive in stage2, as in Slice's MSHR interface.
    val l3_dirResultValid = RegNext(l3_dirResult.valid, false.B)

    val mshrs = if (useLarge) 16 else 4

    class MSHRSignal(setBits: Int) extends Bundle {
      val status_bits_set = UInt(setBits.W)
      val status_valid = Bool()
    }

    val L2_MSHR_signals = l2_slices.map { l2 =>
      (0 until mshrs).map { i =>
        val mshr_sig = Wire(new MSHRSignal(l2_setBits))
        mshr_sig.status_bits_set := boreOut(l2.mshrCtl.mshrs(i).io.status.bits.set)
        mshr_sig.status_valid := boreOut(l2.mshrCtl.mshrs(i).io.status.valid)
        mshr_sig
      }
    }

    val L3_MSHR_signals = l3.module.slices.head.ms.map { mshr =>
      val mshr_sig = Wire(new MSHRSignal(l3_setBits))
      mshr_sig.status_bits_set := boreOut(mshr.io.status.bits.set)
      mshr_sig.status_valid := boreOut(mshr.io.status.valid)
      mshr_sig
    }

    val l2_MSHR_prop_patch = coupledL2.indices.map { idx =>
      Cat(L2_MSHR_signals(idx).map { m =>
        m.status_bits_set === 0.U && m.status_valid
      }).orR
    }

    val l3_MSHR_prop_patch = Cat(L3_MSHR_signals.map { m =>
      m.status_bits_set === 0.U && m.status_valid
    }).orR

    val l2_reqarb_prop_patch_s1 = l2_slices.map { l2 =>
      val reqArbValid = boreOut(l2.reqArb.task_s1.valid)
      val reqArbSet = boreOut(l2.reqArb.task_s1.bits.set)
      reqArbValid && reqArbSet === 0.U
    }

    val l2_mainpipe_prop_patch_s2 = l2_slices.map { l2 =>
      val mainpipeS2Valid = boreOut(l2.mainPipe.task_s2.valid)
      val mainpipeS2Set = boreOut(l2.mainPipe.task_s2.bits.set)
      mainpipeS2Valid && mainpipeS2Set === 0.U
    }

    val l2_mainpipe_prop_patch_s3 = l2_slices.map { l2 =>
      val mainpipeS3Valid = boreOut(l2.mainPipe.task_s3.valid)
      val mainpipeS3Set = boreOut(l2.mainPipe.task_s3.bits.set)
      mainpipeS3Valid && mainpipeS3Set === 0.U
    }

    def l2_l2_mutual(addr: UInt, state0: UInt, state1: UInt): Unit = {
      val (l2_tag, l2_set, _) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
        case (tag, state) =>
          l2_tag === tag && state =/= MetaData.INVALID
      }
      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArrays(1)(l2_set).zip(l2_stateArrays(1)(l2_set)).map {
        case (tag, state) =>
          l2_tag === tag && state =/= MetaData.INVALID
      }
      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      assert(!(hit0 && l2_stateArrays(0)(l2_set)(way0) === state0 &&
        hit1 && l2_stateArrays(1)(l2_set)(way1) === state1))
    }

    def l2_l3_mutual(addr: UInt, l2_state: UInt, l3_state: UInt, l2_idx: Int = 0): Unit = {
      if (l2_state == MetaData.INVALID || l3_state == MetaData.INVALID) {
        l2_l3_mutual_with_invalid(addr, l2_state, l3_state, l2_idx)
        return
      }

      val (l2_tag, l2_set, _) = parseL2Address(addr)
      val (l3_tag, l3_set, _) = parseL3Address(addr)

      val l2_hit_vec = l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
        case (tag, state) => tag === l2_tag && state =/= MetaData.INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      val l3_lookup_hit = l3_dirResultValid &&
        l3_dirResult.bits.set === l3_set &&
        l3_dirResult.bits.self.tag === l3_tag &&
        l3_dirResult.bits.self.hit &&
        l3_dirResult.bits.self.state =/= MetaData.INVALID

      val mutualViolation =
        l2_hit && l2_stateArrays(l2_idx)(l2_set)(l2_way) === l2_state &&
          l3_lookup_hit && l3_dirResult.bits.self.state === l3_state
      val l2_l3_patch_raw =
        l3_MSHR_prop_patch ||
          l2_MSHR_prop_patch(l2_idx) ||
          l2_reqarb_prop_patch_s1(l2_idx) ||
          l2_mainpipe_prop_patch_s2(l2_idx) ||
          l2_mainpipe_prop_patch_s3(l2_idx)
      val l2_l3_patch_d1 = RegNext(l2_l3_patch_raw, false.B)
      val l2_l3_patch_d2 = RegNext(l2_l3_patch_d1, false.B)

      assert(
        !mutualViolation ||
          l2_l3_patch_raw ||
          l2_l3_patch_d1 ||
          l2_l3_patch_d2
      )
    }

    def l2_l3_mutual_with_invalid(addr: UInt, l2_state: UInt, l3_state: UInt, l2_idx: Int = 0): Unit = {
      val (l2_tag, l2_set, _) = parseL2Address(addr)
      val (l3_tag, l3_set, _) = parseL3Address(addr)

      val l2_hit = Mux(
        l2_state === MetaData.INVALID,
        !l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
          case (tag2, state2) => tag2 === l2_tag && state2 =/= MetaData.INVALID
        }.reduce(_ || _),
        l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
          case (tag2, state2) => tag2 === l2_tag && state2 === l2_state
        }.reduce(_ || _)
      )

      val l3_lookup_valid = l3_dirResultValid &&
        l3_dirResult.bits.set === l3_set &&
        l3_dirResult.bits.self.tag === l3_tag

      val l3_hit = Mux(
        l3_state === MetaData.INVALID,
        l3_lookup_valid && (!l3_dirResult.bits.self.hit || l3_dirResult.bits.self.state === MetaData.INVALID),
        l3_lookup_valid && l3_dirResult.bits.self.hit && l3_dirResult.bits.self.state === l3_state
      )
      val mutualViolation = l2_hit && l3_hit
      val l2_l3_patch_raw =
        l3_MSHR_prop_patch ||
          l2_MSHR_prop_patch(l2_idx) ||
          l2_reqarb_prop_patch_s1(l2_idx) ||
          l2_mainpipe_prop_patch_s2(l2_idx) ||
          l2_mainpipe_prop_patch_s3(l2_idx)
      val l2_l3_patch_d1 = RegNext(l2_l3_patch_raw, false.B)
      val l2_l3_patch_d2 = RegNext(l2_l3_patch_d1, false.B)

      assert(
        !mutualViolation ||
          l2_l3_patch_raw ||
          l2_l3_patch_d1 ||
          l2_l3_patch_d2
      )
    }

    val probeAddr = 0.U(io.topInputRandomAddrs.head.getWidth.W)

    def l2_l3_mutual_specs(): Unit = {
      // l2_l3_mutual(probeAddr, MetaData.TIP, MetaData.TIP, 0)
      // l2_l3_mutual(probeAddr, MetaData.TIP, MetaData.TIP, 1)
      l2_l3_mutual(probeAddr, MetaData.TRUNK, MetaData.TIP, 0)
      // l2_l3_mutual(probeAddr, MetaData.TRUNK, MetaData.TIP, 1)
      l2_l3_mutual(probeAddr, MetaData.BRANCH, MetaData.TRUNK, 0)
      // l2_l3_mutual(probeAddr, MetaData.BRANCH, MetaData.TRUNK, 1)
      // l2_l3_mutual(probeAddr, MetaData.INVALID, MetaData.TRUNK, 0)
      // l2_l3_mutual(probeAddr, MetaData.INVALID, MetaData.TRUNK, 1)
      l2_l3_mutual(probeAddr, MetaData.TRUNK, MetaData.BRANCH, 0)
      // l2_l3_mutual(probeAddr, MetaData.TRUNK, MetaData.BRANCH, 1)
    }

    def l2l2_mutual_specs(): Unit = {
      l2_l2_mutual(probeAddr, MetaData.TIP, MetaData.TIP)
      l2_l2_mutual(probeAddr, MetaData.TIP, MetaData.BRANCH)
    }

    l2l2_mutual_specs()
    l2_l3_mutual_specs()
  }
}

class VerifyTop_large()(implicit p: Parameters)
  extends VerifyTop_L2L3L2(useLarge = true)(p)
class VerifyTop_small()(implicit p: Parameters)
  extends VerifyTop_L2L3L2(useLarge = false)(p)

object VerifyTop_L2L3L2 extends App {

  println("object VerifyTop_L2L3L2:")
  val useLarge = VerifyMode.resolveUseLarge()
  println(s"VERIFY_MODE=${if (useLarge) "large" else "small"}")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      ways = 4,
      sets = 128,
      blockBytes = 64,
      channelBytes = TLChannelBeatBytes(32),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(
    if (useLarge) new VerifyTop_large()(p) else new VerifyTop_small()(p)
  ))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3L2")
  )
}
