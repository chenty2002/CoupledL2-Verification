package coupledL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import huancun._
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}
import utility.{ChiselDB, FileRegisters, TLLogger}

import scala.collection.mutable.ArrayBuffer
import scala.sys
import chisel3.util.experimental.BoringUtils
import chiselFv._

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
        ways = if (useLarge) 8 else 2,
        blockBytes = if (useLarge) 64 else 2,
        channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
        sourceIdRange = IdRange(0, if (useLarge) 16 else 4),
        ordinaryEntries = if (useLarge) 15 else 3,
        reqField = Seq(AliasField(2)),
        respKey = cacheParams.respKey,
        supportsProbe = Some(TransferSizes(if (useLarge) 64 else 2)),
        minLatency = 1
      )
    )))

  val req_nodes = messageGenerators.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new CoupledL2()(new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = if (useLarge) 4 else 2,
      sets = if (useLarge) 128 else 4,
      blockBytes = if (useLarge) 64 else 2,
      channelBytes = TLChannelBeatBytes(if (useLarge) 32 else 1),
      mshrs = if (useLarge) 16 else 4,
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      hartIds = Seq{i}
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
      mshrs = if (useLarge) 14 else 6,
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
      simulation = true
    )
  })))

  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, if (useLarge) 0xffffffL else 0x1fL), beatBytes = if (useLarge) 32 else 1))

  req_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}", true) := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case(l2, i) => xbar := TLLogger(s"L3_L2_${i}", true) := TLBuffer() := l2
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

    coupledL2.foreach(_.module.io.debugTopDown := DontCare)

    // nothing happened

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputValids = Input(Vec(nrL2, Bool()))
      val msggenRelease = Input(Vec(nrL2, Bool()))
      val msggenStore = Input(Vec(nrL2, Bool()))
      val msggenData = Input(Vec(nrL2, UInt((if (useLarge) 512 else 16).W)))
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(ram.node.in.head._2.bundle.addressBits.W)))
    })

    messageGenerators.zipWithIndex.foreach { case (msgGen, i) =>
      msgGen.module.io.in_valid := true.B
      val requestAddr = RegInit(1.U(msgGen.module.io.in_addr.getWidth.W))
      when(io.topInputValids(i)) { requestAddr := io.topInputRandomAddrs(i) }
      msgGen.module.io.in_addr := requestAddr
      msgGen.module.io.in_isAcquire := !io.msggenRelease(i)
      msgGen.module.io.in_store := io.msggenStore(i)
      msgGen.module.io.in_data := io.msggenData(i)
      msgGen.module.io.in_param := false.B
      dontTouch(msgGen.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    boreOut(coupledL2(0).module.slices(0).directory.resetFinish, Seq(dir_resetFinish))
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

    def boreOut(source: Data, sinks: Seq[Data]): String = {
      BoringUtils.bore(source, sinks)
    }

    // Deadlock versions keep only MSHRCtl assertions.
    // VerifyTop intentionally contains no extra mutual/inclusive/consistency assertions.
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
    if (useLarge) new VerifyTop_large()(p) else new VerifyTop_small()(p)
  ))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "VerilogCodes/L2L3L2")
  )

}