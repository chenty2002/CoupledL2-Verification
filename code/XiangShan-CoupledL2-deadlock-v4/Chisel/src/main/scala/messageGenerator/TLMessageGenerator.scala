package messageGenerator

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{BundleFieldBase, BundleKeyBase}
import org.chipsalliance.cde.config.Parameters

case class MessageGeneratorParam(
  name: String = "MsgGen", sets: Int = 16, ways: Int = 1,
  blockBytes: Int = 64, channelBytes: TLChannelBeatBytes = TLChannelBeatBytes(32),
  sourceIdRange: IdRange = IdRange(0, 16), reqField: Seq[BundleFieldBase] = Nil,
  respKey: Seq[BundleKeyBase] = Nil, supportsProbe: Option[TransferSizes] = None,
  minLatency: Int = 1, echoField: Seq[BundleFieldBase] = Nil,
  ordinaryEntries: Int = 0,
  // Opt-in parent contract: retain the current Grant copy and upstream dirty responsibility.
  retainedGrantCopy: Boolean = false
) {
  def lines: Int = sets * ways
  def clientName: String = name
  def requestFields: Seq[BundleFieldBase] = reqField
  def responseKeys: Seq[BundleKeyBase] = respKey
}

class TLMessageGenerator(params: MessageGeneratorParam)(implicit p: Parameters) extends LazyModule {
  require(isPow2(params.sets) && params.ways > 0)
  require(params.sourceIdRange.size > 0 && params.sourceIdRange.size <= 16)
  require(params.ordinaryEntries >= 0 && params.ordinaryEntries <= params.sourceIdRange.size)
  val node = TLClientNode(Seq(TLMasterPortParameters.v2(
    masters = Seq(TLMasterParameters.v1(name = params.name, sourceId = params.sourceIdRange,
      supportsProbe = params.supportsProbe.getOrElse(TransferSizes(params.blockBytes)))),
    channelBytes = params.channelBytes, minLatency = params.minLatency,
    requestFields = params.reqField, responseKeys = params.respKey, echoFields = params.echoField)))

  class TLMessageGeneratorImp(wrapper: TLMessageGenerator) extends LazyModuleImp(wrapper) {
    val (out, edge) = node.out.head
    val blockBits = params.blockBytes * 8
    val offBits = log2Ceil(params.blockBytes)
    val beatBytes = edge.bundle.dataBits / 8
    val beats = params.blockBytes / beatBytes
    require(beats >= 1 && params.blockBytes % beatBytes == 0)
    val beatIndexBits = log2Ceil(beats) max 1
    val n = if (params.ordinaryEntries > 0) params.ordinaryEntries else params.sourceIdRange.size
    require(n >= 1 && n <= params.sourceIdRange.size)
    val lines = params.lines
    val lineIndexBits = log2Ceil(lines) max 1
    def base(a: UInt): UInt = (a >> offBits) << offBits
    def set(a: UInt): UInt = (a >> offBits) & (params.sets - 1).U
    def source(i: UInt): UInt = i +& params.sourceIdRange.start.U

    val io = IO(new Bundle {
      val in_valid = Input(Bool())
      val in_addr = Input(UInt(edge.bundle.addressBits.W))
      val in_isAcquire = Input(Bool())
      val in_param = Input(Bool())
      // CPU writes are allowed only while this client owns stateT permission.
      val in_store = Input(Bool())
      val in_data = Input(UInt(blockBits.W))
    })

    val stateN :: stateB :: stateT :: Nil = Enum(3)
    val valid = RegInit(VecInit(Seq.fill(lines)(false.B)))
    val address = Reg(Vec(lines, UInt(edge.bundle.addressBits.W)))
    val permission = Reg(Vec(lines, UInt(2.W)))
    val data = Reg(Vec(lines, UInt(blockBits.W)))
    val localModified = if (params.retainedGrantCopy) Some(RegInit(VecInit(Seq.fill(lines)(false.B)))) else None
    val idle :: sendA :: waitD :: finish :: Nil = Enum(4)
    val state = RegInit(VecInit(Seq.fill(n)(idle)))
    val reqAddr = Reg(Vec(n, UInt(edge.bundle.addressBits.W)))
    val reqLine = Reg(Vec(n, UInt(lineIndexBits.W)))
    val hasWay = RegInit(VecInit(Seq.fill(n)(false.B)))
    val bufferValid = RegInit(VecInit(Seq.fill(n)(false.B)))
    val bufferPerm = Reg(Vec(n, UInt(2.W)))
    val ePending = RegInit(VecInit(Seq.fill(n)(false.B)))
    val needT = Reg(Vec(n, Bool()))
    val grow = Reg(Vec(n, UInt(2.W)))
    val grantSink = Reg(Vec(n, UInt(edge.bundle.sinkBits.W)))
    val grantBeat = RegInit(VecInit(Seq.fill(n)(0.U(beatIndexBits.W))))
    val grantData = Reg(Vec(n, Vec(beats, UInt(edge.bundle.dataBits.W))))
    val bufferModified = if (params.retainedGrantCopy) Some(RegInit(VecInit(Seq.fill(n)(false.B)))) else None

