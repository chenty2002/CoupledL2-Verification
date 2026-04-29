## Copilot Instructions: CoupledL2-Chisel6.6

Purpose: Help AI agents quickly generate / modify Chisel & Scala code, test tops, and verification infrastructure in this multi-repo (Rocket-Chip + HuanCun + CoupledL2) workspace.

Architecture Big Picture
1. Cache hierarchy compositions are built via Diplomacy: multiple L2 (or L1-as-L2) slices -> optional L3 (HuanCun) -> memory (`TLRAM`). See `VerifyTop.scala` for a compact 2x(L1-as-L2 + L2) -> L3 example and `coupledL2/src/test/scala/TestTop.scala` for standalone generation tops.
2. Two modes: TileLink-only (TL2TL) and TL-to-CHI (`chi/TestTop.scala`). Mode chosen by selecting different tops & enabling CHI slices (patch refactors slice construction with `createSlice`).
3. Local patch (`scripts/coupledL2.diff`) alters: lazy `Prefetcher` instantiation, directory tag SRAM selection (forces `SRAMTemplate`), and grant block condition refinement in `MainPipe`. Always apply before building (root `make init`). Reverse with `scripts/reverse_coupledL2.sh` if needed.
4. Verification layer (`CoupledL2Verification` mill module) emits formal-oriented SV via `VerifyTop.scala` / `AutoVerify.scala`; Python helper `set_verify.py` rewrites source/dest filenames for variant emission.

Key Directories / Files
`build.sc` (module graph) | root `Makefile` (verification flow) | `coupledL2/Makefile` (RTL top emission) | `scripts/modify_coupledL2.sh` & `coupledL2.diff` (must-run patch) | `src/test/scala/coupledL2Verification/` (formal tops) | `src/main/scala/coupledL2AsL1/` (L1-as-L2 experiment) | `coupledL2/src/test/scala/chi/` (CHI tops).

Essential Commands
Root init + verify: `make init && make compile && make verify` (SV -> `build/VerifyTop.sv`).
Auto variant: `make auto` (emits `VerifyTop_<suffix>.sv` via `AutoVerify`).
Emit TL test top: `cd coupledL2 && make init && make test-top-l2`.
Other TL variants: `test-top-l2standalone`, `test-top-l2l3`, `test-top-l2l3l2`, `test-top-fullsys`.
Emit CHI top (example quad core, 2 UL ports): `cd coupledL2 && make test-top-chi-quadcore-2ul NUM_SLICE=4 ISSUE=B`.

Parameter / Pattern Conventions
Use `p.alterPartial` to inject per-slice params (`EdgeInKey`, `EdgeOutKey`, `BankBitsKey`, `SliceIdKey`). Patch encapsulates slice creation helper to reduce duplication.
Enable logging & DB via env: `WITH_CHISELDB`, `WITH_TLLOG`, `WITH_CHILOG`, `FPGA` (propagated into CLI args). TL log wrappers use `TLLogger` around buffers/xbars.
Formal constraints & assertions use `BoringUtils.bore` + `fvAssert` (example: C channel rate limit in `VerifyTop.scala`). Preserve these when modifying pipeline logic.

Generated Artifacts
Verification SV: `build/` (root). RTL tops: `coupledL2/build/` (split-verilog plus `<Top>.sv.conf` used by `scripts/gen_sep_mem.sh` + `scripts/vlsi_mem_gen`). Keep `.conf` integrity when altering memory generators.

Prefetcher (CoupledL2AsL1 Context)
Enabled in `CoupledL2AsL1` configs via `L2Param(prefetch = Seq(InputAsPrefectchParam(), ...))`. Patch makes `prefetcher` lazy (avoids construction when feature disabled, stabilizes elaboration ordering). Composition chain (priority mux order): Receiver (L1-driven) > VBOP > PBOP > Temporal (TP). Each produces `PrefetchReq` into `PrefetchQueue` (always-ready, discards oldest on overflow) -> 1-stage `Pipeline` -> Slice SinkA. Key bundle fields for assertions: `pfSource` (classifies BOP/PBOP/SMS/TP/L1), `needT` (requesting T permission), `source` (TL sourceId). Training path (`PrefetchTrain`) carries `hit` & `prefetched` for feedback gating pattern detection. For formal, typical properties: (a) mutual exclusion selection metrics (only highest-priority valid enters queue), (b) no starvation of lower priority when higher absent, (c) bounded queue occupancy (`prefetch_queue_entry` histogram) + eventual dequeue when `io.req.ready`. Logging tables (`L2PrefetchTrainTable`, `L2PrefetchPrefetchTable`) only enabled when DB flags set—preserve names if extending to keep downstream parsers stable. When adding a new algorithm, insert ahead of TP only if it must dominate temporal streams; otherwise append with new perf counters mirroring existing `prefetch_req_select*` signals.

Typical Extension Tasks
Adding a new top: mirror patterns in `TestTop.scala`, wrap with `DisableMonitors(...)` + config alteration; remember to include memory gen script invocation if using Makefile.
Modifying slice behavior: update corresponding file under `coupledL2/src/main/scala/...` and consider whether patch diff needs extension (then update `coupledL2.diff`).

Ambiguity Note
Root `README.md` references `make test-top-l3` (no such target). Likely intended `test-top-l2l3`. Until README fixed, use `make test-top-l2l3` inside `coupledL2/`.

Safety / Gotchas
ALWAYS run `make init` after submodule updates; missing patch causes divergent behavior (prefetcher eager instantiation & different tag SRAM). If build anomalies appear, re-run init or apply `scripts/modify_coupledL2.sh` manually.
Do not remove `--split-verilog` or memory script steps; downstream tooling expects separated memories.

Feedback Wanted
If additional guidance is needed on CHI parameterization, memory macro customization, or formal harness signals, note it here for expansion.