package coupledL2

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import huancun._
import messageGenarator.{CoupledL2 => L1}
import utility.{ChiselDB, FileRegisters, TLLogger}

import scala.collection.mutable.ArrayBuffer
import chisel3.util.experimental.BoringUtils
import chiselFv._
import messageGenarator.prefetch.messageGenaratorPrefParam

class VerifyTop()(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  println("class VerifyTop:")

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

  val l1 = (0 until nrL2).map(i => LazyModule(new L1()(new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2, // ways = 4,
      sets = 2, // sets = 128,
      blockBytes = 2,
      mshrs = 4,
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      hartIds = Seq{i},
      prefetch = Option(messageGenaratorPrefParam())
    )
  }))))
  val l1_nodes = l1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new CoupledL2()(new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2, // ways = 4,
      sets = 4, // sets = 128,
      blockBytes = 2,
      mshrs = 4,
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
      ways = 2, // ways = 4,
      sets = 4, // sets = 128,
      blockBytes = 2,
      mshrs = 6,
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 2, // sets = 128,
          ways = 2 + 2, // ways = 4 + 2,
          blockGranularity = log2Ceil(2) // blockGranularity = log2Ceil(128) 
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = 1)) // val ram = LazyModule(new TLRAM(AddressSet(0, 0xffffL), beatBytes = 32))

  l0_nodes.zip(l1_nodes).zipWithIndex map {
    case ((l0, l1), i) => l1 := l0
  }

  l1_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}", true) := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case(l2, i) => xbar := TLLogger(s"L3_L2_${i}", true) := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=* // TLFragmenter(32, 64) :=*
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

    l1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)

    // nothing happened
    l1_nodes.foreach { node =>
      val (l1_in, _) = node.in.head
      dontTouch(l1_in)  // l1_in := DontCare
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputValids = Input(Vec(nrL2, Bool()))
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(3.W)))
    })

    l1.zipWithIndex.foreach { case(l1, i) =>
      l1.module.io.prefetcherInputValid := io.topInputValids(i)
      l1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      dontTouch(l1.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.bore(coupledL2(0).module.slices.head.directory.resetFinish, Seq(dir_resetFinish))
    assume(verify_timer < 100.U || dir_resetFinish)

    val l2_offsetBits = 1
    val l2_bankBits = 0
    val l2_setBits = 2
    val l2_tagBits = 2

    coupledL2.foreach { l2 =>
      l2.module.slices.head.mshrCtl.mshrs.zipWithIndex.foreach {
        case (mshr, i) =>
          if(i < 3) {
            val MSHRStatus = WireDefault(false.B)
            BoringUtils.bore(mshr.io.status.valid, Seq(MSHRStatus))
            astLiveness(MSHRStatus, !MSHRStatus, 300)
            astLiveness(MSHRStatus, !MSHRStatus, 500)
            astLiveness(MSHRStatus, !MSHRStatus, 1000)
          }
      }


    }

    val l2_stateArray_0 = WireDefault(VecInit.fill(4, 2)(0.U(2.W)))
    val l2_stateArray_1 = WireDefault(VecInit.fill(4, 2)(0.U(2.W)))
    val l2_tagArray_0 = WireDefault(VecInit.fill(4, 2)(0.U(2.W)))
    val l2_tagArray_1 = WireDefault(VecInit.fill(4, 2)(0.U(2.W)))
    BoringUtils.bore(coupledL2(0).module.slices.head.directoryTest.stateArray, Seq(l2_stateArray_0))
    BoringUtils.bore(coupledL2(1).module.slices.head.directoryTest.stateArray, Seq(l2_stateArray_1))
    BoringUtils.bore(coupledL2(0).module.slices.head.directoryTest.tagArray, Seq(l2_tagArray_0))
    BoringUtils.bore(coupledL2(1).module.slices.head.directoryTest.tagArray, Seq(l2_tagArray_1))

    val set = 0.U(l2_setBits.W)
    val tag = 0.U(l2_tagBits.W)
    val l2_hit_vec_0 = l2_tagArray_0(set).zip(l2_stateArray_0(set)).map {
      case (l2_tag, l2_state) =>
        tag === l2_tag && l2_state =/= MetaData.INVALID
    }

    val hit0 = l2_hit_vec_0.reduce(_ || _)
    val way0 = OHToUInt(l2_hit_vec_0)

    val l2_hit_vec_1 = l2_tagArray_1(set).zip(l2_stateArray_1(set)).map {
      case (l2_tag, l2_state) =>
        tag === l2_tag && l2_state =/= MetaData.INVALID
    }

    val hit1 = l2_hit_vec_1.reduce(_ || _)
    val way1 = OHToUInt(l2_hit_vec_1)

    assert(!(hit0 && l2_stateArray_0(set)(way0) === MetaData.TIP &&
      hit1 && l2_stateArray_1(set)(way1) === MetaData.TIP))
    assert(!(hit0 && l2_stateArray_0(set)(way0) === MetaData.TIP &&
      hit1 && l2_stateArray_1(set)(way1) === MetaData.BRANCH))
  }
}

object VerifyTop extends App {

  println("object VerifyTop_L2L3L2:")

  val config = new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
     // echoField = Seq(DirtyField())
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop()(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "VerilogCodes/L2L3L2")
  )

}