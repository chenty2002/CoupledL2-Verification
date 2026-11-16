# XiangShan-CoupledL2-deadlock-v4

本用例使用两个 Message Generator 驱动 CoupledL2，经 HuanCun L3 连接到 TLRAM。

支持 `VERIFY_MODE=small|large`，默认 `small`。

```bash
make auto-l2l3l2
make auto-l2l3l2 VERIFY_MODE=large
```

- 验证顶层：`src/test/scala/coupledL2/VerifyTop.scala` 中的 `VerifyTop_L2L3L2`。
- 自动生成入口：同目录 `AutoVerify.scala` 中的 `AutoVerify_L2L3L2`。
- 消息生成器：`src/main/scala/messageGenerator/TLMessageGenerator.scala`。
- C 通道发送模块：`src/main/scala/messageGenerator/MsgGenSourceC.scala`。
- `set_verify.py` 处理生成文件；`AutoVerify` 将结果复制到 `../Verilog/`。

工具版本见本目录 `.mill-version` 和 `build.sc`。源码和基础依赖已随仓库保存，生成入口不要求先重新应用 `scripts/` 中的补丁。

用例索引、检查范围和 JasperGold 调用方法见[仓库说明](../../../README.md)。

## 参数配置

| 参数 | small | large |
| --- | ---: | ---: |
| Message Generator sets / ways | 2 / 2 | 64 / 4 |
| source ID 数量 / 普通事务项 | 4 / 3 | 16 / 15 |
| L2 sets / ways / MSHR | 4 / 2 / 4 | 128 / 4 / 16 |
| L3 sets / ways / MSHR | 4 / 2 / 6 | 128 / 4 / 14 |
| 缓存行字节数 / 每拍字节数 | 2 / 1 | 64 / 32 |
| 内存地址位宽 | 5 | 24 |
| 数据输入及数据观察数组位宽 | 16 | 512 |

两种配置均使用单 bank，缓存行均分两拍传输。`retainedGrantCopy` 与同代用例一致：small 启用，large 关闭。切换配置后需要重新生成顶层；`VerifyTop_*.sv` 是生成输出，不纳入版本管理。
