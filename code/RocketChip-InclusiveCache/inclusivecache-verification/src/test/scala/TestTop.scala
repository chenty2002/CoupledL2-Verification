package verifyl2

import chisel3._
import org.chipsalliance.cde.config._
import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import sifive.blocks.inclusivecache._
import freechips.rocketchip.subsystem.InclusiveCacheParams
import messageGenerator._
import chisel3.util.experimental.BoringUtils
import chiselFv._

/** Minimal parameters for InclusiveCache instantiation */
case object MiniL2Key extends Field[InclusiveCacheParams]

class MiniL2Config extends Config((site, here, up) => {
  case MiniL2Key => InclusiveCacheParams(
    ways = 4,
    sets = 8,           // 64 sets * 4 ways * 64B = 16 KiB (per bank)
    writeBytes = 8,
    portFactor = 4,
    memCycles = 40
  )
})

/** TestTop: 2 TL client ports ("L1" stand-ins) -> InclusiveCache (L2) -> TLRAM */
class TestTop(implicit p: Parameters) extends LazyModule {
  val l2p = p(MiniL2Key)

  val blockBytes = 64
  val numMsgGens = 2

  private val msgGenParams = Seq.tabulate(numMsgGens) { i =>
    MessageGeneratorParam(
      blockBytes = blockBytes,
      sourceIdRange = IdRange(i, i + 1),
      supportsProbe = Some(TransferSizes(blockBytes))
    )
  }

  val msgGenerators = msgGenParams.map(params => LazyModule(new TLMessageGenerator(params)))

  val l2 = LazyModule(new InclusiveCache(
    CacheParameters(
      level = 2,
      ways = l2p.ways,
      sets = l2p.sets,
      blockBytes = blockBytes,
      beatBytes = blockBytes,
      hintsSkipProbe = l2p.hintsSkipProbe
    ), 
    InclusiveCacheMicroParameters(
      writeBytes = l2p.writeBytes,
      portFactor = l2p.portFactor,
      memCycles = l2p.memCycles,
      innerBuf = l2p.bufInnerInterior,
      outerBuf = l2p.bufOuterInterior
    )))

  val xbar = TLXbar()
  msgGenerators.foreach { gen =>
    xbar := TLBuffer() := gen.node
  }

  val ram = LazyModule(new TLRAM(AddressSet(0, 0xfffffL), beatBytes = blockBytes))

  ram.node := TLFragmenter(blockBytes, blockBytes) := TLCacheCork() := l2.node := xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val addrWidth = msgGenerators.head.module.io.in_addr.getWidth

    val io = IO(new Bundle {
      val msg = Vec(msgGenerators.size, new Bundle {
        val addr      = Input(UInt(addrWidth.W))
        val isAcquire = Input(Bool())
        val param     = Input(Bool())
        val data      = Input(UInt((blockBytes*8).W))
      })
    })

    msgGenerators.zipWithIndex.foreach { case (gen, idx) =>
      gen.module.io.in_addr      := io.msg(idx).addr
      gen.module.io.in_isAcquire := io.msg(idx).isAcquire
      gen.module.io.in_param     := io.msg(idx).param
      gen.module.io.io_in_data   := io.msg(idx).data
    }

    // Deadlock Freeness property
    l2.module.mods.foreach { case sched =>
      sched.mshrs.foreach { case mshr =>
        val request_valid = BoringUtils.bore(mshr.request_valid)
        val allocate_valid = BoringUtils.bore(mshr.io.allocate.valid)
        astRelaxedLiveness(request_valid, allocate_valid || !request_valid, 1000)
      }
    }
  }
}

object TestTop extends App {
  val cfg = new MiniL2Config
  val top = DisableMonitors(p => LazyModule(new TestTop()(p)))(cfg)
  (new ChiselStage).execute(args, Seq(ChiselGeneratorAnnotation(() => top.module)))
}
