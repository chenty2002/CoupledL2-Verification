# XiangShan-CoupledL2-deadlock-v4

This project uses two Message Generator clients to drive CoupledL2 instances connected to TLRAM through a HuanCun L3 cache.

`VERIFY_MODE` supports `small` and `large`, with `small` as the default.

```bash
make auto-l2l3l2
make auto-l2l3l2 VERIFY_MODE=large
```

- Verification top: `VerifyTop_L2L3L2` in `src/test/scala/coupledL2/VerifyTop.scala`.
- Generation entry point: `AutoVerify_L2L3L2` in `AutoVerify.scala` in the same directory.
- Message generator: `src/main/scala/messageGenerator/TLMessageGenerator.scala`.
- C-channel sender: `src/main/scala/messageGenerator/MsgGenSourceC.scala`.
- `set_verify.py` processes the generated SystemVerilog; `AutoVerify` copies the result to `../Verilog/`.

Tool versions are specified in `.mill-version` and `build.sc`. The repository includes the sources and dependencies needed by the generation entry point; the patches in `scripts/` do not need to be reapplied first.

See the [repository README](../../../README.md) for project indices, check scope, and JasperGold commands.

## Parameter profiles

| Parameter | small | large |
| --- | ---: | ---: |
| Message Generator sets / ways | 2 / 2 | 64 / 4 |
| Source IDs / ordinary transaction entries | 4 / 3 | 16 / 15 |
| L2 sets / ways / MSHRs | 4 / 2 / 4 | 128 / 4 / 16 |
| L3 sets / ways / MSHRs | 4 / 2 / 6 | 128 / 4 / 14 |
| Cache-line bytes / bytes per beat | 2 / 1 | 64 / 32 |
| Memory address bits | 5 | 24 |
| Data input and observation-array width | 16 | 512 |

Both profiles use a single bank and two beats per cache line. As in the related implementation variants, `retainedGrantCopy` is enabled for `small` and disabled for `large`. Regenerate the top after changing profiles. `VerifyTop_*.sv` files are generated outputs and are excluded from version control.
