package coupledL2

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2AsL1.prefetch.CoupledL2AsL1PrefParam
import coupledL2AsL1.{CoupledL2 => CoupledL2AsL1}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import huancun._
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}
import org.chipsalliance.cde.config._
import utility.TLLogger
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

class VerifyTop_L2L3L2(useLarge: Boolean = false, useMessageGenerator: Boolean = false)(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
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

  val l0_client_nodes = (0 until nrL2).map(i => createClientNode(s"l0$i", 32))

  val messageGenerators: Seq[TLMessageGenerator] = if (useMessageGenerator) {
    (0 until nrL2).map(i => LazyModule(new TLMessageGenerator(
      MessageGeneratorParam(
        name = s"msgGen$i",
        sets = if (useLarge) 64 else 2,
        ways = if (useLarge) 4 else 2,
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

  val coupledL2AsL1: Seq[CoupledL2AsL1] = if (useMessageGenerator) {
    Seq.empty
  } else {
    (0 until nrL2).map(i => LazyModule(new CoupledL2AsL1()(new Config((_, _, _) => {
      case L2ParamKey => L2Param(
        name = s"l1$i",
        ways = if (useLarge) 4 else 2,
        sets = if (useLarge) 64 else 2,
        blockBytes = if (useLarge) 64 else 2,
        channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
        clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
        echoField = Seq(DirtyField()),
        hartIds = Seq {i},
        prefetch = Some(CoupledL2AsL1PrefParam()),
        mshrs = if (useLarge) 16 else 4
      )
    }))))
  }
  val l1_nodes = coupledL2AsL1.map(_.node)
  val req_nodes = if (useMessageGenerator) messageGenerators.map(_.node) else l1_nodes

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new CoupledL2()(new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = if (useLarge) 4 else 2,
      sets = if (useLarge) 128 else 4,
      blockBytes = if (useLarge) 64 else 2,
      channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      hartIds = Seq {i},
      mshrs = if (useLarge) 16 else 4
    )
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(new Config((_, _, _) => {
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
  val ram = LazyModule(new TLRAM(AddressSet(0, if (useLarge) 0xffffffL else 0x1fL), beatBytes = if (useLarge) 32 else 1))

  if (!useMessageGenerator) {
    l0_nodes.zip(l1_nodes).zipWithIndex map {
      case ((l0, l1), i) => l1 := l0
    }
  }

  req_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}", true) := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}", true) := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(if (useLarge) 32 else 1, if (useLarge) 64 else 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3", true) :=*
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

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)

    // nothing happened
    if (!useMessageGenerator) {
      l1_nodes.foreach { node =>
        val (l1_in, _) = node.in.head
        dontTouch(l1_in) // l1_in := DontCare
      }
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputValids = Input(Vec(nrL2, Bool()))
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(24.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      if (useMessageGenerator) {
        l2AsL1.module.io.prefetcherInputRandomAddr := 0.U
        l2AsL1.module.io.prefetcherNeedT := false.B
      } else {
        l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
        l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
      }
      dontTouch(l2AsL1.module.io)
    }

    if (useMessageGenerator) {
      messageGenerators.zipWithIndex.foreach { case (msgGen, i) =>
        msgGen.module.io.in_valid := io.topInputValids(i)
        msgGen.module.io.in_addr := io.topInputRandomAddrs(i)
        msgGen.module.io.in_isAcquire := true.B
        msgGen.module.io.in_param := io.topInputNeedT(i)
        dontTouch(msgGen.module.io)
      }

      for (i <- 0 until nrL2) {
        assume(io.topInputValids(i) || (io.topInputRandomAddrs(i) === 0.U && io.topInputNeedT(i) === false.B))
      }
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    boreOut(coupledL2(0).module.slices(0).directory.resetFinish, Seq(dir_resetFinish))
    assume(verify_timer < 100.U || dir_resetFinish)

    val l1_offsetBits = if (useLarge) 6 else 1
    val l1_setBits = if (useLarge) 6 else 1

    def parseL1Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> l1_offsetBits
      val tag = set >> l1_setBits
      (tag, set(l1_setBits - 1, 0), offset(l1_offsetBits - 1, 0))
    }

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

    def boreOut(source: Data, sinks: Seq[Data]): String = {
      BoringUtils.bore(source, sinks)
    }

    // Deadlock versions keep only MSHRCtl assertions.
    // VerifyTop intentionally contains no extra mutual/inclusive/consistency assertions.
  }
}

class VerifyTop_large(useMessageGenerator: Boolean = false)(implicit p: Parameters)
  extends VerifyTop_L2L3L2(useLarge = true, useMessageGenerator = useMessageGenerator)(p)
class VerifyTop_small(useMessageGenerator: Boolean = false)(implicit p: Parameters)
  extends VerifyTop_L2L3L2(useLarge = false, useMessageGenerator = useMessageGenerator)(p)

object VerifyTop_L2L3L2 extends App {

  println("object VerifyTop_L2L3L2:")
  val useLarge = VerifyMode.resolveUseLarge()
  val useMessageGenerator = VerifyInputMode.resolveUseMessageGenerator()
  println(s"VERIFY_MODE=${if (useLarge) "large" else "small"}")
  println(s"VERIFY_INPUT_MODE=${if (useMessageGenerator) "message_generator" else "coupledl2asl1"}")

  val config = new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
     // echoField = Seq(DirtyField())
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(
    if (useLarge) new VerifyTop_large(useMessageGenerator)(p) else new VerifyTop_small(useMessageGenerator)(p)
  ))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "VerilogCodes/L2L3L2")
  )
}