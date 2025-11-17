# InclusiveCache Standalone Verification Harness

This module (`inclusivecache_verify` sbt project) builds a minimal test top (`TestTop`) wiring:

```
TLMessageGenerator[0]--\
                     +--> TLXbar -> InclusiveCache (L2) -> TLCacheCork -> TLRAM
TLMessageGenerator[1]--/
```

Key pieces:
- `TestTop.scala`: Instantiates two [`TLMessageGenerator`](../generators/MessageGenerator/messageGenerator/src/main/scala/TLMessageGenerator.scala) clients, a single InclusiveCache instance, and a backing `TLRAM`.
- Message generators expose address / operation control (`io.msg(i).{addr,isAcquire,param}`) so external harnesses can drive `Acquire` / `Release` sequences for formal verification.
- `MiniL2Config`: Simple `Config` providing `InclusiveCacheParams` (4 ways, 64 sets, 64B blocks -> 16KiB/set * 4 ways = 16 KiB per bank unbanked).
- Makefile targets for quick elaboration (`run`, `verilog`, `firrtl`).

## Build / Elaborate
From repository root (where `build.sbt` lives):

```
make -C verify-l2 run        # Elaborate (default target)
make -C verify-l2 verilog    # Emit SystemVerilog
make -C verify-l2 firrtl     # Emit FIRRTL
```

Or via sbt manually:
```
sbt 'project inclusivecache_verify' 'runMain TestTop'
```

Artifacts land under `verify-l2/build/` (modifiable via `OUTDIR`).

## Customization
Adjust parameters in `MiniL2Config` or layer a new `Config` (e.g.):
```scala
class BiggerL2 extends Config(new MiniL2Config ++ new Config((_,_,_) => {
  case MiniL2Key => MiniL2KeyParams.copy(ways = 8, sets = 128)
}))
```
Then change the `cfg` in `TestTop` or pass a generated config object (extend harness if needed).

## Notes
- Uses the repo's Chisel 6 toolchain to stay consistent with Rocket-Chip / InclusiveCache code.
- Top-level IO now provides a structured `msg` vector for driving each message generator. Supply the line base address, operation type (`isAcquire`), and parameter bit (`param`) per cycle to enqueue new transactions.
- MessageGenerator sources are included directly from `generators/MessageGenerator` for easy extension/customization.
- Further reduction of upstream dependencies would require pruning Rocket-Chip subprojects; this harness keeps the existing dependency graph for compatibility.
