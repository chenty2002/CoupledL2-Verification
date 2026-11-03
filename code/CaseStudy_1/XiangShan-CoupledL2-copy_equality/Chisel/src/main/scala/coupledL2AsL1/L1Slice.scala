package coupledL2AsL1

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import coupledL2._
import coupledL2.tl2tl.Slice
import freechips.rocketchip.tilelink.TLMessages._
import org.chipsalliance.cde.config.Parameters

class L1RequestArb(implicit p: Parameters) extends RequestArb {
  override protected def hintCanAccessDS: Bool =
    io.sinkA.bits.reqSource === Input2ReqPfSource.PrefetchRelease
}

class L1Slice()(implicit p: Parameters) extends Slice {
  override lazy val reqArb = Module(new L1RequestArb())
  override lazy val mainPipe = Module(new L1MainPipe())
  val io_storePending = IO(Input(Bool()))
  val io_storeSource = IO(Input(UInt(sourceIdBits.W)))
  val io_storeTag = IO(Input(UInt(tagBits.W)))
  val io_storeSet = IO(Input(UInt(setBits.W)))
  val io_storeData = IO(Input(UInt(blockBits.W)))
  val io_storeCanIssue = IO(Output(Bool()))
  val io_storeDone = IO(Output(Bool()))
  val io_storeFire = IO(Output(Bool()))
  val queuedCpu = VecInit(a_reqBuf.buffer.map(e => BoringUtils.bore(e.valid))).asUInt.orR ||
    (BoringUtils.bore(a_reqBuf.chosenQ.io.count) =/= 0.U)
  def busyLine(tag: UInt, set: UInt): Bool = VecInit(mshrCtl.io.msInfo.map(s =>
    s.valid && s.bits.set === set && (s.bits.reqTag === tag || s.bits.needRelease && s.bits.metaTag === tag))).asUInt.orR
  val incoming = io.prefetch.get.req.bits
  // Drain real CPU queues and pipeline before associating the one pending store with its original Hint.
  io_storeCanIssue := !queuedCpu && !mainPipe.io_storeOrdinaryBusy && !busyLine(incoming.tag, incoming.set)
  val snapshotEnqueue = sourceC.io.in.fire
  val snapshotDone = io.out.c.fire && edgeOut.last(io.out.c)
  val snapshots = RegInit(0.U(log2Ceil(mshrsAll + 2).W))
  when(snapshotEnqueue =/= snapshotDone) {
    snapshots := Mux(snapshotEnqueue, snapshots + 1.U, snapshots - 1.U)
  }
  assert(!snapshotDone || snapshots =/= 0.U || snapshotEnqueue)
  val incomingProbe = io.out.b.valid && (io.out.b.bits.address >> offsetBits) === Cat(io_storeTag, io_storeSet)
  val bufferedProbe = sinkB.io.b.valid && (sinkB.io.b.bits.address >> offsetBits) === Cat(io_storeTag, io_storeSet)
  val bufferedRelease = sinkC.io.c.valid && (sinkC.io.c.bits.address >> offsetBits) === Cat(io_storeTag, io_storeSet)
  def queuedSnapshot(valid: Bool, t: TaskBundle): Bool = valid && t.tag === io_storeTag && t.set === io_storeSet
  // shortcut: stores wait for all C snapshots, refine per-block tracking only if unrelated-C concurrency is needed.
  mainPipe.io_storeBlock := busyLine(io_storeTag, io_storeSet) || incomingProbe || bufferedProbe || bufferedRelease ||
    queuedSnapshot(sinkB.io.task.valid, sinkB.io.task.bits) || queuedSnapshot(sinkC.io.task.valid, sinkC.io.task.bits) ||
    snapshots =/= 0.U || sourceC.io.in.valid || io.out.c.valid || io.out.e.valid
  mainPipe.io_storePending := io_storePending
  mainPipe.io_storeSource := io_storeSource
  mainPipe.io_storeTag := io_storeTag
  mainPipe.io_storeSet := io_storeSet
  mainPipe.io_storeData := io_storeData
  io_storeDone := mainPipe.io_storeDone
  io_storeFire := mainPipe.io_storeFire
  val probeClients = edgeOut.client.clients.filter(_.supports.probe)
  require(probeClients.size == 1 && probeClients.head.sourceId.start == 0,
    "The native copy-equality Probe source adapter requires one client starting at zero")
  when(io.out.b.valid) { assert(io.out.b.bits.source === 0.U) }
  // The fixed DUT SourceB uses the sole native client start ID, zero.
  when(io.out.c.valid && (io.out.c.bits.opcode === ProbeAck || io.out.c.bits.opcode === ProbeAckData)) {
    io.out.c.bits.source := 0.U
  }
}
