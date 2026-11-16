# CoupledL2 Message Generator

本仓库使用 Message Generator 作为 TileLink 客户端，驱动两个 CoupledL2 实例、一个 HuanCun L3 和后端内存。各用例保留各自的缓存源码、观察模块和断言，便于比较不同实现中的行为。

```text
Message Generator 0 -> CoupledL2 0 --\
                                      TLXbar -> HuanCun L3 -> TLRAM
Message Generator 1 -> CoupledL2 1 --/
```

## 目录与用例

各验证项目直接位于 `code/` 下：

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
│   ├── XiangShan-CoupledL2-Native-L1/
│   ├── XiangShan-CoupledL2-TL-Test/
│   └── preprocess_sva.py
├── figures/
└── jg-example/
```

| 索引 | 目录 | 检查内容 | 生成文件 |
| ---: | --- | --- | --- |
| 0 | `XiangShan-CoupledL2-copy_equality` | 两个 L2 中地址 0 的共享副本数据比较，保留在途事务保护条件 | `VerifyTop_copy_equality.sv` |
| 1 | `XiangShan-CoupledL2-write_read` | 跟踪地址 0 的写回数据，通过已接收 A 请求的 source 匹配 D 返回数据 | `VerifyTop_write_read.sv` |
| 2 | `XiangShan-CoupledL2-deadlock-v0` | MSHR 有界进展检查 | `VerifyTop_accquire.sv` |
| 3 | `XiangShan-CoupledL2-deadlock-v1` | MSHR 有界进展检查 | `VerifyTop_accquire.sv` |
| 4 | `XiangShan-CoupledL2-deadlock-v2` | MSHR 有界进展检查 | `VerifyTop_acquire.sv` |
| 5 | `XiangShan-CoupledL2-deadlock-v3` | MSHR 有界进展检查 | `VerifyTop_acquire.sv` |
| 6 | `XiangShan-CoupledL2-deadlock-v4` | MSHR 有界进展检查 | `VerifyTop_performance.sv` |
| 7 | `XiangShan-CoupledL2-peer-l2` | L2 副本之间及 L2/L3 的权限状态检查 | `VerifyTop_mutual.sv` |

MSHR 指未完成缓存请求的跟踪项。有界进展断言检查请求是否超过设定的周期限制；触发断言后仍需分析等待关系，才能判断是否发生死锁。

`XiangShan-CoupledL2-Native-L1` 提供 Native L1 对照，`XiangShan-CoupledL2-TL-Test` 提供 TL-Test 仿真对照。

每个主流程用例包含：

- `Chisel/src/main/scala/messageGenerator/`：`TLMessageGenerator.scala` 和 C 通道发送模块 `MsgGenSourceC.scala`。
- `Chisel/src/test/scala/`：验证顶层、生成入口及已有回归源码。
- `Chisel/set_verify.py`：处理生成的 SystemVerilog，保留形式检查、Message Generator 自检及进展计时断言。
- `Verilog/`：JasperGold 启动脚本和黑盒支持文件。`VerifyTop_*.sv` 由各项目的生成入口写入此目录，不纳入版本管理。
- 用例目录下的 `.fst` 保存波形，`Chisel/Verilog/` 保存生成文件；根目录 `figures/` 保存场景图。
- `rocket-chip/`、`utility/`、`HuanCun/`：缓存和消息生成器所需的基础依赖；部分用例将它们放在 `Chisel/coupledL2/` 下。

## Message Generator

顶层直接实例化 `TLMessageGenerator`，无需选择输入模型。生成器跟踪缓存行地址、权限、数据、在途 Acquire、Grant/GrantAck、Probe 和 Release/ReleaseAck，并处理多拍传输和通道握手。

顶层输入决定请求地址、Acquire/Release、是否请求独占权限以及写入数据。写入仅在生成器持有相应权限时生效。各用例的激励方式如下：`deadlock-v0` 至 `deadlock-v3` 用有效信号更新请求地址寄存器；其他用例直接使用地址输入。生成器的 `in_valid` 在这些顶层中固定为真。

小配置通常采用 2 字节缓存行、1 字节通道、4 个 source ID 和 3 个普通事务项。部分用例启用 `retainedGrantCopy`，其具体取值以对应 `VerifyTop.scala` 为准。观察模块比较的是实现中接出的状态和数据，不能据此推导完整处理器行为的覆盖范围。

## 工具与入口

需要 Java、Mill、Python 3 和 JasperGold（`jg`）。生成脚本使用命令名 `python`，应指向 Python 3。使用各目录的 `.mill-version` 和 `build.sc` 确认版本；`copy_equality`、`write_read` 使用 Chisel 6.6.0，其余用例使用 Chisel 3.6 系列。Chisel 6 的 SystemVerilog 生成还需要可用的 `firtool`。

在仓库根目录执行：

```bash
make help
make verify 0
make verify IDX=1
make verify 1 VERIFY_MODE=large
make verify 6 VERIFY_MODE=large
```

`VERIFY_MODE` 默认为 `small`，索引 0 至 7 均支持 `large`。根入口先生成对应顶层，再使用表中的确切文件名调用 `Verilog/setup.sh`。

也可以在用例的 `Chisel/` 目录直接生成：

```bash
# copy_equality、write_read
make auto