    val sourceC = Module(new MsgGenSourceC(edge, params.blockBytes, beatBytes, entries = 2))
    out.c <> sourceC.io.tlc
    val probeQueue = Module(new Queue(new Bundle {
      val param = UInt(out.b.bits.param.getWidth.W)
      val source = UInt(edge.bundle.sourceBits.W)
      val address = UInt(edge.bundle.addressBits.W)
      val dataRequired = if (params.retainedGrantCopy) Some(Bool()) else None
    }, entries = 2, flow = false, pipe = false))
    probeQueue.io.enq.valid := out.b.valid
    out.b.ready := probeQueue.io.enq.ready
    probeQueue.io.enq.bits.param := out.b.bits.param
    probeQueue.io.enq.bits.source := out.b.bits.source
    probeQueue.io.enq.bits.address := out.b.bits.address
    probeQueue.io.enq.bits.dataRequired.foreach(_ := out.b.bits.data(0))
    val probeIn = probeQueue.io.deq
    val releaseIdle :: releaseEnqueue :: releaseQueued :: releaseWaitAck :: Nil = Enum(4)
    val releaseState = RegInit(VecInit(Seq.fill(n)(releaseIdle)))
    val releaseLine = Reg(Vec(n, UInt(lineIndexBits.W)))
    val releaseReq = Reg(Vec(n, new MsgGenSourceCReq(edge, params.blockBytes)))
    val releaseToB = Reg(Vec(n, Bool()))
    val free = VecInit((0 until n).map(i => releaseState(i) === releaseIdle && state(i) === idle)).asUInt
    val alloc = PriorityEncoder(free)
    val probePending = RegInit(false.B)
    val probeAddr = Reg(UInt(edge.bundle.addressBits.W))
    val bBase = base(probeIn.bits.address)
    def probeConflict(a: UInt): Bool = (probePending && probeAddr === a) || (probeIn.valid && bBase === a)
    val reserved = VecInit((0 until lines).map(i => VecInit((0 until n).map(j =>
      (state(j) =/= idle && hasWay(j) && reqLine(j) === i.U) ||
      (releaseState(j) =/= releaseIdle && releaseLine(j) === i.U))).asUInt.orR))

    // Refill buffers acquire a way only after the Grant. One row changes owner per cycle.
    val commitWays = Wire(Vec(n, UInt(lineIndexBits.W)))
    val commitMask = VecInit((0 until n).map { i =>
      val candidates = VecInit((0 until lines).map(j => set(reqAddr(i)) === (j / params.ways).U && !reserved(j)))
      val empty = VecInit((0 until lines).map(j => candidates(j) && !valid(j)))
      commitWays(i) := Mux(hasWay(i), reqLine(i), PriorityEncoder(Mux(empty.asUInt.orR, empty.asUInt, candidates.asUInt)))
      val row = commitWays(i)
      state(i) === finish && bufferValid(i) && releaseState(i) === releaseIdle &&
        !probeConflict(reqAddr(i)) &&
        (bufferPerm(i) === stateN || ((hasWay(i) || candidates.asUInt.orR) &&
          !(valid(row) && probeConflict(address(row)))))
    }).asUInt
    val commitIndex = PriorityEncoder(commitMask)
    val commit = commitMask.orR
    val commitLine = commitWays(commitIndex)

