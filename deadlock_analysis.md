# XiangShan-CoupledL2-deadlock-v2 死锁分析

## 各 MSHR 的最终状态

| MSHR | channel | opcode | param | set | tag | dirHit | dirTag | way | s_* | w_* |
|------|---------|--------|-------|-----|-----|--------|--------|-----|----------|-----------|
| L1_0.mshrs_0 | A | AcquireBlock | NtoB | - | - | 0 (Miss) | - | 1 |  | **w_replResp** |
| L1_0.mshrs_1 | A | AcquireBlock | NtoT | - | - | 1 (Hit)  | - | 1 | s_refill | w_grantlast |
| L1_0.mshrs_2 | A | AcquireBlock | NtoT | - | - | 1 (Hit)  | - | 0 | s_refill | w_grantlast |
| L1_1.mshrs_0 | A | AcquireBlock | NtoT | - | - | 0 | - | 1 | s_refill | w_grantlast, **w_replResp** |
| L1_1.mshrs_1 | A | AcquireBlock | NtoB | - | - | 0 | - | 1 | s_refill | w_grantlast, **w_replResp** |
| L1_1.mshrs_2 | A | AcquireBlock | NtoT | - | - | 0 | - | 0 | s_refill | w_grantlast, **w_replResp** |
| **L2_0.mshrs_0** | A | AcquireBlock | NtoT | 10 | 01 | 1 | 01 | 1 | s_refill | w_grantlast |
| **L2_0.mshrs_1** | **B (Probe)** | ProbeBlock | toB | 10 | 10 | 1 | 10 | 0 |  | **w_pprobeackfirst** |
| L2_0.mshrs_2 | A | AcquireBlock | NtoT | 00 | 10 | 1 | 10 | 1 | s_refill | w_grantlast |
| L2_1.mshrs_0 | A | AcquireBlock | NtoB | 01 | 11 | 1 | 11 | 0 | s_refill | w_grantlast |
| L2_1.mshrs_1 | A | AcquireBlock | NtoT | 10 | 10 | 0 | 00 | 0 | s_refill | w_grantlast, **w_replResp** |
| L2_1.mshrs_2 | A | AcquireBlock | NtoT | 11 | 00 | 0 | 11 | 0 | s_refill | w_grantlast, **w_replResp** |

（`state_*` = 1 代表"已完成/已发送"；= 0 代表"仍待处理"。）

## MSHR.scala 信号分析

`MSHR.scala` 的 FSM：

- **调度位 `state.s_*`**：`s_acquire / s_rprobe / s_pprobe / s_probeack / s_refill / s_release / s_retry`。`s_* = 0` 表示"该任务还需要调度发送"；`s_* = 1` 表示"已发送或无需发送"。
- **等待位 `state.w_*`**：`w_rprobeackfirst/last`、`w_pprobeackfirst/last/ack`、`w_grantfirst/last/grant`、`w_releaseack`、`w_replResp`。`w_* = 0` 表示"还在等某个响应/结果"；`w_* = 1` 表示"已收到"。

MSHR 分配时，根据请求类型把对应的 `s_*/w_*` 位复位为 0；MSHR 的 `req_valid` 要最终被清掉，一个 stalled MSHR，必然有且仅有少数几个 `s_*/w_*` 停在 0。

---

### `w_replResp = 0` — 被 replacer 卡住

**代表 MSHR**：`L1_0.mshrs_0`（共收到 257 次 replResp.retry），以及 `L1_1.mshrs_0/1/2`、`L2_1.mshrs_1/2`。

**排查顺序**：

1. **`io_msInfo_bits_*` / `state_*` 确认停滞位**
   波形：`mshrs_i.state_w_replResp = 0`、`state_s_release = 1`、`state_s_refill = 0`、`req_valid = 1`。

2. **MSHR.scala 里 `w_replResp` 赋值位置**

   ```scala
   when (io.replResp.valid && replResp.retry) {
     state.s_refill := false.B
     state.s_retry  := false.B
     dirResult.way  := replResp.way            // ← 每次 retry 把 way 覆盖成 replResp.way
   }
   when (io.replResp.valid && !replResp.retry) {
     state.w_replResp := true.B                // ← 只有非-retry 才能置 1
     dirResult.tag := replResp.tag
     dirResult.way := replResp.way
     dirResult.meta := replResp.meta
     ...
   }
   ```

3. **波形 `io_replResp_valid / io_replResp_bits_retry / io_replResp_bits_way` 时序**
   每 4 拍一次 `replResp_valid=1`，`retry` 恒为 1，`way` 在 `0/1` 间交替，replacer 不停 retry。

