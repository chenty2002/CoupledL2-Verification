package coupledL2

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2.tl2chi._
import coupledL2.tl2tl.TL2TLCoupledL2
import coupledL2.tl2tl.{Slice => TLSlice}
import coupledL2AsL1.prefetch.CoupledL2AsL1PrefParam
import coupledL2AsL1.tl2tl.{TL2TLCoupledL2 => TLCoupledL2AsL1}
import coupledL2AsL1.tl2chi.{TL2CHICoupledL2 => CHICoupledL2AsL1}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import messageGenerator.{MessageGeneratorParam, TLMessageGenerator}
import org.chipsalliance.cde.config._
import utility._
import dataclass.data
import scala.sys


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


class VerifyTop_L2L3L2(useMessageGenerator: Boolean = false)(implicit p: Parameters) extends LazyModule {

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

  val l0_nodes = (0 until nrL2).map(i => createClientNode(s"l0$i", 32))

  val messageGenerators: Seq[TLMessageGenerator] = if (useMessageGenerator) {
    (0 until nrL2).map(i => LazyModule(new TLMessageGenerator(
      MessageGeneratorParam(
        name = s"msgGen$i",
        sets = 2,
        ways = 2,
        blockBytes = 2,
        channelBytes = TLChannelBeatBytes(1),
        sourceIdRange = IdRange(0, 4),
        reqField = Seq(AliasField(2)),
        respKey = cacheParams.respKey,
        supportsProbe = Some(TransferSizes(cacheParams.blockBytes)),
        minLatency = 1
      )
    )))
  } else {
    Seq.empty
  }

  val coupledL2AsL1: Seq[TLCoupledL2AsL1] = if (useMessageGenerator) {
    Seq.empty
  } else {
    (0 until nrL2).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
      case L2ParamKey => L2Param(
        name = s"l1$i",
        ways = 2,
        sets = 2,
        blockBytes = 2,
        channelBytes = TLChannelBeatBytes(1),
        clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
        echoField = Seq(),
        prefetch = Seq(CoupledL2AsL1PrefParam()),
        mshrs = 4,
        hartId = i
      )
      case BankBitsKey => 0
    }))))
  }
  val l1_nodes = coupledL2AsL1.map(_.node)
  val req_nodes = if (useMessageGenerator) messageGenerators.map(_.node) else l1_nodes

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(4) // 2024.11.29: log2Ceil(2), should be log2Ceil(#l2_sets)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = 6
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = (if (scala.sys.env.get("VERIFY_MODE").exists(_.trim.equalsIgnoreCase("large"))) 32 else 1)))

  if (!useMessageGenerator) {
    l0_nodes.zip(l1_nodes).zipWithIndex map {
      case ((l0, l1), i) => l1 := l0
    }
  }

  req_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
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

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)


    coupledL2AsL1.foreach(_.module.io.l2_tlb_req <> DontCare)
    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2AsL1.foreach(_.module.io.hartId <> DontCare)
    coupledL2.foreach(_.module.io.hartId <> DontCare)

    if (!useMessageGenerator) {
      l1_nodes.foreach { node =>
        val (l1_in, _) = node.in.head
        dontTouch(l1_in)
      }
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputValids = Input(Vec(nrL2, Bool()))
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(5.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
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
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    // Deadlock version keeps only MSHRCtl assertions.
    // VerifyTop_L2L3L2 intentionally has no extra mutual/inclusive/consistency assertions.
  }
}

object VerifyTop_L2L3L2 extends App {

  println("object VerifyTop_L2L3L2:")
  val useMessageGenerator = VerifyInputMode.resolveUseMessageGenerator()
  println(s"VERIFY_INPUT_MODE=${if (useMessageGenerator) "message_generator" else "coupledl2asl1"}")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3L2(useMessageGenerator)(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3L2")
  )
}

class VerifyTop_L2L3()(implicit p: Parameters) extends LazyModule {

  /* L1D  
   *  |   
   * L2
   *  |
   * L3
   */

  println("class VerifyTop_L2L3:")

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL1 = 1
  val nrL2 = 1

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

  val l0_nodes = (0 until nrL1).map(i => createClientNode(s"l0$i", 32))

  val coupledL2AsL1 = (0 until nrL1).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l1$i",
      ways = 2,
      sets = 2,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(),
      prefetch = Seq(CoupledL2AsL1PrefParam()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l1_nodes = coupledL2AsL1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(2)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = 6
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = (if (scala.sys.env.get("VERIFY_MODE").exists(_.trim.equalsIgnoreCase("large"))) 32 else 1)))

  l0_nodes.zip(l1_nodes).zipWithIndex map {
    case ((l0, l1), i) => l1 := l0
  }

  l1_nodes.zipWithIndex map {
    case (l1d, i) => l2_nodes(0) := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
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

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)


    coupledL2AsL1.foreach(_.module.io.l2_tlb_req <> DontCare)
    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2AsL1.foreach(_.module.io.hartId <> DontCare)
    coupledL2.foreach(_.module.io.hartId <> DontCare)

    l1_nodes.foreach { node =>
      val (l1_in, _) = node.in.head
      dontTouch(l1_in)
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(5.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
      dontTouch(l2AsL1.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val stateArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))
    val tagArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))

    for (i <- 0 until nrL2) {
      BoringUtils.addSink(stateArray(i), s"stateArray_${i}")
      BoringUtils.addSink(tagArray(i), s"tagArray_${i}")
    }
  }
}

object VerifyTop_L2L3 extends App {

  println("object VerifyTop_L2L3:")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3()(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3")
  )
}
