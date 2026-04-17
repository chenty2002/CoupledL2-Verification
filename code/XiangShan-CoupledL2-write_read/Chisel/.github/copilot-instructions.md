# CoupledL2 data-consistency – AI onboarding

## Architecture snapshot
- `src/main/scala/coupledL2AsL1/` wraps the upstream CoupledL2 cache so it can impersonate an L1: wrappers inject `Input2Req` as the prefetcher, tweak `Slice` instantiation, and expose IOs (`io_inputAddr`, `io_inputNeedT`) that drive formal scenarios.
- `src/test/scala/coupledL2Verification/VerifyTop.scala` is the system harness: two L1-wrapped CoupledL2s feed a shared `HuanCun` L3, tie off debug ports, and bore TileLink channels for assertions. This is the top most generators target.
- `chiselFv/` supplies reusable formal helpers (`Formal`, `fvAssert`, `ResetCounter`) relied on throughout `VerifyTop` – keep assertions wrapped with these utilities so they respect the not-chaos window.
- The repo embeds upstream XiangShan modules under `coupledL2/`, `coupledL2/rocket-chip/`, `.../HuanCun/`, etc.; direct edits there should generally flow through `scripts/coupledL2.diff` so `make init` can reapply local patches.

## Key forks from upstream
- `scripts/coupledL2.diff` introduces `L2AddrKey` echo fields, lazily-instantiated `Prefetcher`/`SourceC`, and extra block conditions. Any change touching TileLink echo metadata must maintain these hooks because `VerifyTop` watches the echoes to track Grant data origins.
- The `Input2Req` prefetcher treats formal inputs as serialized TL requests; maintain its always-valid handshake and dual-source increment pattern (even/odd per hart) when extending tests.

## Build & generate flow
- Run `make init` once per clone to fetch submodules (`coupledL2`, `rocket-chip`, `HuanCun`, `utility`) and apply the local patch script.
- Mill (version in `.mill-version`) drives everything: `make compile` → `mill -i CoupledL2Verification.compile` compiles Scala/Chisel; `make verify` runs `VerifyTop`; `make auto` calls `AutoVerify` which regenerates SystemVerilog, rewrites `set_verify.py`, and copies artifacts to the downstream verification directory.
- Generated SV lives under `Verilog/VerifyTop.sv`; `AutoVerify` then post-processes it via `set_verify.py` to strip `.site` and comment unneeded assertions before exporting `VerifyTop_<suffix>.sv`.

## Working with the harness
- `VerifyTop` assumes two L2s (`nrL2 = 2`) and a 1-byte beat width to keep the state space small. If you scale cores or beats, adjust the hard-coded `blockBytes`, counters, and BoringUtils taps accordingly.
- Assertions rely on TileLink echo fields and `data_p1/p2` shadow registers. When adding checks, thread new metadata through `GrantBuffer.toTLBundleD` and `SourceC.toTLBundleC` so downstream monitors can observe it.
- The RAM behind `TLRAM(AddressSet(0, 0x1fL), beatBytes = 1)` defines the verification window. Expanding address coverage means revisiting `fvAssert` constraints and the post-processing script that filters asserts near `match_tag`.

## Coding conventions & tips
- Use `LazyModule` plumbing from Rocket Chip diplomacy; new nodes should mirror the existing `createClientNode` helper to keep alias field wiring consistent.
- Tie off unused diplomacy IOs explicitly (`DontCare`) like in `VerifyTop` to avoid FIRRTL warnings – the `set_verify.py` script assumes no extra monitors.
- When touching submodules, prefer `lazy val` instantiation (see `Slice`/`Prefetcher` changes) to avoid eager module creation that breaks parameter overrides.
- Keep prints (`println`) in `Input2Req` guarded or remove once stable; they execute at elaboration time and clutter CI logs.

## Test & lint hygiene
- Scala tests live in `src/test/scala/coupledL2Verification/`; add new formal harnesses beside `VerifyTop` and expose them through Mill `runMain` targets for easy automation.
- Formatting uses `.scalafmt.conf`; run `mill -i __.checkFormat` or the `reformat` target before committing.

## Hand-off reminders
- If you need to update upstream IP, adjust `scripts/coupledL2.diff` and rerun `make init` to validate the patch applies cleanly.
- Keep generated SV artifacts out of source control unless they are the intended deliverable; `AutoVerify` will rehydrate them.