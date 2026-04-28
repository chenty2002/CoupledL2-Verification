# CoupledL2 Verification

> Artifacts for the paper:
> **Protocol-Independent Bug-Hunting for Industrial Non-Blocking Cache Hierarchies in an Agile Chisel Framework**

---

## Results

This repository corresponds to the paper's counterexample-first bug-hunting workflow and keeps the case-study artifacts in a directly reproducible layout.

### Reported Highlights

1. On XiangShan CoupledL2, the campaign triggers 12 actionable counterexamples under the configured budget.
2. Triggered failures span progress stall/deadlock, data consistency, and protocol-state legality categories.
3. The same workflow is transferred to RocketChip InclusiveCache with mechanical adaptation at the harness/property integration layer.

The repository datasets (cause notes, traces, waveforms, and assertion locations) are structured to support replay, diagnosis, and fix validation across the two case studies.

**Contents**

- [CoupledL2 Verification](#coupledl2-verification)
  - [Results](#results)
    - [Reported Highlights](#reported-highlights)
  - [Overview](#overview)
    - [Current Snapshot Organization](#current-snapshot-organization)
  - [Repository Structure](#repository-structure)
  - [Requirements](#requirements)
    - [Recommended Toolchain](#recommended-toolchain)
    - [TL-Test Toolchain Additions](#tl-test-toolchain-additions)
    - [Root Makefile Checks](#root-makefile-checks)
  - [Quick Start](#quick-start)
    - [Index Mapping](#index-mapping)
    - [Common Commands](#common-commands)
    - [Ablation Commands](#ablation-commands)
  - [Ablation Study Mapping](#ablation-study-mapping)
    - [Paper-to-Code Correspondence](#paper-to-code-correspondence)
  - [Experimental Parameter Reduction](#experimental-parameter-reduction)
    - [Paper Parameter Reduction Table](#paper-parameter-reduction-table)
    - [Code Location Mapping](#code-location-mapping)
  - [Dataset](#dataset)
    - [Critical Errors](#critical-errors)

---

## Overview

This repository contains two case studies:

1. XiangShan CoupledL2-based hierarchy.
2. RocketChip InclusiveCache-based hierarchy.

| Item | Description |
| --- | --- |
| Workflow Stage 1 | Bug-hunting-ready DUV construction |
| Workflow Stage 2 | Counterexample-first model checking |
| Property Categories | Progress stall/deadlock freeness, data consistency, inclusion-policy conformance, protocol-state legality |

### Current Snapshot Organization

1. Deadlock-focused cases are organized as deadlock-v0 to deadlock-v4.
2. Consistency-focused cases are organized as copy_equality and write_read.
3. Protocol-state-legality-focused case is organized as peer-l2.
4. Inclusion-policy conformance is covered in properties but does not have a standalone case directory in this snapshot.

---

## Repository Structure

```text
.
|- code
|  |- RocketChip-InclusiveCache
|  |  |- Chisel
|  |  |- Verilog
|  |  `- inclusivecache-verification
|  |- XiangShan-CoupledL2-copy_equality
|  |  |- Chisel
|  |  |- Verilog
|  |  |- cause.txt
|  |  `- XiangShan-CoupledL2-copy_equality.fst
|  |- XiangShan-CoupledL2-write_read
|  |  |- Chisel
|  |  |- Verilog
|  |  |- cause.txt
|  |  `- XiangShan-CoupledL2-write_read-1017.fst
|  |- XiangShan-CoupledL2-peer-l2
|  |  |- Chisel
|  |  |- Verilog
|  |  |- cause.txt
|  |  `- XiangShan-CoupledL2-peer-l2.fst
|  |- XiangShan-CoupledL2-Native-L1
|  |  |- Chisel
|  |  `- Verilog
|  `- XiangShan-CoupledL2-TL-Test
|     |- configs
|     |- dut
|     |- main
|     `- scripts
|  |- XiangShan-CoupledL2-deadlock-v0
|  |  |- Chisel
|  |  |- Verilog
|  |  |- cause.txt
|  |  `- XiangShan-CoupledL2-deadlock-v0.fst
|  |- XiangShan-CoupledL2-deadlock-v1
|  |- XiangShan-CoupledL2-deadlock-v2
|  |- XiangShan-CoupledL2-deadlock-v3
|  `- XiangShan-CoupledL2-deadlock-v4
|- figures
|  |- deadlock-1.png
|  |- deadlock-2.png
|  |- deadlock-3.png
|  |- TileLink_state_coherence.png
|  `- consistency.png
|- Makefile
`- README.md
```

Notes:
Each XiangShan version directory is self-contained with Chisel, Verilog, and an FST trace.

---

## Requirements

### Recommended Toolchain

| Tool | Version | Purpose |
| --- | --- | --- |
| Java | 8 | Build/runtime dependency |
| Scala | 2.13.x | Chisel/verification codebase |
| mill | 0.11.1 | XiangShan variant builds |
| sbt | latest stable | InclusiveCache build flow |
| Python | 3.x | Root scripts, preprocessing, and staging utilities |
| JasperGold (jg) | installed and in PATH | Formal runs |

### TL-Test Toolchain Additions

The TL-Test ablation (`code/XiangShan-CoupledL2-TL-Test`) needs a larger toolchain than the formal-only flow:

| Tool | Purpose |
| --- | --- |
| python / python3 | TL-Test helper scripts and staged verification harness processing |
| mill | Build the CoupledL2-side DUT used by TL-Test |
| cmake | Configure the TL-Test host build |
| verilator | Build the Verilator-host executable |
| C++17 compiler (`g++` or `clang++`) | Compile the TL-Test host and generated Verilator code |
| sqlite3 development library | Linked by the TL-Test host build (`-lsqlite3`) |

The root `make tltest ...` entry checks for `python`, `mill`, `verilator`, and `cmake`. If your Verilator install lives in a non-default location, TL-Test also supports `VERILATOR_INCLUDE`, `CXX_COMPILER`, and `SQLITE3_ROOT`; see `code/XiangShan-CoupledL2-TL-Test/Makefile`.

### Root Makefile Checks

1. Compile stage: java and python are required.
2. XiangShan variants (index 0-7): mill required before compile.
3. InclusiveCache (index 8): sbt required before compile.
4. Verification stage (`setup.sh`): jg required before formal run.
5. TL-Test ablation (`make tltest ...`): python, mill, verilator, cmake required before build.
6. Native-L1 ablation (`make native-l1`): java, python, mill, jg required.

---

## Quick Start

The root Makefile provides one-click dispatch:

```bash
make help
make verify <index>
```

### Index Mapping

| Index | Case Directory |
| ---: | --- |
| 0 | XiangShan-CoupledL2-copy_equality |
| 1 | XiangShan-CoupledL2-write_read |
| 2 | XiangShan-CoupledL2-deadlock-v0 |
| 3 | XiangShan-CoupledL2-deadlock-v1 |
| 4 | XiangShan-CoupledL2-deadlock-v2 |
| 5 | XiangShan-CoupledL2-deadlock-v3 |
| 6 | XiangShan-CoupledL2-deadlock-v4 |
| 7 | XiangShan-CoupledL2-peer-l2 |
| 8 | RocketChip-InclusiveCache |

### Common Commands

Run one XiangShan case:

```bash
make verify 0
```

Run InclusiveCache case:

```bash
make verify 8
```

Direct local flow example:

```bash
cd code/XiangShan-CoupledL2-write_read/Chisel
make auto
cd ../Verilog
./setup.sh VerifyTop*.sv
```

Override verification mode example:

```bash
make verify 1 VERIFY_MODE=large
```

### Ablation Commands

The paper compares the full workflow against several ablation variants. The root `Makefile` exposes the runnable ones directly:

```bash
# baseline simulation ablation (TL-Test)
make tltest 2

# w/o parameter reduction
make verify 1 VERIFY_MODE=large

# w/o bounded liveness as safety primitive
make verify 2 VERIFY_ABLATION=wo-bounded-liveness

# w/o Simplified L1
make native-l1
```

For the Native-L1 flow, the default verified top is `../Chisel/VerifyTop_all.sv`. You can override it if needed:

```bash
make native-l1 NATIVE_L1_TOP=../Chisel/VerifyTop_all.sv
```

---

## Ablation Study Mapping

Section 6 of `main.tex` evaluates the full XiangShan workflow against five comparison configurations. The repository mapping is:

### Paper-to-Code Correspondence

| Paper configuration | Repository support | How to run / inspect | Notes |
| --- | --- | --- | --- |
| `our workflow` | Main XiangShan cases under `code/XiangShan-CoupledL2-*` | `make verify <index>` | Uses Simplified L1, reduced parameters, synchronization modules, and bounded liveness checks. |
| `baseline (TL-Test)` | `code/XiangShan-CoupledL2-TL-Test` | `make tltest <case-or-index>` | Simulation-only comparison under the same case selection. |
| `w/o parameter reduction` | Same XiangShan case directories | `make verify <index> VERIFY_MODE=large` | `VERIFY_MODE=large` switches from the reduced verification profile back to the development-scale parameter setting described in the paper. |
| `w/o bounded liveness as safety primitive` | Deadlock cases plus `code/preprocess_sva.py` | `make verify <deadlock-index> VERIFY_ABLATION=wo-bounded-liveness` | `preprocess_sva.py` rewrites generated bounded timer assertions into unbounded SVA eventuality checks for this ablation. |
| `w/o Simplified L1` | `code/XiangShan-CoupledL2-Native-L1` | `make native-l1` | Replaces the paper's Simplified L1 boundary model with a `NativeL1` package derived from XiangShan's original L1-side behavior. |
| `w/o synchronization modules` | No standalone runnable target in this snapshot | Text-only description | This removal disables the auxiliary synchronized observation mirrors, so state/data-aware checks become uncheckable even though progress checks still conceptually remain. |

About the code layout for these ablations:

1. `code/XiangShan-CoupledL2-TL-Test` is the simulation baseline used for the TL-Test comparison in `main.tex`.
2. `VERIFY_MODE=small|large` is the switch used to move between the reduced formal profile and the development-value profile for the parameter-reduction ablation.
3. `code/preprocess_sva.py` implements the bounded-liveness removal by post-processing generated Verilog assertions in the deadlock cases.
4. `code/XiangShan-CoupledL2-Native-L1` contains the Native-L1 harness used for the `w/o Simplified L1` comparison.
5. The `w/o Sync Modules` row is documented for correspondence with the paper, but no separate runnable artifact is provided here because the removal intentionally breaks the observability support needed by the relevant properties.

---

## Experimental Parameter Reduction

### Paper Parameter Reduction Table

The full development-vs-verification parameter values used in the paper are recorded in `tables/parameters.tex`.

| Parameter | Cache Component | Development Value | Verification Value |
| --- | --- | ---: | ---: |
| ways | L2 Cache | 8 | 2 |
| ways | L3 Cache | 16 | 2 |
| sets | L2 Cache | 512 | 4 |
| sets | L3 Cache | 4096 | 4 |
| banks | L2/L3 Cache | 4 | 1 |
| blockBytes | L2/L3 Cache | 64 | 2 |
| busWidth | L2/L3 Cache | 256 | 8 |
| mshrs | L2 Cache | 16 | 4 |
| mshrs | L3 Cache | 16 | 6 |
| address | L1/L2/L3/RAM | 24 bits | 5 bits |

### Code Location Mapping

The repository code stores these reductions as harness-level knobs (`if (useLarge) ... else ...`) and explicit bank/address settings in each XiangShan case. Representative locations are listed below.

| Parameter | Representative code location(s) | How it is encoded |
| --- | --- | --- |
| ways / sets / blockBytes / mshrs (L2/L3) | `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala` | `L2Param(...)` and `HCCacheParameters(...)` use `if (useLarge) ... else ...`, where the `else` branch is the reduced verification profile. |
| ways / sets / blockBytes | `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala` | `MessageGeneratorParam(...)` uses `if (useLarge) ... else ...` to reduce request-space complexity for formal runs. |
| banks | `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala` and `code/XiangShan-CoupledL2-copy_equality/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala` | `case huancun.BankBitsKey => 0` enforces a single-bank setting for verification. |
| busWidth | `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala`, `code/XiangShan-CoupledL2-write_read/Chisel/src/main/scala/coupledL2/tl2tl/TL2TLCoupledL2.scala` | Reduced bus width is reflected via `TLChannelBeatBytes(if (useLarge) 32 else 1)` and `beatBytes = (if env VERIFY_MODE=large then 32 else 1)`. |
| address bits | `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala` | `TLRAM(AddressSet(0, if (useLarge) 0xffffffL else 0x1fL), ...)` corresponds to 24-bit vs 5-bit address space. |
| size mode switch | `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala`, `code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/AutoVerify.scala` | `VERIFY_MODE` selects small/large. |

---

## Dataset

### Critical Errors

| Case Directory | Suggested Critical Error Name | Bug Category | Cause Summary |
| --- | --- | --- | --- |
| code/XiangShan-CoupledL2-deadlock-v0 | Deadlock Freeness - Probe Starvation | Progress stall/deadlock freeness | Continuous same-address prefetch blocks Probe admission; circular wait forms. |
| code/XiangShan-CoupledL2-deadlock-v1 | Deadlock Freeness - Replacement Conflict I | Progress stall/deadlock freeness | Same-set X/Y interaction plus replacement and Probe interlock leads to deadlock. |
| code/XiangShan-CoupledL2-deadlock-v2 | Deadlock Freeness - Replacement Conflict II | Progress stall/deadlock freeness | Same root cause family as v1 and v2, reproduced in another version point. |
| code/XiangShan-CoupledL2-deadlock-v3 | Deadlock Freeness - High Same-Set Contention | Progress stall/deadlock freeness | Too many same-set lines saturate ways; replacement and Probe dependency deadlocks. |
| code/XiangShan-CoupledL2-deadlock-v4 | Deadlock Freeness - Bounded-Latency Mismatch | Progress stall/deadlock freeness | HuanCun parallelism bottleneck cannot satisfy a 200-cycle completion budget. |
| code/XiangShan-CoupledL2-peer-l2 | Protocol-State Legality - Peer L2 Tip-Branch Conflict | Protocol-state legality | Probe may be accepted before ReleaseAck ordering is fully respected, creating illegal peer state combination. |
| code/XiangShan-CoupledL2-copy_equality | Data Consistency - Copy Equality Update Race | Data consistency | Near-simultaneous ProbeAck and ReleaseData causes dirty data update race. |
| code/XiangShan-CoupledL2-write_read | Data Consistency - Write-Read Divergence | Data consistency | Concurrent Acquire/Release ordering conflict returns stale memory value. |

Relevant files:

Per-case full traces: each case directory contains XiangShan-CoupledL2-*.fst.

<details>
<summary><strong>Assertion Catalog (Root VerifyTop Sample)</strong></summary>

<br>

The root VerifyTop.scala is kept as the complete XiangShan-side assertion reference sample.

| Root assertion function or primitive | Bug scope in paper | Meaning |
| --- | --- | --- |
| l2_mutual / l2_mutual_with_invalid | Protocol-state legality | Forbid illegal state combinations on the same line across peer L2 slices. |
| l1_l2_mutual / l1_l2_mutual_with_invalid | Protocol-state legality + inclusion constraints | Forbid impossible L1-L2 state pairings for the same line. |
| l2_l3_mutual / l2_l3_mutual_with_invalid | Protocol-state legality | Forbid impossible L2-L3 state pairings for the same line. |
| l1l2_inclusive | Inclusion-policy conformance | If a line is valid in L1, L2 must contain the line or be covered by in-flight patch conditions. |
| l2_consistency | Data consistency | If two L2 slices hold the same line in BRANCH state, data must be equal. |
| astRelaxedLiveness (sample snippets) | Progress stall/deadlock freeness | Bounded liveness templates for no-progress waiting conditions. |

Notes:

1. In root VerifyTop.scala, the default enabled check is consistency_spec(); other groups are documented as sample switchable suites.
2. Deadlock properties in the deadlock-* variants are moved to controller-level MSHRCtl.scala assertions rather than kept in VerifyTop.scala.

</details>

<details>
<summary><strong>Assertion Locations by Version</strong></summary>

<br>

| Version / case | Primary assertion location | Covered bug scope |
| --- | --- | --- |
| XiangShan-CoupledL2-copy_equality | code/XiangShan-CoupledL2-copy_equality/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala | Data consistency |
| XiangShan-CoupledL2-write_read | code/XiangShan-CoupledL2-write_read/Chisel/src/test/scala/coupledL2Verification/VerifyTop.scala | Data consistency + protocol constraints |
| XiangShan-CoupledL2-peer-l2 | code/XiangShan-CoupledL2-peer-l2/Chisel/src/test/scala/coupledL2/VerifyTop.scala | Protocol-state legality |
| XiangShan-CoupledL2-deadlock-v0 | code/XiangShan-CoupledL2-deadlock-v0/Chisel/src/main/scala/coupledL2/MSHRCtl.scala | Progress stall/deadlock freeness |
| XiangShan-CoupledL2-deadlock-v1 | code/XiangShan-CoupledL2-deadlock-v1/Chisel/src/main/scala/coupledL2/MSHRCtl.scala | Progress stall/deadlock freeness |
| XiangShan-CoupledL2-deadlock-v2 | code/XiangShan-CoupledL2-deadlock-v2/Chisel/src/main/scala/coupledL2/MSHRCtl.scala | Progress stall/deadlock freeness |
| XiangShan-CoupledL2-deadlock-v3 | code/XiangShan-CoupledL2-deadlock-v3/Chisel/src/main/scala/coupledL2/MSHRCtl.scala | Progress stall/deadlock freeness |
| XiangShan-CoupledL2-deadlock-v4 | code/XiangShan-CoupledL2-deadlock-v4/Chisel/src/main/scala/coupledL2/tl2tl/MSHRCtl.scala | Progress stall/deadlock freeness |

Important deadlock note:

1. For deadlock-v0 to deadlock-v4, deadlock assertions are in MSHRCtl.scala.
2. VerifyTop.scala in deadlock variants is mainly used as formal harness wiring and stimulus/observation shell.

</details>

<details>
<summary><strong>InclusiveCache Assertion Migration</strong></summary>

<br>

InclusiveCache paper-related assertions have been migrated into the real project verification entry:

1. code/RocketChip-InclusiveCache/Chisel/inclusivecache-verification/src/test/scala/TestTop.scala

Current retained property groups in that TestTop are paper-scoped only:

1. liveness_spec (progress/deadlock freeness)
2. l1_l1_mutual_specs (protocol-state legality)
3. l1_l2_mutual_specs (protocol-state legality / coherence constraints)
4. l1_l2_inclusive_specs (inclusion-policy conformance)
5. l1_l1_consistency_specs (data consistency)

Non-paper auxiliary assertions (internal MSHR/dir sanity groups) were removed from this TestTop to keep the property set aligned with the paper bug scope.

</details>

---