    val inputBase = base(io.in_addr)
    val hits = VecInit((0 until lines).map(i => valid(i) && address(i) === inputBase))
    val hit = hits.asUInt.orR
    val hitLine = OHToUInt(hits)
    val sameBlock = VecInit((0 until n).map(i =>
      (state(i) =/= idle && reqAddr(i) === inputBase) ||
      (releaseState(i) =/= releaseIdle && releaseReq(i).address === inputBase))).asUInt.orR
    val canTake = io.in_valid && !sameBlock && !probeConflict(inputBase) && !commit &&
      (!hit || !reserved(hitLine))
    val needsAcquire = !hit || (io.in_param && permission(hitLine) === stateB)
    val allocate = canTake && io.in_isAcquire && needsAcquire && free.orR
    val explicitRelease = canTake && !io.in_isAcquire && hit && free.orR &&
      (!io.in_param || permission(hitLine) === stateT)
    when(allocate) {
      state(alloc) := sendA
      reqAddr(alloc) := inputBase
      reqLine(alloc) := hitLine
      hasWay(alloc) := hit
      bufferValid(alloc) := false.B
      ePending(alloc) := false.B
      needT(alloc) := io.in_param
      grow(alloc) := Mux(hit, TLPermissions.BtoT, Mux(io.in_param, TLPermissions.NtoT, TLPermissions.NtoB))
      grantBeat(alloc) := 0.U
      // Permission-only Grants preserve the existing Branch data.
      when(hit) { grantData(alloc) := data(hitLine).asTypeOf(grantData(alloc)) }
      bufferModified.foreach(m => m(alloc) := Mux(hit, localModified.get(hitLine), false.B))
    }
    when(explicitRelease) {
      releaseState(alloc) := releaseEnqueue
      releaseLine(alloc) := hitLine
      releaseToB(alloc) := io.in_param && permission(hitLine) === stateT
      releaseReq(alloc).source := source(alloc)
      releaseReq(alloc).opcode := TLMessages.ReleaseData
      releaseReq(alloc).param := Mux(permission(hitLine) === stateT,
        Mux(io.in_param, TLPermissions.TtoB, TLPermissions.TtoN), TLPermissions.BtoN)
      releaseReq(alloc).address := address(hitLine)
      releaseReq(alloc).data := data(hitLine)
    }
    when(commit) {
      when(bufferPerm(commitIndex) === stateN) {
        bufferValid(commitIndex) := false.B
        bufferModified.foreach(m => m(commitIndex) := false.B)
      }.elsewhen(valid(commitLine)) {
        assert(!hasWay(commitIndex), "reserved upgrade row still valid at commit")
        releaseState(commitIndex) := releaseEnqueue
        releaseLine(commitIndex) := commitLine
        releaseToB(commitIndex) := false.B
        releaseReq(commitIndex).source := source(commitIndex)
        releaseReq(commitIndex).opcode := TLMessages.ReleaseData
        releaseReq(commitIndex).param := Mux(permission(commitLine) === stateT, TLPermissions.TtoN, TLPermissions.BtoN)
        releaseReq(commitIndex).address := address(commitLine)
        releaseReq(commitIndex).data := data(commitLine)
        valid(commitLine) := false.B
        localModified.foreach(m => m(commitLine) := false.B)
        hasWay(commitIndex) := true.B
        reqLine(commitIndex) := commitLine
      }.otherwise {
        valid(commitLine) := true.B
        address(commitLine) := reqAddr(commitIndex)
        permission(commitLine) := bufferPerm(commitIndex)
        data(commitLine) := grantData(commitIndex).asUInt
        localModified.foreach(m => m(commitLine) := bufferModified.get(commitIndex))
        bufferModified.foreach(m => m(commitIndex) := false.B)
        reqLine(commitIndex) := commitLine
        hasWay(commitIndex) := true.B
        bufferValid(commitIndex) := false.B
      }
    }
    for(i <- 0 until lines) {
      when(io.in_valid && io.in_isAcquire && io.in_store && hit && hitLine === i.U &&
        permission(i) === stateT && !reserved(i) && !probeConflict(inputBase) && !commit) {
        data(i) := io.in_data
        localModified.foreach(m => m(i) := true.B)
      }
    }
    for(i <- 0 until n) {
      when(io.in_valid && io.in_isAcquire && io.in_store && state(i) === finish &&
        bufferValid(i) && !ePending(i) && bufferPerm(i) === stateT && reqAddr(i) === inputBase &&
        !probeConflict(inputBase) && !commit) {
        grantData(i) := io.in_data.asTypeOf(grantData(i))
        bufferModified.foreach(m => m(i) := true.B)
      }
      when(state(i) === finish && !bufferValid(i) && !ePending(i) && releaseState(i) === releaseIdle) {
        state(i) := idle
        hasWay(i) := false.B
      }
    }