4. **`replResp` 在 `Directory.scala` 里替换**

   ```scala
   val wayConflictMask = VecInit(io.msInfo.map(s =>
     s.valid && s.bits.set === req_s3.set &&
     (s.bits.blockRefill || s.bits.dirHit) &&
     s.bits.way === finalWay
   )).asUInt
   val refillRetry = wayConflictMask.orR
   io.replResp.bits.way   := finalWay
   io.replResp.bits.retry := refillRetry
   val updateRefill = refillReqValid_s3 && !refillRetry   // retry 时 replacer 状态不更新
   ```

   **检查其他事务信号**：同 `set` 的其它 `mshrs_j.io_msInfo_bits_set / _way / _dirHit / _blockRefill`。如果两个 way 上都有 `msInfo.valid && (dirHit || blockRefill)`，就会命中 `wayConflictMask`，`refillRetry=1` 恒成立。

5. **MSHR.scla 的 `blockRefill / dirHit` 信号**

   ```scala
   val releaseNotSent = !state.s_release
   io.msInfo.bits.blockRefill :=
     releaseNotSent || RegNext(releaseNotSent,false.B) || RegNext(RegNext(releaseNotSent,false.B),false.B)
   io.msInfo.bits.dirHit      := dirResult.hit
   ```

   `state_s_release=1` 已完成，`blockRefill=0`；但 `dirHit=1`（同 set 同 way 上的另一 hit MSHR 仍未 free），于是 wayConflictMask 仍然命中。

结论：`w_replResp=0` ← `io.replResp.bits.retry=1` ← `Directory.wayConflictMask.orR=1` ← 同 set 两个 way 都被正活跃的 MSHR 以 `dirHit` 标住。

---

### `w_pprobeackfirst = 0` — 未收到到 ProbeAck

**代表 MSHR**：`L2_0.mshrs_1`（`req_channel=010/B`, `set=10`, `dirTag=10`, `dirHit=1`, `way=0`）。

**排查顺序**：

1. **调度位/等待位**：`state_s_pprobe = 1`（Probe 已发出）、`state_w_pprobeackfirst = 0`、`state_s_probeack = 0`（还没给 L3 回 ProbeAck）。

   ```scala
   val mp_probeack_valid = !state.s_probeack && state.w_pprobeacklast
   ```

   MSHR 想下一步发 ProbeAck，必须先收齐 L1 的 ProbeAck。

2. **`w_pprobeack*` 的赋值位置**：

   ```scala
   when (c_resp.valid) {
     when (c_resp.bits.opcode === ProbeAck || c_resp.bits.opcode === ProbeAckData) {
       state.w_rprobeackfirst := true.B
       state.w_rprobeacklast  := state.w_rprobeacklast || c_resp.bits.last
       state.w_pprobeackfirst := true.B
       state.w_pprobeacklast  := state.w_pprobeacklast || c_resp.bits.last
       state.w_pprobeack      := state.w_pprobeack || req.off === 0.U || c_resp.bits.last
     }
   }
   ```

   要置 1，必须 `io.resps.sink_c.valid=1` 且 opcode 是 ProbeAck/ProbeAckData。

3. **波形检查**：`L2_0.mshrs_1.io_resps_sink_c_valid` 在整个 MSHR 生命周期里从未为 1，说明 L1 没回 ProbeAck。

4. **检查L1**：L1 没回 ProbeAck 的原因是 L1 的 SinkB 没收到这个 Probe。

    L1 侧的 Probe 由 L2 `source_b` 发出，`L2_0.mshrs_1.io_tasks_source_b_valid` 在 Probe 发出后 `state.s_pprobe=1`。Probe 发到 L1 后，要进入 L1 的 `RequestArb`，需要通过：

   - `RequestArb.scala:` `block_B = blockB_s1(MSHR) || blockB_s1(MainPipe) || blockB_s1(GrantBuffer)`
   - `MSHRCtl.scala:` `io.toReqArb.blockB_s1 := mshrFull`
   - `MainPipe.scala:`：

     ```scala
     io.toReqArb.blockB_s1 :=
       task_s2.valid && bBlock(task_s2.bits) ||
       task_s3.valid && bBlock(task_s3.bits) ||
       task_s4.valid && bBlock(task_s4.bits, tag = true) ||
       task_s5.valid && bBlock(task_s5.bits, tag = true)
     ```

     其中 `bBlock` 是"流水线里正在处理同 set 的 task"。

   - `SinkB.scala:` 再过 `addrConflict`（同 `set + reqTag`、MSHR `willFree=0` 且不允许 nestB）和 `replaceConflict`（同 `set + metaTag` 且 `blockRefill`）。

