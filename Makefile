.PHONY: verify help 0 1 2 3 4 5 6 7 8

IDX_FROM_GOALS := $(word 2,$(MAKECMDGOALS))
IDX ?= $(IDX_FROM_GOALS)
VERIFY_MODE ?= small
VERIFY_INPUT_MODE := msggen

define PRINT_MAPPING
echo "Usage: make verify <index>  (or: make verify IDX=<index>)"; \
echo "XiangShan size mode: VERIFY_MODE=small|large (default: $(VERIFY_MODE))"; \
echo "Example: make verify 1 VERIFY_MODE=large"; \
echo "Index mapping:"; \
echo "  0: XiangShan-CoupledL2-copy_equality"; \
echo "  1: XiangShan-CoupledL2-write_read"; \
echo "  2: XiangShan-CoupledL2-deadlock-v0"; \
echo "  3: XiangShan-CoupledL2-deadlock-v1"; \
echo "  4: XiangShan-CoupledL2-deadlock-v2"; \
echo "  5: XiangShan-CoupledL2-deadlock-v3"; \
echo "  6: XiangShan-CoupledL2-deadlock-v4"; \
echo "  7: XiangShan-CoupledL2-peer-l2"; \
echo "  8: RocketChip-InclusiveCache"
endef

help:
	@$(PRINT_MAPPING)

verify:
	@idx="$(IDX)"; \
	verify_mode="$(VERIFY_MODE)"; \
	verify_input_mode="$(VERIFY_INPUT_MODE)"; \
	if [ -z "$$idx" ]; then \
		$(PRINT_MAPPING); \
		exit 1; \
	fi; \
	case "$$verify_mode" in \
		small|large) ;; \
		*) \
			echo "Invalid VERIFY_MODE: $$verify_mode"; \
			echo "Expected VERIFY_MODE=small or VERIFY_MODE=large"; \
			exit 1 ;; \
	esac; \
	case "$$idx" in \
		0) dir="XiangShan-CoupledL2-copy_equality"; auto_target="auto" ;; \
		1) dir="XiangShan-CoupledL2-write_read"; auto_target="auto" ;; \
		2) dir="XiangShan-CoupledL2-deadlock-v0"; auto_target="auto-l2l3l2" ;; \
		3) dir="XiangShan-CoupledL2-deadlock-v1"; auto_target="auto-l2l3l2" ;; \
		4) dir="XiangShan-CoupledL2-deadlock-v2"; auto_target="auto-l2l3l2" ;; \
		5) dir="XiangShan-CoupledL2-deadlock-v3"; auto_target="auto-l2l3l2" ;; \
		6) dir="XiangShan-CoupledL2-deadlock-v4"; auto_target="auto-l2l3l2" ;; \
		7) dir="XiangShan-CoupledL2-peer-l2"; auto_target="auto-l2l3l2" ;; \
		8) dir="RocketChip-InclusiveCache"; auto_target="auto" ;; \
		*) \
			echo "Invalid index: $$idx"; \
			$(PRINT_MAPPING); \
			exit 1 ;; \
	esac; \
	for tool in java python; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "Missing required command: $$tool"; \
			exit 1; \
		fi; \
	done; \
	if [ "$$dir" = "RocketChip-InclusiveCache" ]; then \
		if ! command -v sbt >/dev/null 2>&1; then \
			echo "Missing required command: sbt"; \
			exit 1; \
		fi; \
	else \
		if ! command -v mill >/dev/null 2>&1; then \
			echo "Missing required command: mill"; \
			exit 1; \
		fi; \
	fi; \
	echo "Selected directory: $$dir"; \
	if [ "$$dir" != "RocketChip-InclusiveCache" ]; then \
		echo "VERIFY_MODE=$$verify_mode"; \
		echo "Compile in code/$$dir/Chisel: make $$auto_target"; \
		( cd "code/$$dir/Chisel" && VERIFY_MODE="$$verify_mode" VERIFY_INPUT_MODE="$$verify_input_mode" $(MAKE) $$auto_target ) || exit $$?; \
	else \
		echo "Compile in code/$$dir/Chisel: make $$auto_target"; \
		( cd "code/$$dir/Chisel" && $(MAKE) $$auto_target ) || exit $$?; \
	fi; \
	if ! command -v jg >/dev/null 2>&1; then \
		echo "Missing required command for verification: jg"; \
		exit 1; \
	fi; \
	echo "Verify in code/$$dir/Verilog: ./setup.sh VerifyTop*.sv"; \
	cd "code/$$dir/Verilog" && ./setup.sh VerifyTop*.sv

0 1 2 3 4 5 6 7 8:
	@:
