SBT ?= sbt
PROJECT ?= inclusivecache_verify
ROOT ?= .
OUTDIR ?= ../Verilog
CHISEL_ARGS ?=
GEN_ARGS ?=

compile:
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "compile"

verify:
	mkdir -p $(OUTDIR)
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "runMain verifyl2.TestTop --target systemverilog $(CHISEL_ARGS) $(GEN_ARGS) --target-dir $(abspath $(OUTDIR))"

auto:
	mkdir -p $(OUTDIR)
	cd $(ROOT) && $(SBT) "project $(PROJECT)" "runMain verifyl2.AutoVerify $(CHISEL_ARGS) $(GEN_ARGS)"

clean:
	rm -f ../Verilog/VerifyTop*.sv ../Verilog/verify.tcl

.PHONY: compile verify auto clean
