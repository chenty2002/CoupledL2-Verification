MILL ?= mill
OUTPUT ?= $(CURDIR)/Verilog/VerifyTop.sv

.PHONY: init compile verify auto clean bsp idea reformat checkformat

init:
	@printf '%s\n' 'Dependencies are included as ordinary source files; no initialization is required.'

compile:
	$(MILL) -i CoupledL2Verification.compile

# Generate a standalone top with valid default geometry (L2 sets=4, L3 sets=8).
verify:
	$(MILL) -i CoupledL2Verification.test.runMain coupledL2Verification.VerifyTop

# For ordered parameter search and JasperGold classification, use ../runner.py.
# Direct generation requires all 16 candidate keys in PARAMS.
auto:
	@test -n "$(PARAMS)" || { printf '%s\n' 'Set PARAMS to the complete key=value candidate, or use ../runner.py.'; exit 1; }
	$(MILL) -i CoupledL2Verification.test.runMain coupledL2Verification.ParamVerifyTop $(PARAMS) output=$(OUTPUT)

clean:
	rm -rf ./build

bsp:
	$(MILL) -i mill.bsp.BSP/install

idea:
	$(MILL) -i mill.idea.GenIdea/idea

reformat:
	$(MILL) -i __.reformat

checkformat:
	$(MILL) -i __.checkFormat
