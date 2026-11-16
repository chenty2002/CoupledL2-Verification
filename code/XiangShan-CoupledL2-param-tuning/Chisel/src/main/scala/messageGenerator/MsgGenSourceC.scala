package messageGenerator

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._


class MsgGenSourceCReq(edge: TLEdgeOut, blockBytes: Int) extends Bundle {
  val source = UInt(edge.bundle.sourceBits.W)
  val opcode  = UInt(3.W)  // ProbeAck/ProbeAckData/Release/ReleaseData
  val param   = UInt(3.W)
  val address = UInt(edge.bundle.addressBits.W)
  val data    = UInt((blockBytes * 8).W)
}

class MsgGenSourceC(edge: TLEdgeOut, blockBytes: Int, beatBytes: Int, entries: Int = 4) extends Module {
  private val lineBeats     = (blockBytes / beatBytes) max 1
  private val beatBits      = beatBytes * 8
  private val beatCountBits = scala.math.max(log2Ceil(lineBeats), 1)

  val io = IO(new Bundle {
    val req    = Flipped(Decoupled(new MsgGenSourceCReq(edge, blockBytes)))
    val tlc    = Decoupled(new TLBundleC(edge.bundle))
  })

  // Queue for tasks and data (similar to CoupledL2 SourceC)
  val queue = Module(new Queue(new MsgGenSourceCReq(edge, blockBytes), entries = entries, flow = true, pipe = false))
  queue.io.enq <> io.req

  // Current task being sent
  val beatValids = RegInit(VecInit(Seq.fill(lineBeats)(false.B)))
  val taskValid = beatValids.asUInt.orR
  val taskR = Reg(new MsgGenSourceCReq(edge, blockBytes))

  val reqIsProbeAck = io.req.bits.opcode === TLMessages.ProbeAck || io.req.bits.opcode === TLMessages.ProbeAckData
  val headIsRelease = queue.io.deq.bits.opcode === TLMessages.Release || queue.io.deq.bits.opcode === TLMessages.ReleaseData
  // Preserve the Probe snapshot for a full cycle before publishing the older same-block Release.
  val holdDequeue = !taskValid && queue.io.deq.valid && headIsRelease &&
    io.req.fire && reqIsProbeAck && io.req.bits.address === queue.io.deq.bits.address
  val dequeueReady = !taskValid && !holdDequeue
  queue.io.deq.ready := dequeueReady
  
  when(queue.io.deq.valid && dequeueReady) {
    beatValids.foreach(_ := true.B)
    taskR := queue.io.deq.bits
  }

  def toTLBundleC(task: MsgGenSourceCReq, data: UInt = 0.U) = {
    val c = WireDefault(0.U.asTypeOf(new TLBundleC(edge.bundle)))
    c.opcode := task.opcode
    c.param := task.param
    c.size := log2Ceil(blockBytes).U
    c.source := task.source
    c.address := task.address  // All beats use same address
    c.data := data
    c.corrupt := false.B
    c.echo.elements.get("blockisdirty").foreach(f => f := task.opcode(0).asTypeOf(f))
    c
  }

  def getBeat(data: UInt, beatsOH: UInt): (UInt, UInt) = {
    // Get one beat from data according to beatsOH
    require(data.getWidth == (blockBytes * 8))
    require(beatsOH.getWidth == lineBeats)
    
    val beatVec = data.asTypeOf(Vec(lineBeats, UInt(beatBits.W)))
    val selOH = PriorityEncoderOH(beatsOH)
    val next_beat = Mux1H(selOH, beatVec)
    val next_beatsOH = beatsOH & ~selOH
    (next_beat, next_beatsOH)
  }

  val data = taskR.data
  val beatsOH = beatValids.asUInt
  val (beat, next_beatsOH) = getBeat(data, beatsOH)

  io.tlc.valid := taskValid
  io.tlc.bits := toTLBundleC(taskR, beat)

  val hasData = io.tlc.bits.opcode(0)
  when (io.tlc.fire) {
    when (hasData) {
      beatValids := VecInit(next_beatsOH.asBools)
    }.otherwise {
      beatValids.foreach(_ := false.B)
    }
  }
}
