package coupledL2AsL1

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import coupledL2._
import coupledL2.tl2tl._
import coupledL2.MetaData._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink.TLPermissions._
import freechips.rocketchip.tilelink.TLMessages._
import freechips.rocketchip.tilelink.TLHints._
import utility.ParallelPriorityMux

class L1MainPipe(implicit p: Parameters) extends MainPipe {
    val io_storePending = IO(Input(Bool()))
    val io_storeSource = IO(Input(UInt(sourceIdBits.W)))
    val io_storeTag = IO(Input(UInt(tagBits.W)))
    val io_storeSet = IO(Input(UInt(setBits.W)))
    val io_storeData = IO(Input(UInt(blockBits.W)))
    val io_storeBlock = IO(Input(Bool()))
    val io_storeDone = IO(Output(Bool()))
    val io_storeFire = IO(Output(Bool()))
    val io_storeOrdinaryBusy = IO(Output(Bool()))
    def ordinary(t: ValidIO[TaskBundle]): Bool = t.valid && t.bits.fromA && !t.bits.mshrTask
    io_storeOrdinaryBusy := ordinary(task_s2) || ordinary(task_s3) || ordinary(task_s4) || ordinary(task_s5)
    val storeTask = task_s3.valid && req_s3.fromA && !req_s3.mshrTask &&
      req_s3.reqSource === Input2ReqPfSource.PrefetchAcquire && io_storePending &&
      req_s3.sourceId === io_storeSource && req_s3.tag === io_storeTag && req_s3.set === io_storeSet
    def snapshot(t: ValidIO[TaskBundle]): Bool = t.valid &&
      t.bits.tag === req_s3.tag && t.bits.set === req_s3.set &&
      (t.bits.fromB || t.bits.fromC || t.bits.mshrTask || t.bits.reqSource === Input2ReqPfSource.PrefetchRelease)
    val snapshotPending = snapshot(task_s2) || snapshot(task_s4) || snapshot(task_s5)
    val previousDSEn = RegNext(io.toDS.en_s3, false.B)
    // Hints do not reserve MCP2 in RequestArb, so a write needs an empty following s2 slot.
    val storeWrite = storeTask && resetFinish && dirResult_s3.hit && isT(meta_s3.state) &&
      !meta_s3.clients.orR && !io_storeBlock && !snapshotPending && !previousDSEn && !task_s2.valid
    val storeWriteNext = RegNext(storeWrite, false.B)
    val storeWriteHold2 = storeWrite || storeWriteNext
    val storeDataHeld = RegEnable(io_storeData, storeWrite)
    io_storeDone := storeTask
    io_storeFire := storeWrite
    val storeMeta = WireInit(meta_s3)
    storeMeta.dirty := true.B


  // 确认req_s3.param, toN, toB, PREFETCH_WRITE的值
    val metaW_s3_flush = Mux(
        task_s3.bits.param === PREFETCH_WRITE,
        MetaEntry(
            dirty = false.B,
            state = BRANCH,
            clients = meta_s3.clients,
            alias = meta_s3.alias,
            tagErr = meta_s3.tagErr,
            dataErr = meta_s3.dataErr
        ),
        MetaEntry()
    )

    val need_data_active_release = task_s3.valid && req_s3.fromA && dirResult_s3.hit && !req_s3.mshrTask &&
        !(meta_s3.state === BRANCH && task_s3.bits.param === ActiveReleaseParam.toB) &&
        task_s3.bits.reqSource === Input2ReqPfSource.PrefetchRelease
    val need_mshr_s3_active_release = need_data_active_release
    val need_mshr_s3_l1 = need_mshr_s3 || need_mshr_s3_active_release
    val active_release_hold2 = need_data_active_release || RegNext(need_data_active_release, false.B)
    io.toDS.en_s3 := task_s3.valid && (ren || wen || need_data_active_release) || storeWrite
    io.toDS.req_s3.valid := task_s3_valid_hold2(0) && (ren || wen || active_release_hold2) || storeWriteHold2
    io.toDS.req_s3.bits.wen := wen || storeWriteHold2
    when(storeWriteHold2) {
      io.toDS.wdata_s3.data := Mux(storeWrite, io_storeData, storeDataHeld)
    }

    val metaW_valid_s3_flush = need_data_active_release
    when(metaW_valid_s3_flush) {
        sink_resp_s3.bits.mshrId := io.fromMSHRCtl.mshr_alloc_ptr
        sink_resp_s3.bits.opcode := ReleaseData
        sink_resp_s3.bits.param  := Mux(task_s3.bits.param === ActiveReleaseParam.toN, 
            // param == 0(releaseToN) && state == T  -> TtoN
            // param == 0(releaseToN) && state == B  -> BtoN
            Mux(isT(meta_s3.state), TtoN, BtoN),
            // param == 1(releaseToB) && state == T  -> TtoB
            TtoB
        )
    }

    io.toMSHRCtl.mshr_alloc_s3.valid := task_s3.valid && !mshr_req_s3 && need_mshr_s3_l1
    when (need_mshr_s3_active_release) {
        // AcquireBlock is an internal ReleaseAck waiter sentinel; all A/Grant actions are already complete.
        ms_task.opcode := AcquireBlock
        alloc_state.elements.foreach(_._2 := true.B)
        alloc_state.w_releaseack := false.B
    }
    
    io.metaWReq.valid := !resetFinish || task_s3.valid && (metaW_valid_s3_a || metaW_valid_s3_b || metaW_valid_s3_c || metaW_valid_s3_mshr || metaW_valid_s3_flush || storeWrite)
    io.metaWReq.bits.wmeta := Mux(
        resetFinish,
        Mux(storeWrite, storeMeta, ParallelPriorityMux(
            Seq(metaW_valid_s3_a, metaW_valid_s3_b, metaW_valid_s3_c, metaW_valid_s3_mshr, metaW_valid_s3_flush),
            Seq(metaW_s3_a, metaW_s3_b, metaW_s3_c, metaW_s3_mshr, metaW_s3_flush)
        )),
        MetaEntry()
    )

    val pendingC_pf = task_s4.valid && RegNext(need_data_active_release)
    when(task_s4.valid && !req_drop_s4) {
        isC_s5 := isC_s4 || pendingC_s4 || pendingC_pf
    }
    io.status_vec_toC(1).valid := task_s4.valid && (isC_s4 || pendingC_s4 || pendingC_pf)

    // 让 active release 进 MSHR
    // 1. 修改 alloc 条件
    // 2. 在 alloc_state 中设置 mshr 状态机，仅设置 s_release 和 w_releaseAck 即可
    


}