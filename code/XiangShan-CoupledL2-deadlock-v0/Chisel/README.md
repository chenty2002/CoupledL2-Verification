# XiangShan-CoupledL2-deadlock-v0

This project uses two Message Generator clients to drive CoupledL2 instances connected to TLRAM through a HuanCun L3 cache.

`VERIFY_MODE` supports `small` and `large`, with `small` as the default.

```bash
make auto-l2l3l2
```

- Verification top: `VerifyTop_L2L3L2` in `src/test/scala/coupledL2/VerifyTop.scala`.
- Generation entry point: `AutoVerify_L2L3L2` in `AutoVerify.scala` in the same directory.
- Message generator: `src/main/scala/messageGenerator/TLMessageGenerator.scala`.
- C-channel sender: `src/main/scala/messageGenerator/MsgGenSourceC.scala`.
- `set_verify.py` processes the generated SystemVerilog; `AutoVerify` copies the result to `../Verilog/`.

Tool versions are specified in `.mill-version` and `build.sc`. The repository includes the sources and dependencies needed by the generation entry point; the patches in `scripts/` do not need to be reapplied first.

See the [repository README](../../../README.md) for project indices, check scope, and JasperGold commands.
