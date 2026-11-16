package coupledL2Verification

import chisel3._
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2._
import coupledL2.tl2tl.{Slice => L2Slice, _}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink.TLMessages.{GrantData, ProbeAckData, ReleaseData}
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

object baseConfig {
  def apply(maxHartIdBits: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
    })
  }
}

class VerifyTop(useLarge: Boolean = false)(implicit p: Parameters) extends LazyModule {

  /* MG0   MG1
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

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
  })))

  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, if (useLarge) 0xffffffL else 0x1fL), beatBytes = if (useLarge) 32 else 1))

  req_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := 
        TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
        TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case(l2, i) => xbar := 
      TLLogger(s"L3_L2[${i}]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
      TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(if (useLarge) 32 else 1, if (useLarge) 64 else 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {

    coupledL2.foreach {
      l2 => {
        l2.module.io.debugTopDown <> DontCare
        l2.module.io.hartId := DontCare
        l2.module.io.l2_tlb_req <> DontCare
      }
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U

//    assert(verify_timer < 1000.U)

    val io = IO(Vec(nrL2, new Bundle() {
      // Input signals for formal verification
      val inputValid = Input(Bool())
      val msggenRelease = Input(Bool())
      val msggenStore = Input(Bool())
      val msggenData = Input(UInt((if (useLarge) 512 else 16).W))
      val inputAddr = Input(UInt(ram.node.in.head._2.bundle.addressBits.W))
      val inputNeedT = Input(Bool())
    }))

    messageGenerators.zipWithIndex.foreach {
      case (msgGen, i) =>
        msgGen.module.io.in_valid := true.B
        msgGen.module.io.in_addr := io(i).inputAddr
        msgGen.module.io.in_isAcquire := !io(i).msggenRelease
        msgGen.module.io.in_store := io(i).msggenStore
        msgGen.module.io.in_data := io(i).msggenData
        msgGen.module.io.in_param := io(i).inputNeedT
        dontTouch(msgGen.module.io)
    }

    coupledL2(0).module.slices.head match {
      case tlSlice: L2Slice =>
        val dir_resetFinish = BoringUtils.bore(tlSlice.directory.resetFinish)
        assume(verify_timer < 100.U || dir_resetFinish)
    }

    // Keep only write_read consistency assertions in this version.

    val observed = coupledL2.head.module.slices.head.asInstanceOf[L2Slice].io.in
    val dataChecker = Module(new WatchedBlockDataChecker(
      nrL2, observed.a.bits.source.getWidth, observed.a.bits.address.getWidth,
      observed.d.bits.data.getWidth, if (useLarge) 64 else 2))

    coupledL2.zipWithIndex.foreach { case (l2, i) =>
      val tl = l2.module.slices.head.asInstanceOf[L2Slice].io.in
      val ch = dataChecker.io.channels(i)
      ch.a_valid := BoringUtils.bore(tl.a.valid)
      ch.a_ready := BoringUtils.bore(tl.a.ready)
      ch.a_opcode := BoringUtils.bore(tl.a.bits.opcode)
      ch.a_source := BoringUtils.bore(tl.a.bits.source)
      ch.a_address := BoringUtils.bore(tl.a.bits.address)
      ch.c_valid := BoringUtils.bore(tl.c.valid)
      ch.c_ready := BoringUtils.bore(tl.c.ready)
      ch.c_opcode := BoringUtils.bore(tl.c.bits.opcode)
      ch.c_param := BoringUtils.bore(tl.c.bits.param)
      ch.c_address := BoringUtils.bore(tl.c.bits.address)
      ch.c_data := BoringUtils.bore(tl.c.bits.data)
      ch.d_valid := BoringUtils.bore(tl.d.valid)
      ch.d_ready := BoringUtils.bore(tl.d.ready)
      ch.d_opcode := BoringUtils.bore(tl.d.bits.opcode)
      ch.d_source := BoringUtils.bore(tl.d.bits.source)
      ch.d_data := BoringUtils.bore(tl.d.bits.data)
      fvAssert(!dataChecker.io.violation(i))
    }
  }
}

class VerifyTop_large()(implicit p: Parameters)
  extends VerifyTop(useLarge = true)(p)
class VerifyTop_small()(implicit p: Parameters)
  extends VerifyTop(useLarge = false)(p)

object VerifyTop extends App {
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