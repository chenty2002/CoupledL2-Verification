# Message Generator parameter tuning

This project searches a finite parameter space for two Message Generators, two CoupledL2 caches, one HuanCun L3 cache, and backing RAM. It uses the search and result-classification code from [param_tuning](https://github.com/chenty2002/param_tuning). All source files and dependencies are ordinary files in the parent repository.

The directory contains runtime code, configuration, required support files, and license notices. Generated SystemVerilog, tool output, candidate results, and search summaries belong in ignored directories under `verilog/`.

## Files and tools

```text
XiangShan-CoupledL2-param-tuning/
├── search.json       # Parameter ranges and candidate budget
├── search.py         # Static constraints, ordered enumeration, and summaries
├── runner.py         # Generation, JasperGold execution, and independent recheck
├── Chisel/
│   ├── build.sc
│   ├── Makefile
│   ├── src/main/scala/messageGenerator/
│   ├── src/test/scala/coupledL2Verification/VerifyTop.scala
│   └── coupledL2/    # CoupledL2, HuanCun, Rocket Chip, utility, CDE, and HardFloat sources
└── verilog/          # Fixed verify.tcl and five support files
```

Running the full flow requires Python 3.9+, Git, `codex-run`, a JDK, Mill, JasperGold, and the Chisel 6 SystemVerilog generation tools. The runner uses `~/mill` through `codex-run`; `Chisel/.mill-version` selects Mill 0.11.5, and `build.sc` selects Scala 2.13.15 and Chisel 6.6.0. Python search code uses only the standard library. Dependencies are included locally; no submodule initialization or patch application is needed.

## Entry points

From this directory:

```bash
python3 search.py list --limit 20
python3 search.py prepare
python3 search.py summary
python3 runner.py
python3 runner.py --recheck
python3 runner.py --search
```

`list` shows candidates in order. `prepare` writes the next candidate description, and `summary` reads saved results. The default runner processes one candidate. `--search` continues through ordered candidates, independently rechecks the first feasible candidate, and stops at a minimum, an exhausted space, a candidate limit, or an unresolved result.

Both scripts accept `--config` and `--runs`. The runner requires the runs directory to be a direct child of this project's `verilog/`, so the fixed Tcl relative paths resolve correctly. The default location is `verilog/runs-l3-space/<candidate-id>/`. Run only one runner at a time to avoid shared Mill and JasperGold state conflicts.

The runner invokes `coupledL2Verification.ParamVerifyTop` directly with the complete `key=value` candidate and an output path. `AutoVerify` forwards the same arguments to that entry point. For standalone generation, `make -C Chisel verify` uses valid default geometry with L2 sets=4 and L3 sets=8. This standalone default does not change the search baseline or ordering. The root repository's indexed `make verify` flow is separate from this search.

## Parameters and ordering

`search.json` is the sole search-space configuration. It accepts 16 known parameter keys and integer ranges defined by `min + n*step <= max`. Missing keys use `search.py` baseline values. `search.max_candidates=0` removes the candidate limit. A nonzero limit counts statically eligible candidates, excluding bulk static pruning.

| Keys | Meaning |
| --- | --- |
| `l1.ways`, `l1.sets` | Directory ways and sets in each Message Generator |
| `l1.mshrs` | Ordinary transaction entries and source IDs in each Message Generator |
| `l2.ways`, `l2.sets`, `l2.mshrs` | Geometry and ordinary MSHRs in each CoupledL2 |
| `l3.ways`, `l3.sets`, `l3.mshrs` | Geometry and ordinary MSHRs in HuanCun L3 |
| `l1/l2/l3.blockBytes` | Fixed at 2 |
| `l1/l2/l3.channelBytes` | Fixed at 1 |
| `ram.width` | RAM address width, from 5 through 32 bits |

The `l1.*` field names also identify Message Generator checks in the property manifest. Each generator enables `retainedGrantCopy` and has exactly `l1.mshrs` ordinary entries, with source IDs in `[0, l1.mshrs)`. Its request inputs select valid, address, Acquire or Release, permission, store enable, and a 16-bit data value. Stores follow the generator's permission and conflict rules. Input addresses are not restricted to zero.

Candidates are ordered lexicographically by:

1. Total directory data capacity: `2*l1.ways*l1.sets*l1.blockBytes + 2*l2.ways*l2.sets*l2.blockBytes + l3.ways*l3.sets*l3.blockBytes`.
2. Total transaction/MSHR entries: `2*l1.mshrs + 2*l2.mshrs + l3.mshrs`.
3. The sum of the three `channelBytes` values.
4. The complete parameter tuple in sorted key order.

The capacity score counts Message Generator directory data and cache data; it does not estimate all implementation storage. Search does not assume that feasibility is monotonic in any parameter.

Static constraints require ways >= 2, power-of-two sets >= 2, entry/MSHR minima of 4/4/6, and `4*sets <= 2**ram.width` for every layer. `l3.sets > l2.sets` requires a strictly larger L3 set-index space; extra L3 ways do not satisfy this condition. These search constraints also appear in Scala `require` checks. Message Generator entries are limited to 16 source IDs, matching the configured range. Smaller counts are outside this search's stated domain. RAM uses one-byte beats and `TLFragmenter(1, 2)`.

The search filters per-parameter values and partitions valid domains by RAM width and L2 sets before merging ordered candidate streams. `rank` counts statically eligible candidates; `total` counts the original space, `eligible_total` counts eligible candidates, and `counts.static_reject` counts bulk pruning. `attempted` counts eligible candidates with saved results. `prepare` stores pruning information and candidate descriptions without creating a directory for every invalid combination. Existing outputs are preserved.

## Parameter checks

Only `param_*` properties are selected for proof. The fixed Tcl also exports the names of excluded internal checks. `runner.expected_manifest` derives the exact required assertions and covers from each candidate's geometry.

Message Generator checks observe its live directory rows, address commits, transaction allocation, outstanding Acquire addresses, and A/C/D channels. They retain the `__l1_*` suffixes used by the search interface. L2 and L3 checks observe the cache directories, ordinary MSHRs, address decoding, and channel transfers. The `param_mshr_progress` properties check allocation safety; they do not establish eventual completion.

Each set has a reachability cover. Other covers require two live ways and two occupied transaction/MSHR entries. Both L2 instances must reach their own `param_cover_miss__l2_0/1`: a valid `mshr_alloc_s3` for a channel-A directory miss. The RAM checks watch one byte address selected by the first masked-in write and compare source-matched read responses against the expected byte. These checks have the scope defined by their source predicates; they do not prove whole-memory concurrency correctness.

## Results and recheck

Candidate statuses are `static_reject`, `compile_failed`, `cex`, `tool_failed`, `property_missing`, `unknown`, `feasible_proved`, and `bounded_no_cex`. Static rejection, compilation failure, and counterexamples reject a candidate. Tool failure, missing properties, and unknown results stop ordered search at that candidate.

A feasible result requires successful generation and JasperGold completion, the exact assertion and cover manifest, processed statuses for every selected property, no counterexample, and every required cover reached. `feasible_proved` requires all selected assertions to be proved. `bounded_no_cex` permits the supported unresolved assertion statuses at the fixed proof time limit. A process exit code alone is insufficient, and a bounded result is not a complete proof.

`verilog/verify.tcl` fixes `elaborate -bbox_a 65536`, reset and clock names, a 1800-second proof limit, four engine threads, and four jobs per engine. The runner allows 900 seconds for generation and a 2100-second outer JasperGold timeout. An outer timeout is a tool failure.

At runtime, each candidate directory receives its candidate description, generated SV, raw logs, property manifests and statuses, and `result.json`. Feasible records include the existing required fields `sv_sha256`, `tcl_sha256`, `source_sha256`, `helper_sha256`, `source_revision`, `submodule_revisions`, `property_version`, `assumption_version`, `tool_versions`, `generate_log`, `jg_log`, and `elapsed_seconds`, together with expected and actual assertion/cover results. The compatibility field `submodule_revisions` describes ordinary dependency directories in the parent repository. `source_state` and these dependency records cover staged, unstaged, and nonignored new source files while excluding ignored build output.

Before generation the runner saves `run_metadata.json`. `--resume-generated` continues a generated candidate whose JasperGold run has not started; `--finalize-existing` reads a completed JasperGold run. Both require matching candidate, source state, Tcl, support files, and generated SV. Malformed or duplicate status rows, missing properties, unprocessed properties, and nonzero JasperGold exits cannot produce a feasible result. Existing SV and results are not overwritten.

The first feasible candidate enters `pending_recheck`. `--recheck` regenerates and reruns it in a fresh sibling `<candidate-id>-recheck` directory. Only a feasible recheck with matching inputs and generated SV permits `minimum`. The minimum is relative to the configured finite space, ordering, static constraints, selected properties, input model, and fixed proof window. Generated output and experiment results are excluded from version control.

## Licenses

License notices remain next to the corresponding source directories. Mulan PSL v2 notices retain the supplied Chinese and English license text.
