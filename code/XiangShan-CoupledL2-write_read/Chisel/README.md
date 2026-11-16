# XiangShan-CoupledL2-write_read

本用例使用两个 Message Generator 驱动 CoupledL2，经 HuanCun L3 连接到 TLRAM。

支持 `VERIFY_MODE=small|large`，默认 `small`。

```bash
make auto
```

- 验证顶层：`src/test/scala/coupledL2Verification/VerifyTop.scala` 中的 `VerifyTop`。
- 自动生成入口：同目录 `AutoVerify.scala` 中的 `AutoVerify`。
- 消息生成器：`src/main/scala/messageGenerator/TLMessageGenerator.scala`。
- C 通道发送模块：`src/main/scala/messageGenerator/MsgGenSourceC.scala`。
- `set_verify.py` 处理生成文件；`AutoVerify` 将结果复制到 `../Verilog/`。

工具版本见本目录 `.mill-version` 和 `build.sc`。源码和基础依赖已随仓库保存，生成入口不要求先重新应用 `scripts/` 中的补丁。

用例索引、检查范围和 JasperGold 调用方法见[仓库说明](../../../README.md)。