5. **波形检查**：L1 的 `mshrCtl.io_mshrFull`、L1 的 `mainPipe.io_toReqArb_blockB_s1`、L1 的 `sinkB.io_task_valid / io_b_ready`。这些信号长期为 0，说明无法接收 Probe。

   同时看 L1 侧所有 `mshrs_i.io_msInfo_bits_set` 和 `_reqTag`，若其中有 MSHR `set === L2.mshrs_1.set` 且 `reqTag === L2.mshrs_1.tag`、且 `willFree=0` 且 `nestB=0`，就正好命中 `addrConflict`，Probe 被拒。

结论：`w_pprobeackfirst=0` ← L1 `sink_c_valid` 未拉高 ← L1 `SinkB.addrConflict || replaceConflict` 恒为 1 ← L1 的 MSHR 因替换未完成导致 MSHR 满 + 同地址的 MSHR 未释放。

---

### `w_grantlast = 0` — 未收到 Grant

**代表 MSHR**：`L2_0.mshrs_0`（`req_channel=A`, `set=10`, `tag=01`, `dirHit=1`, `way=1`, `param=NtoT`, `dirResult_meta_state=BRANCH`）。

**排查顺序**：

1. **调度位/等待位**：`state_s_acquire = 1`（Acquire 已发给 L3）、`state_w_grantlast = 0`、`state_w_grant = 0`、`state_w_replResp = 1`（命中无需替换或已完成）。

   ```scala
   io.tasks.source_a.valid := !state.s_acquire
   ...
   val mp_grant_valid = !state.s_refill && state.w_grantlast && state.w_rprobeacklast
   ```

   `s_refill=0 && w_grantlast=0`：Acquire 已发，正在等 Grant，发不出 refill。

2. **`w_grantlast` 赋值位置**

   ```scala
   when (d_resp.valid) {
     when(d_resp.bits.opcode === Grant || d_resp.bits.opcode === GrantData ||
          d_resp.bits.opcode === AccessAck) {
       state.w_grantfirst := true.B
       state.w_grantlast  := d_resp.bits.last
       state.w_grant      := req.off === 0.U || d_resp.bits.last
     }
     ...
   }
   ```

   要置 1，必须 `io.resps.sink_d.valid=1` 且 opcode 是 Grant/GrantData 且 `last=1`。

3. **波形检查**：`L2_0.mshrs_0.io_resps_sink_d_valid` 未拉高 → L3 未回 Grant。

4. **检查L3**：L3 正在给 L2 发 Probe（对应 L2_0.mshrs_1），但 Probe 永远不完成，L3 因此阻塞后续 Grant。

5. **比对同 set MSHR**：`L2_0.mshrs_0.set=10 tag=01 way=1` 与 `L2_0.mshrs_1.set=10 tag=10 way=0` 同 set、不同 way、不同 tag，L3 中正在将 `L2_0.mshrs_0` 的 Acquire 替换 `mshrs_1` 的 Probe。

结论：`w_grantlast=0` ← L3 未回 Grant ← L3 自身在等 L2 回 ProbeAck。

---

### 死锁环

```
L1 MSHR (w_replResp=0)  需要 Directory 的 replResp      
  │   因为同 set 其他 MSHR 的 way 都被 hold，Directory 每次都 retry 
  ▼                                                                
L1 无法 release；同 set 的任何 A / 下行 B 都被 SinkB 拒收         
  │                                                                
  ▼                                                                
L2.mshrs_1 (Probe from L3) 收不到 L1 ProbeAck                 
  │  w_pprobeackfirst=0                                        
  ▼                                                                
L2 不能 mp_probeack → 无法给 L3 回 ProbeAck                    
  │                                                                
  ▼                                                                
L3 无法完成 Probe → 不会给 L2.mshrs_0 发 Grant                
  │                                                                
  ▼                                                                
L2.mshrs_0 w_grantlast=0 → 无法 mp_refill 回 L1                    
  │                                                                
  ▼                                                                
L1.mshrs_* 等不到 Grant / 数据，同 set 的 way 被占用
```

## 根因

**`Directory.scala` 的替换重试逻辑**：

```scala
val wayConflictMask = VecInit(io.msInfo.map(s =>
  s.valid && s.bits.set === req_s3.set && (s.bits.blockRefill || s.bits.dirHit) && s.bits.way === finalWay
)).asUInt
val refillRetry = wayConflictMask.orR
io.replResp.bits.way   := finalWay        
io.replResp.bits.retry := refillRetry
val updateRefill = refillReqValid_s3 && !refillRetry   // retry 时不更新 replacer 状态
```

- 当同 set 的所有 way 都被其它 MSHR 暂时占住（`dirHit=1` 或 `blockRefill=1`），replacer 输出的 `finalWay` 每次都落在 conflict 集合里；
- `updateRefill` 在 retry 时不生效，PLRU 的状态保持不变，下一次 `finalWay` 仍然相同；
- `refillRetry` 恒为 1，MSHR 的 `w_replResp` 无法置位，形成死锁。

