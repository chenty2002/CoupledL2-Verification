# XiangShan-CoupledL2-copy_equality

This project uses two Message Generator clients to drive CoupledL2 instances connected to TLRAM through a HuanCun L3 cache.

`VERIFY_MODE` supports `small` and `large`, with `small` as the default.

```bash
make auto
```

- Verification top: `VerifyTop` in `src/test/scala/coupledL2Verification/VerifyTop.scala`.
- Generation entry point: `AutoVerify` in `AutoVerify.scala` in the same directory.
- Message generator: `src/main/scala/messageGenerator/TLMessageGenerator.scala`.
- C-channel sender: `src/main/scala/messageGenerator/MsgGenSourceC.scala`.
- `set_verify.py` processes the generated SystemVerilog; `AutoVerify` copies the result to `../Verilog/`.

Tool versions are specified in `.mill-version` and `build.sc`. The repository includes the sources and dependencies needed by the generation entry point; the patches in `scripts/` do not need to be reapplied first.

See the [repository README](../../../README.md) for project indices, check scope, and JasperGold commands.
