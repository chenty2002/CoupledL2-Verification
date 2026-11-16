# CoupledL2 Message Generator

This repository uses Message Generator clients at the TileLink boundary to drive two CoupledL2 instances, a HuanCun L3 cache, and backing memory. Each verification project includes its cache implementation, observation modules, and assertions so that behavior can be examined across implementation variants.

```text
Message Generator 0 -> CoupledL2 0 --\
                                      TLXbar -> HuanCun L3 -> TLRAM
Message Generator 1 -> CoupledL2 1 --/
```

## Repository structure

Verification projects are located directly under `code/`:

```text
.
├── Makefile
├── README.md
├── code/
│   ├── XiangShan-CoupledL2-copy_equality/
│   ├── XiangShan-CoupledL2-write_read/
│   ├── XiangShan-CoupledL2-deadlock-v0/
│   ├── XiangShan-CoupledL2-deadlock-v1/
│   ├── XiangShan-CoupledL2-deadlock-v2/
│   ├── XiangShan-CoupledL2-deadlock-v3/
│   ├── XiangShan-CoupledL2-deadlock-v4/
│   ├── XiangShan-CoupledL2-peer-l2/
│   ├── XiangShan-CoupledL2-param-tuning/
│   ├── XiangShan-CoupledL2-Native-L1/
│   ├── XiangShan-CoupledL2-TL-Test/
│   └── preprocess_sva.py
├── figures/
└── jg-example/
```

| Index | Project directory | Checks | Waveform |
| ---: | --- | --- | --- |
| 0 | `XiangShan-CoupledL2-copy_equality` | Shared-copy data comparison at address 0 across two L2 caches, with guards for in-flight transactions | [XiangShan-CoupledL2-copy_equality.fst](code/XiangShan-CoupledL2-copy_equality/XiangShan-CoupledL2-copy_equality.fst) |
| 1 | `XiangShan-CoupledL2-write_read` | Writeback-data tracking at address 0, matching D-channel responses to accepted A-channel requests by source ID | [XiangShan-CoupledL2-write_read-1017.fst](code/XiangShan-CoupledL2-write_read/XiangShan-CoupledL2-write_read-1017.fst) |
| 2 | `XiangShan-CoupledL2-deadlock-v0` | MSHR bounded progress | [XiangShan-CoupledL2-deadlock-v0.fst](code/XiangShan-CoupledL2-deadlock-v0/XiangShan-CoupledL2-deadlock-v0.fst) |
| 3 | `XiangShan-CoupledL2-deadlock-v1` | MSHR bounded progress | [XiangShan-CoupledL2-deadlock-v1.fst](code/XiangShan-CoupledL2-deadlock-v1/XiangShan-CoupledL2-deadlock-v1.fst) |
| 4 | `XiangShan-CoupledL2-deadlock-v2` | MSHR bounded progress | [XiangShan-CoupledL2-deadlock-v2.fst](code/XiangShan-CoupledL2-deadlock-v2/XiangShan-CoupledL2-deadlock-v2.fst) |
| 5 | `XiangShan-CoupledL2-deadlock-v3` | MSHR bounded progress | [XiangShan-CoupledL2-deadlock-v3.fst](code/XiangShan-CoupledL2-deadlock-v3/XiangShan-CoupledL2-deadlock-v3.fst) |
| 6 | `XiangShan-CoupledL2-deadlock-v4` | MSHR bounded progress | [XiangShan-CoupledL2-deadlock-v4.fst](code/XiangShan-CoupledL2-deadlock-v4/XiangShan-CoupledL2-deadlock-v4.fst) |
| 7 | `XiangShan-CoupledL2-peer-l2` | Permission-state checks across peer L2 caches and between L2 and L3 | [XiangShan-CoupledL2-peer-l2.fst](code/XiangShan-CoupledL2-peer-l2/XiangShan-CoupledL2-peer-l2.fst) |

Miss status holding registers (MSHRs) track outstanding cache requests. Bounded progress assertions detect requests that exceed a configured cycle limit. A failure still requires analysis of the wait dependencies to determine whether it is a deadlock.

`XiangShan-CoupledL2-Native-L1` provides the Native L1 comparison flow. `XiangShan-CoupledL2-TL-Test` provides the TL-Test simulation baseline.

The indexed Message Generator verification projects contain:

