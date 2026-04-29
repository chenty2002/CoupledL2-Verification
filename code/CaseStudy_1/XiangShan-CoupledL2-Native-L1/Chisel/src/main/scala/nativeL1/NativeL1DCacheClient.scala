package nativeL1

import chisel3._
import chisel3.util._
import coupledL2.L2ParamKey
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import huancun.AliasField
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink.ClientStates
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLPermissions._

class NativeL1DCacheClient(name: String, sources: Int)(implicit p: Parameters) extends LazyModule {
  private val cacheParams = p(L2ParamKey)
  private val cacheSets = math.max(2, math.min(8, cacheParams.sets))
  private val cacheWays = math.max(2, math.min(4, cacheParams.ways))
  private val setBits = log2Ceil(cacheSets)
  private val wayBits = log2Ceil(cacheWays)

  val node = TLClientNode(Seq(
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

  class NativeL1DCacheClientImp(wrapper: LazyModule) extends LazyModuleImp(wrapper) {
    val (out, _) = node.out.head
    val lsuOpBits = 2
    val LSU_OP_LOAD = 0.U(lsuOpBits.W)
    val LSU_OP_STORE = 1.U(lsuOpBits.W)
    val LSU_OP_AMO = 2.U(lsuOpBits.W)
    val defaultAcquireSize = log2Ceil(cacheParams.blockBytes).U(out.a.bits.size.getWidth.W)

    val io = IO(new Bundle {
      val acquireValid = Input(Bool())
      val acquireAddr = Input(UInt(out.a.bits.address.getWidth.W))
      val acquireSize = Input(UInt(out.a.bits.size.getWidth.W))
      val acquireCmd = Input(UInt(MemoryOpConstants.M_SZ.W))
      val acquireNeedT = Input(Bool())
      val acquireSource = Input(UInt(out.a.bits.source.getWidth.W))

      val releaseValid = Input(Bool())
      val releaseAddr = Input(UInt(out.c.bits.address.getWidth.W))
      val releaseSize = Input(UInt(out.c.bits.size.getWidth.W))
      val releaseData = Input(UInt(out.c.bits.data.getWidth.W))
      val releaseToB = Input(Bool())
      val releaseSource = Input(UInt(out.c.bits.source.getWidth.W))

      val inputBReady = Input(Bool())
      val inputDReady = Input(Bool())

      // LSU-like native request interface (inspired by XiangShan DCache load/store/atomics).
      val lsuLoadValid = Input(Bool())
      val lsuLoadAddr = Input(UInt(out.a.bits.address.getWidth.W))
      val lsuLoadCmd = Input(UInt(MemoryOpConstants.M_SZ.W))
      val lsuLoadId = Input(UInt(out.a.bits.source.getWidth.W))

      val lsuStoreValid = Input(Bool())
      val lsuStoreAddr = Input(UInt(out.a.bits.address.getWidth.W))
      val lsuStoreCmd = Input(UInt(MemoryOpConstants.M_SZ.W))
      val lsuStoreId = Input(UInt(out.a.bits.source.getWidth.W))
      val lsuStoreData = Input(UInt(out.c.bits.data.getWidth.W))
      val lsuStoreMask = Input(UInt(out.a.bits.mask.getWidth.W))

      val lsuAmoValid = Input(Bool())
      val lsuAmoAddr = Input(UInt(out.a.bits.address.getWidth.W))
      val lsuAmoCmd = Input(UInt(MemoryOpConstants.M_SZ.W))
      val lsuAmoId = Input(UInt(out.a.bits.source.getWidth.W))
      val lsuAmoData = Input(UInt(out.c.bits.data.getWidth.W))
      val lsuAmoMask = Input(UInt(out.a.bits.mask.getWidth.W))

      val lsuLoadRespValid = Output(Bool())
      val lsuLoadRespMiss = Output(Bool())
      val lsuLoadRespReplay = Output(Bool())
      val lsuLoadRespData = Output(UInt(out.c.bits.data.getWidth.W))
      val lsuLoadRespId = Output(UInt(out.a.bits.source.getWidth.W))

      val lsuStoreRespValid = Output(Bool())
      val lsuStoreRespMiss = Output(Bool())
      val lsuStoreRespReplay = Output(Bool())
      val lsuStoreRespId = Output(UInt(out.a.bits.source.getWidth.W))

      val lsuAmoRespValid = Output(Bool())
      val lsuAmoRespMiss = Output(Bool())
      val lsuAmoRespReplay = Output(Bool())
      val lsuAmoRespData = Output(UInt(out.c.bits.data.getWidth.W))
      val lsuAmoRespId = Output(UInt(out.a.bits.source.getWidth.W))
    })

    io.lsuLoadRespValid := false.B
    io.lsuLoadRespMiss := false.B
    io.lsuLoadRespReplay := false.B
    io.lsuLoadRespData := 0.U
    io.lsuLoadRespId := 0.U
    io.lsuStoreRespValid := false.B
    io.lsuStoreRespMiss := false.B
    io.lsuStoreRespReplay := false.B
    io.lsuStoreRespId := 0.U
    io.lsuAmoRespValid := false.B
    io.lsuAmoRespMiss := false.B
    io.lsuAmoRespReplay := false.B
    io.lsuAmoRespData := 0.U
    io.lsuAmoRespId := 0.U

    val lineTags = RegInit(VecInit(Seq.fill(cacheSets)(VecInit(Seq.fill(cacheWays)(0.U(out.a.bits.address.getWidth.W))))))
    val lineStates = RegInit(VecInit(Seq.fill(cacheSets)(VecInit(Seq.fill(cacheWays)(ClientStates.Nothing)))))
    val lineData = RegInit(VecInit(Seq.fill(cacheSets)(VecInit(Seq.fill(cacheWays)(0.U(out.c.bits.data.getWidth.W))))))
    val replPtr = RegInit(VecInit(Seq.fill(cacheSets)(0.U(wayBits.W))))

    val pendingValid = RegInit(VecInit(Seq.fill(sources)(false.B)))
    val pendingSet = RegInit(VecInit(Seq.fill(sources)(0.U(setBits.W))))
    val pendingWay = RegInit(VecInit(Seq.fill(sources)(0.U(wayBits.W))))
    val pendingNeedT = RegInit(VecInit(Seq.fill(sources)(false.B)))
    val pendingFromLSU = RegInit(VecInit(Seq.fill(sources)(false.B)))
    val pendingLsuOp = RegInit(VecInit(Seq.fill(sources)(LSU_OP_LOAD)))
    val pendingLsuId = RegInit(VecInit(Seq.fill(sources)(0.U(out.a.bits.source.getWidth.W))))

    def getSet(addr: UInt): UInt = {
      addr(setBits - 1, 0)
    }

    def applyByteMask(oldData: UInt, newData: UInt, byteMask: UInt): UInt = {
      val fullMask = FillInterleaved(8, byteMask)
      (oldData & ~fullMask) | (newData & fullMask)
    }

    def writeLineState(set: UInt, way: UInt, newState: UInt): Unit = {
      for (s <- 0 until cacheSets) {
        when(set === s.U) {
          for (w <- 0 until cacheWays) {
            when(way === w.U) {
              lineStates(s)(w) := newState
            }
          }
        }
      }
    }

    def writeLineTag(set: UInt, way: UInt, newTag: UInt): Unit = {
      for (s <- 0 until cacheSets) {
        when(set === s.U) {
          for (w <- 0 until cacheWays) {
            when(way === w.U) {
              lineTags(s)(w) := newTag
            }
          }
        }
      }
    }

    def writeLineData(set: UInt, way: UInt, newData: UInt): Unit = {
      for (s <- 0 until cacheSets) {
        when(set === s.U) {
          for (w <- 0 until cacheWays) {
            when(way === w.U) {
              lineData(s)(w) := newData
            }
          }
        }
      }
    }

    val lsuAmoChosen = io.lsuAmoValid
    val lsuStoreChosen = !lsuAmoChosen && io.lsuStoreValid
    val lsuLoadChosen = !lsuAmoChosen && !lsuStoreChosen && io.lsuLoadValid
    val lsuReqChosen = lsuAmoChosen || lsuStoreChosen || lsuLoadChosen

    val selectedAcquireValid = Mux(lsuReqChosen, true.B, io.acquireValid)
    val selectedAcquireAddr = Mux(
      lsuAmoChosen,
      io.lsuAmoAddr,
      Mux(lsuStoreChosen, io.lsuStoreAddr, Mux(lsuLoadChosen, io.lsuLoadAddr, io.acquireAddr))
    )
    val selectedAcquireCmd = Mux(
      lsuAmoChosen,
      io.lsuAmoCmd,
      Mux(lsuStoreChosen, io.lsuStoreCmd, Mux(lsuLoadChosen, io.lsuLoadCmd, io.acquireCmd))
    )
    val selectedAcquireSource = Mux(
      lsuAmoChosen,
      io.lsuAmoId,
      Mux(lsuStoreChosen, io.lsuStoreId, Mux(lsuLoadChosen, io.lsuLoadId, io.acquireSource))
    )
    val selectedAcquireNeedT = Mux(lsuReqChosen, lsuStoreChosen || lsuAmoChosen, io.acquireNeedT)
    val selectedAcquireSize = Mux(lsuReqChosen, defaultAcquireSize, io.acquireSize)

    val acquireSet = getSet(selectedAcquireAddr)
    val acquireSetTags = lineTags(acquireSet)
    val acquireSetStates = lineStates(acquireSet)
    val acquireHitVec = Wire(Vec(cacheWays, Bool()))
    for (w <- 0 until cacheWays) {
      acquireHitVec(w) := acquireSetTags(w) === selectedAcquireAddr && acquireSetStates(w) =/= ClientStates.Nothing
    }
    val acquireHit = acquireHitVec.asUInt.orR
    val acquireWay = Mux(acquireHit, OHToUInt(acquireHitVec), replPtr(acquireSet))
    val acquireState = acquireSetStates(acquireWay)
    val acquireNeedWritePerm = MemoryOpConstants.isWriteIntent(selectedAcquireCmd)
    val acquireNeedMiss = !acquireHit || (acquireNeedWritePerm && acquireState === ClientStates.Branch)

    when(lsuLoadChosen && !acquireNeedMiss) {
      io.lsuLoadRespValid := true.B
      io.lsuLoadRespMiss := false.B
      io.lsuLoadRespReplay := false.B
      io.lsuLoadRespData := lineData(acquireSet)(acquireWay)
      io.lsuLoadRespId := io.lsuLoadId
    }

    when(lsuStoreChosen && !acquireNeedMiss) {
      val oldData = lineData(acquireSet)(acquireWay)
      val newData = applyByteMask(oldData, io.lsuStoreData, io.lsuStoreMask)
      writeLineData(acquireSet, acquireWay, newData)
      writeLineState(acquireSet, acquireWay, ClientStates.Trunk)
      io.lsuStoreRespValid := true.B
      io.lsuStoreRespMiss := false.B
      io.lsuStoreRespReplay := false.B
      io.lsuStoreRespId := io.lsuStoreId
    }

    when(lsuAmoChosen && !acquireNeedMiss) {
      val oldData = lineData(acquireSet)(acquireWay)
      val newData = applyByteMask(oldData, io.lsuAmoData, io.lsuAmoMask)
      writeLineData(acquireSet, acquireWay, newData)
      writeLineState(acquireSet, acquireWay, ClientStates.Trunk)
      io.lsuAmoRespValid := true.B
      io.lsuAmoRespMiss := false.B
      io.lsuAmoRespReplay := false.B
      io.lsuAmoRespData := oldData
      io.lsuAmoRespId := io.lsuAmoId
    }

    val probeQ = Module(new Queue(chiselTypeOf(out.b.bits), 2))
    probeQ.io.enq.valid := out.b.valid && io.inputBReady
    probeQ.io.enq.bits := out.b.bits
    out.b.ready := io.inputBReady && probeQ.io.enq.ready

    val cRelease = WireInit(0.U.asTypeOf(out.c.bits))
    val releaseSet = getSet(io.releaseAddr)
    val releaseSetTags = lineTags(releaseSet)
    val releaseSetStates = lineStates(releaseSet)
    val releaseSetData = lineData(releaseSet)
    val releaseHitVec = Wire(Vec(cacheWays, Bool()))
    for (w <- 0 until cacheWays) {
      releaseHitVec(w) := releaseSetTags(w) === io.releaseAddr && releaseSetStates(w) =/= ClientStates.Nothing
    }
    val releaseHit = releaseHitVec.asUInt.orR
    val releaseWay = Mux(releaseHit, OHToUInt(releaseHitVec), 0.U)
    val releaseState = releaseSetStates(releaseWay)
    val releaseLineData = releaseSetData(releaseWay)

    cRelease.opcode := ReleaseData
    cRelease.param := Mux(
      releaseState === ClientStates.Branch,
      Mux(io.releaseToB, BtoB, BtoN),
      Mux(io.releaseToB, TtoB, TtoN)
    )
    cRelease.size := io.releaseSize
    cRelease.source := io.releaseSource
    cRelease.address := io.releaseAddr
    cRelease.data := Mux(releaseHit, releaseLineData, io.releaseData)
    cRelease.corrupt := false.B

    val cProbeAck = WireInit(0.U.asTypeOf(out.c.bits))
    val probeSet = getSet(probeQ.io.deq.bits.address)
    val probeSetTags = lineTags(probeSet)
    val probeSetStates = lineStates(probeSet)
    val probeSetData = lineData(probeSet)
    val probeHitVec = Wire(Vec(cacheWays, Bool()))
    for (w <- 0 until cacheWays) {
      probeHitVec(w) := probeSetTags(w) === probeQ.io.deq.bits.address && probeSetStates(w) =/= ClientStates.Nothing
    }
    val probeHit = probeHitVec.asUInt.orR
    val probeWay = Mux(probeHit, OHToUInt(probeHitVec), 0.U)
    val probeState = probeSetStates(probeWay)
    val probeData = probeSetData(probeWay)
    val probeToN = probeQ.io.deq.bits.param === toN
    val probeNeedsData = probeHit && (probeState === ClientStates.Trunk || probeState === ClientStates.Dirty)

    cProbeAck.opcode := Mux(probeNeedsData, ProbeAckData, ProbeAck)
    cProbeAck.param := Mux(
      probeHit,
      Mux(
        probeState === ClientStates.Branch,
        Mux(probeToN, BtoN, BtoB),
        Mux(probeToN, TtoN, TtoB)
      ),
      NtoN
    )
    cProbeAck.size := probeQ.io.deq.bits.size
    cProbeAck.source := probeQ.io.deq.bits.source
    cProbeAck.address := probeQ.io.deq.bits.address
    cProbeAck.data := Mux(probeNeedsData, probeData, 0.U)
    cProbeAck.corrupt := false.B

    val sendProbeAck = probeQ.io.deq.valid
    out.c.valid := sendProbeAck || io.releaseValid
    out.c.bits := Mux(sendProbeAck, cProbeAck, cRelease)
    probeQ.io.deq.ready := sendProbeAck && out.c.ready

    val aAcquire = WireInit(0.U.asTypeOf(out.a.bits))
    aAcquire.opcode := Mux(acquireNeedWritePerm, AcquirePerm, AcquireBlock)
    aAcquire.param := Mux(
      acquireNeedWritePerm,
      Mux(acquireHit && acquireState === ClientStates.Branch, BtoT, NtoT),
      Mux(selectedAcquireNeedT, NtoT, NtoB)
    )
    aAcquire.size := selectedAcquireSize
    aAcquire.source := selectedAcquireSource
    aAcquire.address := selectedAcquireAddr
    aAcquire.mask := Fill(out.a.bits.mask.getWidth, 1.U(1.W))
    aAcquire.data := 0.U
    aAcquire.corrupt := false.B

    out.a.valid := selectedAcquireValid && acquireNeedMiss
    out.a.bits := aAcquire

    when(out.a.fire) {
      when(selectedAcquireSource < sources.U) {
        pendingValid(selectedAcquireSource) := true.B
        pendingSet(selectedAcquireSource) := acquireSet
        pendingWay(selectedAcquireSource) := acquireWay
        pendingNeedT(selectedAcquireSource) := selectedAcquireNeedT || acquireNeedWritePerm
        pendingFromLSU(selectedAcquireSource) := lsuReqChosen
        pendingLsuOp(selectedAcquireSource) := Mux(lsuAmoChosen, LSU_OP_AMO, Mux(lsuStoreChosen, LSU_OP_STORE, LSU_OP_LOAD))
        pendingLsuId(selectedAcquireSource) := selectedAcquireSource
      }

      when(!acquireHit) {
        writeLineTag(acquireSet, acquireWay, selectedAcquireAddr)
        writeLineState(acquireSet, acquireWay, ClientStates.Nothing)
        writeLineData(acquireSet, acquireWay, 0.U)
        replPtr(acquireSet) := replPtr(acquireSet) + 1.U
      }
    }

    val grantAckQ = Module(new Queue(UInt(out.e.bits.sink.getWidth.W), 2))
    val dIsGrant = out.d.bits.opcode === Grant || out.d.bits.opcode === GrantData
    val dFire = out.d.valid && out.d.ready

    grantAckQ.io.enq.valid := dFire && dIsGrant
    grantAckQ.io.enq.bits := out.d.bits.sink

    out.d.ready := io.inputDReady && (!dIsGrant || grantAckQ.io.enq.ready)

    val dSrcInRange = out.d.bits.source < sources.U
    val dTracked = dSrcInRange && pendingValid(out.d.bits.source)
    when(dFire && dIsGrant && dTracked) {
      val grantSet = pendingSet(out.d.bits.source)
      val grantWay = pendingWay(out.d.bits.source)
      val grantNeedT = pendingNeedT(out.d.bits.source)

      writeLineState(
        grantSet,
        grantWay,
        Mux(out.d.bits.param === toT || grantNeedT, ClientStates.Trunk, ClientStates.Branch)
      )

      when(out.d.bits.opcode === GrantData) {
        writeLineData(grantSet, grantWay, out.d.bits.data)
      }

      when(pendingFromLSU(out.d.bits.source)) {
        when(pendingLsuOp(out.d.bits.source) === LSU_OP_LOAD) {
          io.lsuLoadRespValid := true.B
          io.lsuLoadRespMiss := false.B
          io.lsuLoadRespReplay := false.B
          io.lsuLoadRespData := Mux(out.d.bits.opcode === GrantData, out.d.bits.data, 0.U)
          io.lsuLoadRespId := pendingLsuId(out.d.bits.source)
        }
        when(pendingLsuOp(out.d.bits.source) === LSU_OP_STORE) {
          io.lsuStoreRespValid := true.B
          io.lsuStoreRespMiss := false.B
          io.lsuStoreRespReplay := false.B
          io.lsuStoreRespId := pendingLsuId(out.d.bits.source)
        }
        when(pendingLsuOp(out.d.bits.source) === LSU_OP_AMO) {
          io.lsuAmoRespValid := true.B
          io.lsuAmoRespMiss := false.B
          io.lsuAmoRespReplay := false.B
          io.lsuAmoRespData := Mux(out.d.bits.opcode === GrantData, out.d.bits.data, 0.U)
          io.lsuAmoRespId := pendingLsuId(out.d.bits.source)
        }
      }

      pendingValid(out.d.bits.source) := false.B
      pendingNeedT(out.d.bits.source) := false.B
      pendingFromLSU(out.d.bits.source) := false.B
    }

    when(out.c.fire && sendProbeAck && probeHit) {
      writeLineState(
        probeSet,
        probeWay,
        Mux(probeToN, ClientStates.Nothing, ClientStates.Branch)
      )
    }

    when(out.c.fire && !sendProbeAck && io.releaseValid && releaseHit) {
      writeLineState(
        releaseSet,
        releaseWay,
        Mux(io.releaseToB, ClientStates.Branch, ClientStates.Nothing)
      )
      when(!io.releaseToB) {
        writeLineData(releaseSet, releaseWay, 0.U)
      }
    }

    val eBits = WireInit(0.U.asTypeOf(out.e.bits))
    eBits.sink := grantAckQ.io.deq.bits
    out.e.valid := grantAckQ.io.deq.valid
    out.e.bits := eBits
    grantAckQ.io.deq.ready := out.e.ready
  }

  lazy val module = new NativeL1DCacheClientImp(this)
}