    val probeHits = VecInit((0 until lines).map(i => valid(i) && address(i) === bBase))
    val probeLine = OHToUInt(probeHits)
    val probeBufferHits = VecInit((0 until n).map(i => state(i) === finish && bufferValid(i) &&
      bufferPerm(i) =/= stateN && reqAddr(i) === bBase))
    val probeBuffer = OHToUInt(probeBufferHits)
    val probeInBuffer = probeBufferHits.asUInt.orR
    val probeHit = probeHits.asUInt.orR || probeInBuffer
    val probeT = probeHit && Mux(probeInBuffer, bufferPerm(probeBuffer), permission(probeLine)) === stateT
    val probeModified = if (params.retainedGrantCopy)
      Mux(probeInBuffer, bufferModified.get(probeBuffer), localModified.get(probeLine)) else true.B
    val probeData = probeHit && ((probeT && probeModified) || probeIn.bits.dataRequired.getOrElse(false.B))
    val dMatches = VecInit((0 until n).map(i => out.d.bits.source === (i + params.sourceIdRange.start).U))
    val dIndex = OHToUInt(dMatches)
    val isGrant = out.d.bits.opcode === TLMessages.Grant || out.d.bits.opcode === TLMessages.GrantData
    val receivingGrant = VecInit((0 until n).map(i => reqAddr(i) === bBase &&
      (ePending(i) || (state(i) === waitD && grantBeat(i) =/= 0.U)))).asUInt.orR ||
      (out.d.valid && isGrant && dMatches.asUInt.orR && reqAddr(dIndex) === bBase)
    val cIsRelease = out.c.bits.opcode === TLMessages.Release || out.c.bits.opcode === TLMessages.ReleaseData
    val idleQueuedProbe = VecInit((0 until n).map(i => releaseState(i) === releaseQueued &&
      releaseReq(i).address === bBase && !out.c.valid))
    val releaseConflict = VecInit((0 until n).map { i =>
      val ackedNow = releaseState(i) === releaseWaitAck && out.d.fire &&
        out.d.bits.opcode === TLMessages.ReleaseAck && dMatches(i)
      // An older different-block C task keeps this queued Release from being presented this cycle.
      val queuedCross = releaseState(i) === releaseQueued && out.c.valid && cIsRelease &&
        out.c.bits.address =/= releaseReq(i).address
      releaseState(i) =/= releaseIdle && releaseReq(i).address === bBase && !ackedNow && !queuedCross && !idleQueuedProbe(i)
    }).asUInt.orR
    probeIn.ready := !probePending && !receivingGrant && !releaseConflict && sourceC.io.req.ready
    val probeReq = Wire(new MsgGenSourceCReq(edge, params.blockBytes))
    probeReq.source := probeIn.bits.source
    probeReq.opcode := Mux(probeData, TLMessages.ProbeAckData, TLMessages.ProbeAck)
    probeReq.address := bBase
    probeReq.data := Mux(probeData, Mux(probeInBuffer, grantData(probeBuffer).asUInt, data(probeLine)), 0.U)
    probeReq.param := Mux(!probeHit, TLPermissions.NtoN,
      Mux(probeT, Mux(probeIn.bits.param === TLPermissions.toN, TLPermissions.TtoN,
        Mux(probeIn.bits.param === TLPermissions.toB, TLPermissions.TtoB, TLPermissions.TtoT)),
        Mux(probeIn.bits.param === TLPermissions.toN, TLPermissions.BtoN, TLPermissions.BtoB)))
    val releaseSendMask = VecInit(releaseState.map(_ === releaseEnqueue)).asUInt
    val releaseSend = PriorityEncoder(releaseSendMask)
    sourceC.io.req.valid := probeIn.fire || releaseSendMask.orR
    sourceC.io.req.bits := Mux(probeIn.fire, probeReq, releaseReq(releaseSend))
    when(probeIn.fire) {
      probePending := true.B
      probeAddr := bBase
      when(probeHits.asUInt.orR) {
        when(probeIn.bits.param =/= TLPermissions.toT) { localModified.foreach(m => m(probeLine) := false.B) }
        when(probeIn.bits.param === TLPermissions.toN) { valid(probeLine) := false.B }
          .elsewhen(probeIn.bits.param === TLPermissions.toB) { permission(probeLine) := stateB }
      }
      when(probeInBuffer) {
        when(probeIn.bits.param =/= TLPermissions.toT) { bufferModified.foreach(m => m(probeBuffer) := false.B) }
        when(probeIn.bits.param === TLPermissions.toN) { bufferPerm(probeBuffer) := stateN }
          .elsewhen(probeIn.bits.param === TLPermissions.toB) { bufferPerm(probeBuffer) := stateB }
      }
      for(i <- 0 until n) {
        when(state(i) === sendA && reqAddr(i) === bBase && probeIn.bits.param === TLPermissions.toN) {
          grow(i) := Mux(needT(i), TLPermissions.NtoT, TLPermissions.NtoB)
        }
      }
    }
    when(sourceC.io.req.fire && !probeIn.fire) {
      releaseState(releaseSend) := releaseQueued
      valid(releaseLine(releaseSend)) := releaseToB(releaseSend)
      permission(releaseLine(releaseSend)) := stateB
      localModified.foreach(m => m(releaseLine(releaseSend)) := false.B)
    }
    for (i <- 0 until n) {
      when(releaseState(i) === releaseQueued && out.c.valid && cIsRelease &&
        out.c.bits.source === releaseReq(i).source && out.c.bits.address === releaseReq(i).address) {
        releaseState(i) := releaseWaitAck
      }
    }
    when(out.c.fire && edge.last(out.c) &&
      (out.c.bits.opcode === TLMessages.ProbeAck || out.c.bits.opcode === TLMessages.ProbeAckData)) {
      probePending := false.B
      when(out.c.bits.opcode === TLMessages.ProbeAckData && out.c.bits.param === TLPermissions.TtoT) {
        for (i <- 0 until lines) {
          when(valid(i) && address(i) === probeAddr) { localModified.foreach(m => m(i) := false.B) }
        }
        for (i <- 0 until n) {
          when(bufferValid(i) && reqAddr(i) === probeAddr) { bufferModified.foreach(m => m(i) := false.B) }
        }
      }
    }