- `Chisel/src/main/scala/messageGenerator/`: `TLMessageGenerator.scala` and the C-channel sender, `MsgGenSourceC.scala`.
- `Chisel/src/test/scala/`: verification tops, generation entry points, and regression sources.
- `Chisel/set_verify.py`: SystemVerilog post-processing that retains formal checks, Message Generator self-checks, and progress-timer assertions.
- `Verilog/`: JasperGold launch scripts and black-box support files. Generation entry points place `VerifyTop_*.sv` files here; these outputs are excluded from version control.
- Project-level `.fst` waveforms and generated files under `Chisel/Verilog/`. Scenario figures are stored in the repository-level `figures/` directory.
- `rocket-chip/`, `utility/`, and `HuanCun/`: dependencies used by the caches and message generators. Some projects place these dependencies under `Chisel/coupledL2/`.

## Message Generator

The verification tops instantiate `TLMessageGenerator` directly. The generator tracks cache-line addresses, permissions, data, outstanding acquisitions, Grant/GrantAck responses, probes, and Release/ReleaseAck exchanges. It also handles multibeat transfers and channel handshakes.

Top-level inputs select the request address, Acquire or Release operation, exclusive-permission request, and store data. Stores take effect only when the generator holds the required permission. In `deadlock-v0` through `deadlock-v3`, valid inputs update a request-address register. Other projects use the address input directly. The generator's `in_valid` is tied high in the indexed verification tops. The parameter tuning top exposes valid as an input.

The small profile typically uses 2-byte cache lines, 1-byte channel beats, four source IDs, and three ordinary transaction entries. Some projects enable `retainedGrantCopy`; its setting is defined in the corresponding `VerifyTop.scala`. Observation modules compare state and data exposed by the implementation. These checks do not establish coverage of all processor behaviors.

## Requirements and quick start

The main flow requires Java, Mill, Python 3, and JasperGold (`jg`). Generation scripts invoke `python`, which must resolve to Python 3. Tool versions are specified in each project's `.mill-version` and `build.sc`. The `copy_equality` and `write_read` projects use Chisel 6.6.0; the other indexed Message Generator projects use Chisel 3.6. The parameter tuning project also uses Chisel 6.6.0. Chisel 6 SystemVerilog generation also requires `firtool`.

From the repository root:

```bash
make help
make verify 0
make verify IDX=1
make verify 1 VERIFY_MODE=large
make verify 6 VERIFY_MODE=large
```

`VERIFY_MODE` defaults to `small`. Indices 0 through 7 also support `large`. The root entry point generates the selected top and invokes `Verilog/setup.sh` with the generated filename configured for that project.

To generate a top directly from a project's `Chisel/` directory:

```bash
# copy_equality and write_read
make auto

# deadlock-v0 through deadlock-v4, and peer-l2
make auto-l2l3l2
```

Then enter that project's `Verilog/` directory and use its generated filename:

```bash
./setup.sh VerifyTop_copy_equality.sv
# Optional arguments: proof time limit in hours, threads, maximum jobs per engine
./setup.sh VerifyTop_copy_equality.sv 24 16 32
```

When invoking a top through Mill directly, set `VERIFY_MODE=small` or `VERIFY_MODE=large`. The local Makefiles supply the `small` default.

## Parameter profiles and progress-check ablation

Each project's `VerifyTop.scala` defines the generator sets and ways, cache-line and channel widths, transaction-entry counts, L2/L3 parameters, and memory address range. Parameter values differ across implementation variants; use the configuration in the selected project.

For `deadlock-v0` through `deadlock-v3`, the `timers_N` bounded assertions can be converted to unbounded SystemVerilog Assertions (SVA) eventuality checks:

```bash
make verify 2 VERIFY_ABLATION=wo-bounded-liveness
python code/preprocess_sva.py --case XiangShan-CoupledL2-deadlock-v0 --dry-run
```

`deadlock-v4` uses `assertLivenessTimer` and is not supported by this conversion entry point. `VERIFY_ABLATION` defaults to `none`. Check the generated assertions and tool results to determine which properties are active and what has been verified; source-level wiring alone is not a completed verification result.

## Parameter tuning

