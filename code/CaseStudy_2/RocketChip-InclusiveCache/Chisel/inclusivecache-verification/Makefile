# Simple Makefile for InclusiveCache standalone verification harness
# Usage examples:
#   make run             # elaborate TestTop to FIRRTL/Verilog (default)
#   make verilog         # generate Verilog only
#   make firrtl          # generate LowFIRRTL/MLIR (depending on chisel plugin output)
#   make clean

SBT ?= sbt
PROJECT ?= inclusivecache_verify
# Fully-qualified object name (package.verifyl2)
TOP ?= verifyl2.TestTop
OUTDIR ?= build
# Additional args passed to ChiselStage
GEN_ARGS ?=
CLASS ?= $(TOP)
# Path to chipyard root (one level up by default)
ROOT ?= ..

# Common chisel args (target-dir appended per-invocation)
CHISEL_ARGS ?=

all: run

$(OUTDIR):
	mkdir -p $@

run: $(OUTDIR)
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "runMain $(CLASS) $(CHISEL_ARGS) $(GEN_ARGS) --target-dir $(abspath $(OUTDIR))"

verilog: $(OUTDIR)
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "runMain $(CLASS) --target systemverilog $(CHISEL_ARGS) $(GEN_ARGS) --target-dir $(abspath $(OUTDIR))"

firrtl: $(OUTDIR)
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "runMain $(CLASS) --target firrtl $(CHISEL_ARGS) $(GEN_ARGS) --target-dir $(abspath $(OUTDIR))"

auto:
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "runMain verifyl2.AutoVerify $(CHISEL_ARGS) $(GEN_ARGS)"

clean:
	rm -rf $(OUTDIR) target project/project project/target

.PHONY: all run verilog firrtl clean auto
