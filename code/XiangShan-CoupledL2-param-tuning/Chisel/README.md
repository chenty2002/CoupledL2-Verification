# Message Generator parameterized top

This Chisel project contains two Message Generators, two CoupledL2 caches, one HuanCun L3 cache, and a TileLink RAM. Dependencies are ordinary source directories under `coupledL2/`.

Use the [parameter tuning entry points](../README.md) for ordered search, SystemVerilog generation, and JasperGold runs. `make verify` generates a standalone top with L2 sets=4 and L3 sets=8; this default is separate from the candidate order in `search.py`. `make auto PARAMS="..." OUTPUT=/absolute/path/VerifyTop.sv` invokes `ParamVerifyTop` with all 16 candidate keys.

Generation emits parameter checks directly. No assertion-removing post-processing or source patch application is required.