    val sendMask = VecInit(state.map(_ === sendA)).asUInt
    val aLocked = RegInit(false.B)
    val aIndexReg = Reg(UInt((log2Ceil(n) max 1).W))
    val aBitsReg = Reg(out.a.bits.cloneType)
    val aIndex = Mux(aLocked, aIndexReg, PriorityEncoder(sendMask))
    val (_, aMessage) = edge.AcquireBlock(source(aIndex), reqAddr(aIndex), offBits.U, grow(aIndex))
    out.a.valid := aLocked || sendMask.orR
    out.a.bits := Mux(aLocked, aBitsReg, aMessage)
    when(out.a.valid && !out.a.ready && !aLocked) {
      aLocked := true.B
      aIndexReg := aIndex
      aBitsReg := out.a.bits
    }
    when(out.a.fire) { state(aIndex) := waitD; aLocked := false.B }

    out.d.ready := true.B
    when(out.d.fire && out.d.bits.opcode === TLMessages.ReleaseAck) {
      assert(dMatches.asUInt.orR && (releaseState(dIndex) === releaseQueued || releaseState(dIndex) === releaseWaitAck), "unsolicited ReleaseAck")
      releaseState(dIndex) := releaseIdle
    }
    when(out.d.fire && isGrant) {
      assert(dMatches.asUInt.orR && state(dIndex) === waitD, "unsolicited Grant")
      when(out.d.bits.opcode === TLMessages.GrantData) {
        grantData(dIndex)(grantBeat(dIndex)) := out.d.bits.data
        grantBeat(dIndex) := grantBeat(dIndex) + 1.U
      }
      when(edge.last(out.d)) {
        when(out.d.bits.opcode === TLMessages.GrantData) { bufferModified.foreach(m => m(dIndex) := false.B) }
        when(hasWay(dIndex)) { valid(reqLine(dIndex)) := false.B }
        bufferValid(dIndex) := true.B
        bufferPerm(dIndex) := Mux(out.d.bits.param === TLPermissions.toT, stateT, stateB)
        grantSink(dIndex) := out.d.bits.sink
        grantBeat(dIndex) := 0.U
        state(dIndex) := finish
        ePending(dIndex) := true.B
      }
    }
    val ackMask = ePending.asUInt
    val eLocked = RegInit(false.B)
    val eIndexReg = Reg(UInt((log2Ceil(n) max 1).W))
    val eIndex = Mux(eLocked, eIndexReg, PriorityEncoder(ackMask))
    out.e.valid := eLocked || ackMask.orR
    out.e.bits := 0.U.asTypeOf(out.e.bits)
    out.e.bits.sink := grantSink(eIndex)
    when(out.e.valid && !out.e.ready) { eLocked := true.B; eIndexReg := eIndex }
    when(out.e.fire) { ePending(eIndex) := false.B; eLocked := false.B }
    for(i <- 0 until n; j <- 0 until i) {
      assert(state(i) === idle || state(j) === idle || reqAddr(i) =/= reqAddr(j), "duplicate block Acquire")
      assert(state(i) === idle || state(j) === idle || !hasWay(i) || !hasWay(j) || reqLine(i) =/= reqLine(j), "duplicate reserved row")
    }
    for(i <- 0 until lines; j <- 0 until i) {
      assert(!valid(i) || !valid(j) || address(i) =/= address(j), "duplicate directory block")
    }
  }
  lazy val module = new TLMessageGeneratorImp(this)
}
