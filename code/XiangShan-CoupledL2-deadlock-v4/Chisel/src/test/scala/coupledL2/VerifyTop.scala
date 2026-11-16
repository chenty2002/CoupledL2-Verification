package coupledL2

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2.tl2chi._
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

object VerifyMode {
  def resolveUseLarge(): Boolean = {
    scala.sys.env.get("VERIFY_MODE").map(_.trim.toLowerCase) match {
      case Some("large") => true
      case None | Some("small") => false
      case Some(other) =>
        throw new IllegalArgumentException(
          s"Unsupported VERIFY_MODE=$other, expected 'small' or 'large'"
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
          blockGranularity = log2Ceil(if (useLarge) 128 else 4),
          blockBytes = if (useLarge) 64 else 2
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

    // Deadlock version keeps only MSHRCtl assertions.
    // VerifyTop_L2L3L2 intentionally has no extra mutual/inclusive/consistency assertions.
  }
}

object VerifyTop_L2L3L2 extends App {

  println("object VerifyTop_L2L3L2:")
  val useLarge = VerifyMode.resolveUseLarge()
  println(s"VERIFY_MODE=${if (useLarge) "large" else "small"}")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3L2(useLarge)(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3L2")
  )
}