# deadlock-v0 至 deadlock-v4、peer-l2
make auto-l2l3l2
```

随后进入该用例的 `Verilog/`，使用表中的文件名：

```bash
./setup.sh VerifyTop_copy_equality.sv
# 可选参数：证明时间上限（小时）、线程数、每引擎最大任务数
./setup.sh VerifyTop_copy_equality.sv 24 16 32
```

直接用 Mill 调用顶层时，应设置 `VERIFY_MODE=small` 或 `large`。局部 Makefile 会提供 `small` 默认值。

## 参数与进展检查对照

参数选择保留在每个用例的 `VerifyTop.scala` 中，包括生成器的 sets/ways、缓存行和通道宽度、事务项数量、L2/L3 配置及内存地址范围。不同源码版本的参数不完全相同，应以该用例的配置为准。

`deadlock-v0` 至 `deadlock-v3` 支持将 `timers_N` 的有界断言转换为无界的 SystemVerilog Assertions（SVA）最终性检查：

```bash
make verify 2 VERIFY_ABLATION=wo-bounded-liveness
python code/preprocess_sva.py --case XiangShan-CoupledL2-deadlock-v0 --dry-run
```

`deadlock-v4` 使用 `assertLivenessTimer`，不使用这一转换入口。`VERIFY_ABLATION` 默认为 `none`。正式判断断言是否生效，需要检查生成文件和工具结果；源码中的接线本身不代表验证已完成。

## Native L1 与 TL-Test

Native L1 和 TL-Test 使用独立的源码、构建文件和运行入口：

```bash
make native-l1
make native-l1-help
make tltest 2
make tltest all
make tltest-help
```

Native L1 使用 `Chisel/src/main/scala/nativeL1/` 中的客户端，经 `Chisel/Makefile` 的 `auto` 目标生成 `VerifyTop_all.sv`，再交给 `Verilog/setup.sh`。可用 `NATIVE_L1_TOP` 指定顶层路径。TL-Test 的依赖和参数见其[说明](code/XiangShan-CoupledL2-TL-Test/README.md)；该流程还需要 Verilator、CMake、C++ 编译器和 SQLite 开发库。

## 波形

各项目的 FST 波形如下。检查其完整层次记录后，均可识别出两个 `messageGenerators` 实例，以及各自的 `sourceC` 和 `probeQueue`。`io_in_isAcquire` 与顶层 `msggenStore`、`msggenData` 等信号进一步确认了输入模型。

| 项目 | 波形文件 | 输入模型 | 数据输入位宽 |
| --- | --- | --- | ---: |
| `copy_equality` | [XiangShan-CoupledL2-copy_equality.fst](code/XiangShan-CoupledL2-copy_equality/XiangShan-CoupledL2-copy_equality.fst) | Message Generator | 16 |
| `deadlock-v0` | [XiangShan-CoupledL2-deadlock-v0.fst](code/XiangShan-CoupledL2-deadlock-v0/XiangShan-CoupledL2-deadlock-v0.fst) | Message Generator | 16 |
| `deadlock-v1` | [XiangShan-CoupledL2-deadlock-v1.fst](code/XiangShan-CoupledL2-deadlock-v1/XiangShan-CoupledL2-deadlock-v1.fst) | Message Generator | 16 |
| `deadlock-v2` | [XiangShan-CoupledL2-deadlock-v2.fst](code/XiangShan-CoupledL2-deadlock-v2/XiangShan-CoupledL2-deadlock-v2.fst) | Message Generator | 16 |
| `deadlock-v3` | [XiangShan-CoupledL2-deadlock-v3.fst](code/XiangShan-CoupledL2-deadlock-v3/XiangShan-CoupledL2-deadlock-v3.fst) | Message Generator | 16 |
| `deadlock-v4` | [XiangShan-CoupledL2-deadlock-v4.fst](code/XiangShan-CoupledL2-deadlock-v4/XiangShan-CoupledL2-deadlock-v4.fst) | Message Generator | 16 |
| `peer-l2` | [XiangShan-CoupledL2-peer-l2.fst](code/XiangShan-CoupledL2-peer-l2/XiangShan-CoupledL2-peer-l2.fst) | Message Generator | 16 |
| `write_read` | [XiangShan-CoupledL2-write_read-1017.fst](code/XiangShan-CoupledL2-write_read/XiangShan-CoupledL2-write_read-1017.fst) | Message Generator | 16 |

这些波形的消息数据输入为 16 位，对应 2 字节缓存行的小配置。large 配置的数据输入为 512 位。输入模型识别说明波形来自 Message Generator 顶层，不代表它与当前源码完全一致，也不能据此认定 large 配置已经完成验证。

层次记录的编码依据 [libfst 的读取实现](https://github.com/gtkwave/libfst/blob/main/src/fstapi.c)解析。这里只读取层次与信号声明，没有重新运行仿真或形式验证。

## 场景图

- [Probe 阻塞场景](figures/deadlock-1.png)
- [同组替换与 Probe 等待场景](figures/deadlock-2.png)
- [同组竞争场景](figures/deadlock-3.png)
- [TileLink 状态场景](figures/TileLink_state_coherence.png)
- [数据一致性场景](figures/consistency.png)
- [共享副本数据场景](figures/copy_equality.png)
