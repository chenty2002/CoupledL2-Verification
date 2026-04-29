# CoupledL2 Cache System - AI Coding Instructions

## Project Overview
CoupledL2 is a Chisel-based L2 cache implementation for RISC-V systems, supporting TileLink protocol and formal verification workflows. This project is part of a hierarchical cache verification infrastructure (L1→L2→L3) with formal verification capabilities.

## Architecture & Key Components

### Cache Hierarchy
- **TL2TLCoupledL2** (`src/main/scala/coupledL2/tl2tl/`): L2 cache with TileLink-to-TileLink protocol
- **TLCoupledL2AsL1** (`src/main/scala/coupledL2AsL1/`): L2 cache operating as L1 cache
- **HuanCun** (`HuanCun/`): L3 cache submodule integration
- **Rocket-chip** (`rocket-chip/`): Base SoC framework providing TileLink infrastructure

### Core L2 Cache Components
Located in `src/main/scala/coupledL2/`:
- **Directory.scala**: Metadata storage (tags, coherence states, client tracking) with `MetaEntry` containing dirty/state/clients/alias/prefetch/accessed fields
- **MSHRBuffer.scala**: Miss Status Holding Registers buffer for outstanding requests (sized by `cacheParams.mshrs`, default 16)
- **RequestBuffer.scala**: Request arbitration and queuing
- **DataStorage.scala**: Data array storage
- **SinkA/SinkC/SourceB**: TileLink channel handlers
- **BaseSlice.scala**: Single cache slice implementation

### Configuration System
Parameters defined via Chisel Diplomacy `Parameters` objects:
- **L2Param** (`src/main/scala/coupledL2/L2Param.scala`): Cache configuration including ways/sets/blockBytes/mshrs/prefetch
- **L1Param**: L1 cache parameters (for client configuration) - includes alias bits for virtual address disambiguation
- Use `baseConfig(hartId).alterPartial()` pattern for parameter overrides in test tops

### Verification Infrastructure
- **VerifyTop_L2L3L2** (`src/test/scala/coupledL2/VerifyTop.scala`): Multi-level cache hierarchy for formal verification (2×L2 + L3)
- **AutoVerify_L2L3L2** (`src/test/scala/coupledL2/AutoVerify.scala`): Automated verification generation with Python post-processing
- **chiselFv** (`src/main/scala/chiselFv/`): Formal verification integration (JasperGold/SymbiYosys)
- Generated Verilog outputs to `Verilog/L2L3L2/` directory

## Build System (Mill)

### Essential Commands
```bash
# Initialize submodules (rocket-chip, utility, HuanCun dependencies)
make init

# Compile Chisel sources
make compile  # or: mill -i CoupledL2.compile

# Generate verification top (manual)
make verify-l2l3l2  # runs: mill -i CoupledL2.test.runMain coupledL2.VerifyTop_L2L3L2

# Generate verification top (automated with Python post-processing)
make auto-l2l3l2    # runs: mill -i CoupledL2.test.runMain coupledL2.AutoVerify_L2L3L2

# IDE setup
make bsp            # BSP for VS Code Metals
make idea           # IntelliJ IDEA project

# Code formatting (required before commits)
make checkformat    # validate formatting
make reformat       # apply scalafmt
```

### Module Structure (`build.sc`)
- **CoupledL2**: Main module depending on `rocketchip`, `utility`, `huancun`
- All modules use Chisel 3.6.0 + Scala 2.13.10
- Build uses Mill (not SBT), structured with trait-based module dependencies (`common.sc` defines `CoupledL2Module` trait)

## Development Patterns

### Parameter Overrides
Always use `.alterPartial()` for configuration customization:
```scala
LazyModule(new TL2TLCoupledL2()(baseConfig(hartId).alterPartial({
  case L2ParamKey => L2Param(ways = 4, sets = 128, mshrs = 16, ...)
  case BankBitsKey => 0
})))
```

### TileLink Diplomacy
- Create client nodes with `TLClientNode` specifying `sourceId` ranges, `supportsProbe`, `channelBytes`
- Bind nodes using `:=` operator (e.g., `l2.node :=* xbar`)
- Add delays/fragmentation in chain: `TLDelayer(0.2) := TLFragmenter(32, 64) := node`

### Test Tops
Structure follows: L1-clients → L2-nodes → (optional xbar) → L3/RAM
- Use `createClientNode()` helper for L1 simulation
- Always connect `module.io.hartId`, `debugTopDown`, `l2_tlb_req` (can use `DontCare`)
- Call `node.makeIOs()` to expose IOs with `ValName` for multi-master setups

### Prefetch Integration
Configure via `L2Param.prefetch` field (Seq of prefetcher params):
- **BOPParameters**: Best-Offset Prefetcher
- **TPParameters**: TP prefetcher with metadata interface
- **PrefetchReceiverParams**: Receives external prefetch requests
Prefetch state tracked in `MetaEntry.prefetch` and `prefetchSrc` fields

## Python Verification Post-Processing

### `set_verify.py` Workflow
Post-processes generated `VerifyTop.sv` to:
1. Remove `.site` parameter references (Diplomacy artifacts)
2. Comment out assertions/`$fwrite` not matching `match_tag` or `resetCounter_notChaos`
3. Generate `VerifyTop_top.sv` for formal tools

**Pattern**: Always run Python script after `mill runMain` for verification generation

## Coding Conventions

### File Organization
- Protocol-specific code in subdirs: `tl2tl/`, `tl2chi/`, `prefetch/`, `utils/`, `debug/`
- Each major component in separate file (Directory.scala, DataStorage.scala, etc.)
- Test mains in `src/test/scala/` with runMain entry points

### Naming Conventions
- LazyModules end in node definitions, Imps in `lazy val module = new ...Imp(this)`
- Use `HasCoupledL2Parameters` trait for parameter access
- Bundle fields: `io.hartId`, `io.debugTopDown`, `io.l2_tlb_req` (standard L2 interface)

### Import Structure
Standard imports for L2 modules:
```scala
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import coupledL2._
```

## Key Files to Reference
- `src/main/scala/coupledL2/L2Param.scala`: All cache parameters
- `src/main/scala/coupledL2/CoupledL2.scala`: Base classes (`CoupledL2Base`, `BaseCoupledL2Imp`)
- `src/test/scala/TestTop.scala`: Example test hierarchy configurations
- `build.sc`: Mill build configuration and module dependencies

## Submodule Dependencies
**Critical**: Always run `make init` after cloning - requires rocket-chip hardfloat/cde submodules
