package coupledL2Verification

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2._
import coupledL2.tl2tl.{Slice => L2Slice, _}
import coupledL2AsL1._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import huancun.MetaData._
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}
import org.chipsalliance.cde.config._
import utility._

import java.io.File
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

object VerifyInputMode {
  private val envName = "VERIFY_INPUT_MODE"

  def resolveUseMessageGenerator(): Boolean = {
    sys.env.get(envName).map(_.trim.toLowerCase) match {
      case Some("coupledl2asl1") | Some("legacy") => false
      case Some("message_generator") | Some("messagegenerator") | Some("msggen") => true
      case Some(other) =>
        throw new IllegalArgumentException(
          s"Unsupported $envName=$other, expected 'coupledl2asl1' or 'message_generator'"
        )
      case None => false
    }
  }
}


object baseConfig {
  def apply(maxHartIdBits: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
    })
  }
}

class VerifyTop(useLarge: Boolean = false, useMessageGenerator: Boolean = false)(implicit p: Parameters) extends LazyModule {

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

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }
  val l0_client_nodes = (0 until nrL2).map(i => createClientNode(s"L0_$i", 32))

  val messageGenerators: Seq[TLMessageGenerator] = if (useMessageGenerator) {
    (0 until nrL2).map(i => LazyModule(new TLMessageGenerator(
      MessageGeneratorParam(
        name = s"msgGen$i",
        sets = if (useLarge) 64 else 2,
        ways = if (useLarge) 8 else 2,
        blockBytes = if (useLarge) 64 else 2,
        channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
        sourceIdRange = IdRange(0, if (useLarge) 16 else 4),
        reqField = Seq(AliasField(2)),
        respKey = cacheParams.respKey,
        supportsProbe = Some(TransferSizes(if (useLarge) 64 else 2)),
        minLatency = 1
      )
    )))
  } else {
    Seq.empty
  }

  val l0_nodes = l0_client_nodes

  val coupledL2AsL1: Seq[TLCoupledL2AsL1] = if (useMessageGenerator) {
    Seq.empty
  } else {
    (0 until nrL2).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alter((_, here, _) => {
      case L2ParamKey => L2Param(
        name = s"L1d_$i",
        ways = if (useLarge) 8 else 2,
        sets = if (useLarge) 64 else 2,
        blockBytes = if (useLarge) 64 else 2,
        channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
        mshrs = if (useLarge) 16 else 4,
        clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
        hartId = i,
        prefetch = Seq(InputAsPrefectchParam())
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
    })))
    )
  }
  val l1d_nodes = coupledL2AsL1.map(_.node)
  val req_nodes = if (useMessageGenerator) messageGenerators.map(_.node) else l1d_nodes

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alter((_, here, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = if (useLarge) 4 else 2,
      sets = if (useLarge) 128 else 4,
      blockBytes = if (useLarge) 64 else 2,
      channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
      mshrs = if (useLarge) 16 else 4,
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
      ways = if (useLarge) 4 else 2,
      sets = if (useLarge) 128 else 4,
      blockBytes = if (useLarge) 64 else 2,
      channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
      mshrs = if (useLarge) 14 else 6,
      inclusive = false,
      clientCaches = (0 until nrL2).map(_ =>
        CacheParameters(
          name = s"l2",
          sets = if (useLarge) 128 else 4,
          ways = if (useLarge) 4 + 2 else 2 + 2,
          blockGranularity = log2Ceil(if (useLarge) 128 else 4)
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
  val ram = LazyModule(new TLRAM(AddressSet(0, if (useLarge) 0xffffffL else 0x1fL), beatBytes = if (useLarge) 32 else 1))

  if (!useMessageGenerator) {
    l0_nodes.zip(l1d_nodes) map {
      case (l0, l1d) => l1d := l0
    }
  }

  req_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 :=
      TLLogger(s"L2_L1[${i}].C[0]") :=
        TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar :=
      TLLogger(s"L3_L2[${i}]") :=
        TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(if (useLarge) 32 else 1, if (useLarge) 64 else 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3") :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    coupledL2AsL1.foreach {
      l1 => {
        l1.module.io.debugTopDown <> DontCare
        l1.module.io.hartId := DontCare
        l1.module.io.pfCtrlFromCore := DontCare
        l1.module.io.l2_tlb_req <> DontCare
      }
    }

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
      val inputValid = Input(Bool())
      val inputAddr = Input(UInt(ram.node.in.head._2.bundle.addressBits.W))
      val inputNeedT = Input(Bool())
      val inputRequestType = Input(Bool()) // 0 Acquire, 1 Release
    }))

    coupledL2AsL1.zipWithIndex.foreach {
      case (node, i) =>
        if (useMessageGenerator) {
          node.module.io_inputAddr := 0.U
          node.module.io_inputNeedT := false.B
          node.module.io_requestType := false.B
        } else {
          node.module.io_inputAddr := io(i).inputAddr
          node.module.io_inputNeedT := io(i).inputNeedT
          node.module.io_requestType := io(i).inputRequestType
        }
    }

    if (useMessageGenerator) {
      messageGenerators.zipWithIndex.foreach {
        case (msgGen, i) =>
          msgGen.module.io.in_valid := io(i).inputValid
          msgGen.module.io.in_addr := io(i).inputAddr
          msgGen.module.io.in_isAcquire := true.B
          msgGen.module.io.in_param := io(i).inputNeedT
          dontTouch(msgGen.module.io)
      }

      for (i <- 0 until nrL2) {
        assume(io(i).inputValid)
        assume(io(i).inputAddr === 0.U)
      }
    }

    coupledL2(0).module.slices.head match {
      case tlSlice: L2Slice =>
        val dir_resetFinish = BoringUtils.bore(tlSlice.directory.resetFinish)
        assume(verify_timer < 100.U || dir_resetFinish)
    }

    // Keep only copy_equality consistency assertions in this version.
    val l2_offsetBits = if (useLarge) 6 else 1
    val l2_bankBits = 0
    val l2_setBits = if (useLarge) 7 else 2
    val l2_tagBits = if (useLarge) 11 else 2

    def parseL2Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l2_offsetBits + l2_bankBits)
      val tag = set >> l2_setBits
      (tag(l2_tagBits - 1, 0), set(l2_setBits - 1, 0), offset(l2_offsetBits - 1, 0))
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

    val mshrs = if (useLarge) 16 else 4

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

    val l2_MSHR_prop_patch = coupledL2.indices.map { indx =>
      Cat(L2_MSHR_signals(indx).map { m =>
        m.status_bits_set === 0.U &&
          m.status_valid === true.B
      }).orR
    }

    def l2_consistency(addr: UInt): Unit = {
      val (tag, set, _) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= _root_.coupledL2.MetaData.INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= _root_.coupledL2.MetaData.INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      val arrayIdx0 = Cat(way0, set)
      val arrayIdx1 = Cat(way1, set)

      val valid_state =
        hit0 && l2_stateArrays(0)(set)(way0) === _root_.coupledL2.MetaData.BRANCH &&
        hit1 && l2_stateArrays(1)(set)(way1) === _root_.coupledL2.MetaData.BRANCH

      fvAssert(!valid_state ||
        l2_dataArrays(0)(arrayIdx0) === l2_dataArrays(1)(arrayIdx1) ||
        l2_MSHR_prop_patch(0) ||
        l2_MSHR_prop_patch(1))
    }

    def consistency_spec(): Unit = {
      l2_consistency(0.U(io(0).inputAddr.getWidth.W))
    }

    consistency_spec()
  }
}

class VerifyTop_large(useMessageGenerator: Boolean = false)(implicit p: Parameters)
  extends VerifyTop(useLarge = true, useMessageGenerator = useMessageGenerator)(p)
class VerifyTop_small(useMessageGenerator: Boolean = false)(implicit p: Parameters)
  extends VerifyTop(useLarge = false, useMessageGenerator = useMessageGenerator)(p)

object VerifyTop extends App {
  val useLarge = VerifyMode.resolveUseLarge()
  val useMessageGenerator = VerifyInputMode.resolveUseMessageGenerator()
  println(s"VERIFY_MODE=${if (useLarge) "large" else "small"}")
  println(s"VERIFY_INPUT_MODE=${if (useMessageGenerator) "message_generator" else "coupledl2asl1"}")
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
    if (useLarge) new VerifyTop_large(useMessageGenerator)(p) else new VerifyTop_small(useMessageGenerator)(p)
  ))(config)
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