[`code/XiangShan-CoupledL2-param-tuning/`](code/XiangShan-CoupledL2-param-tuning/README.md) provides ordered parameter search for two Message Generators, two CoupledL2 caches, HuanCun L3, and RAM. Its search and result-classification code comes from [param_tuning](https://github.com/chenty2002/param_tuning). Sources and dependencies are ordinary files in this repository.

The `l1.*` configuration fields describe Message Generator directory geometry and transaction entries. The search preserves candidate ordering, static filtering, per-property JasperGold classification, failure blocking, and independent recheck. A `bounded_no_cex` result records the fixed proof window's outcome. Runtime output and experiment results are ignored.

Run this separate flow from its own directory:

```bash
cd code/XiangShan-CoupledL2-param-tuning
python3 search.py list --limit 20
python3 runner.py --search
```

See its [README](code/XiangShan-CoupledL2-param-tuning/README.md) for tool requirements, parameter meanings, and result rules. It uses `Chisel/` for the source project and `verilog/` for fixed support files and ignored runtime output.

## Native L1 and TL-Test

Native L1 and TL-Test have separate sources, build files, and entry points:

```bash
make native-l1
make native-l1-help
make tltest 2
make tltest all
make tltest-help
```

Native L1 uses the client in `Chisel/src/main/scala/nativeL1/`. Its `Chisel/Makefile` target `auto` generates `VerifyTop_all.sv`, which is passed to `Verilog/setup.sh`. Set `NATIVE_L1_TOP` to override the top-file path. TL-Test dependencies and options are described in its [README](code/XiangShan-CoupledL2-TL-Test/README.md). The simulation flow also requires Verilator, CMake, a C++ compiler, and the SQLite development library.

## Waveforms

The eight FST waveforms contain two `messageGenerators` instances with their own `sourceC` and `probeQueue` instances. The `io_in_isAcquire` signal and top-level `msggenStore` and `msggenData` signals also identify the Message Generator input model.

| Project | Waveform |
| --- | --- |
| `copy_equality` | [XiangShan-CoupledL2-copy_equality.fst](code/XiangShan-CoupledL2-copy_equality/XiangShan-CoupledL2-copy_equality.fst) |
| `write_read` | [XiangShan-CoupledL2-write_read-1017.fst](code/XiangShan-CoupledL2-write_read/XiangShan-CoupledL2-write_read-1017.fst) |
| `deadlock-v0` | [XiangShan-CoupledL2-deadlock-v0.fst](code/XiangShan-CoupledL2-deadlock-v0/XiangShan-CoupledL2-deadlock-v0.fst) |
| `deadlock-v1` | [XiangShan-CoupledL2-deadlock-v1.fst](code/XiangShan-CoupledL2-deadlock-v1/XiangShan-CoupledL2-deadlock-v1.fst) |
| `deadlock-v2` | [XiangShan-CoupledL2-deadlock-v2.fst](code/XiangShan-CoupledL2-deadlock-v2/XiangShan-CoupledL2-deadlock-v2.fst) |
| `deadlock-v3` | [XiangShan-CoupledL2-deadlock-v3.fst](code/XiangShan-CoupledL2-deadlock-v3/XiangShan-CoupledL2-deadlock-v3.fst) |
| `deadlock-v4` | [XiangShan-CoupledL2-deadlock-v4.fst](code/XiangShan-CoupledL2-deadlock-v4/XiangShan-CoupledL2-deadlock-v4.fst) |
| `peer-l2` | [XiangShan-CoupledL2-peer-l2.fst](code/XiangShan-CoupledL2-peer-l2/XiangShan-CoupledL2-peer-l2.fst) |

All eight waveforms have 16-bit message-data inputs, which correspond to the small profile's 2-byte cache lines. The large profile uses 512-bit data inputs. These declarations identify the input model, but do not establish an exact match with the current sources or demonstrate verification of the large profile.

The hierarchy-record encoding follows the [libfst reader implementation](https://github.com/gtkwave/libfst/blob/main/src/fstapi.c). This identification uses hierarchy and signal declarations; it does not require a new simulation or formal run.

## Scenario figures

- [Probe-blocking scenario](figures/deadlock-1.png)
- [Same-set replacement and Probe wait](figures/deadlock-2.png)
- [Same-set contention](figures/deadlock-3.png)
- [TileLink state scenario](figures/TileLink_state_coherence.png)
- [Data consistency scenario](figures/consistency.png)
- [Shared-copy data scenario](figures/copy_equality.png)

## License notices

Source and dependency licenses are provided in their respective directories. Mulan PSL v2 [license files](code/XiangShan-CoupledL2-deadlock-v4/Chisel/LICENSE) retain the Chinese and English license text supplied with the sources.
