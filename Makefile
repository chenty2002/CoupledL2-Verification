.PHONY: verify help tltest tltest-help ablation-help native-l1 native-l1-help \
	0 1 2 3 4 5 6 7 8 all \
	XiangShan-CoupledL2-copy_equality \
	XiangShan-CoupledL2-write_read \
	XiangShan-CoupledL2-deadlock-v0 \
	XiangShan-CoupledL2-deadlock-v1 \
	XiangShan-CoupledL2-deadlock-v2 \
	XiangShan-CoupledL2-deadlock-v3 \
	XiangShan-CoupledL2-deadlock-v4 \
	XiangShan-CoupledL2-peer-l2

IDX_FROM_GOALS := $(word 2,$(MAKECMDGOALS))
IDX ?= $(IDX_FROM_GOALS)
VERIFY_MODE ?= small
VERIFY_INPUT_MODE := msggen
VERIFY_ABLATION ?= none
TLTEST_CASE_FROM_GOALS := $(word 2,$(MAKECMDGOALS))
TLTEST_CASE ?= $(TLTEST_CASE_FROM_GOALS)
TLTEST_THREADS_BUILD ?= 64
TLTEST_VERIFY_MAX_CYCLE ?= 2000
NATIVE_L1_TOP ?= ../Chisel/VerifyTop_all.sv
XIANGSHAN_CASE_ROOT := code/CaseStudy_1
ROCKETCHIP_CASE_ROOT := code/CaseStudy_2

define PRINT_MAPPING
echo "Usage: make verify <index>  (or: make verify IDX=<index>)"; \
echo "XiangShan size mode: VERIFY_MODE=small|large (default: $(VERIFY_MODE))"; \
echo "Ablation mode: VERIFY_ABLATION=none|wo-bounded-liveness (default: $(VERIFY_ABLATION))"; \
echo "Example: make verify 1 VERIFY_MODE=large"; \
echo "Example: make verify 2 VERIFY_ABLATION=wo-bounded-liveness"; \
echo "TL-Test ablation: make tltest <case-or-index>  (see: make tltest-help)"; \
echo "Native-L1 ablation: make native-l1  (see: make native-l1-help)"; \
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

define PRINT_TLTEST_MAPPING
echo "Usage: make tltest <case>  (or: make tltest TLTEST_CASE=<case>)"; \
echo "Input supports directory name or index:"; \
echo "TL-Test ablation cases:"; \
echo "  all: run all TL-Test ablation cases"; \
echo "  0: XiangShan-CoupledL2-copy_equality"; \
echo "  1: XiangShan-CoupledL2-write_read"; \
echo "  2: XiangShan-CoupledL2-deadlock-v0"; \
echo "  3: XiangShan-CoupledL2-deadlock-v1"; \
echo "  4: XiangShan-CoupledL2-deadlock-v2"; \
echo "  5: XiangShan-CoupledL2-deadlock-v3"; \
echo "  6: XiangShan-CoupledL2-deadlock-v4"; \
echo "  7: XiangShan-CoupledL2-peer-l2"; \
echo "Optional: TLTEST_THREADS_BUILD=<n> TLTEST_VERIFY_MAX_CYCLE=<n>"
endef

define PRINT_ABLATION_MAPPING
echo "Usage: make verify <index> VERIFY_ABLATION=wo-bounded-liveness"; \
echo "Supported ablation modes:"; \
echo "  none"; \
echo "  wo-bounded-liveness"; \
echo "Applicable cases:"; \
echo "  XiangShan-CoupledL2-deadlock-v0"; \
echo "  XiangShan-CoupledL2-deadlock-v1"; \
echo "  XiangShan-CoupledL2-deadlock-v2"; \
echo "  XiangShan-CoupledL2-deadlock-v3"; \
echo "  XiangShan-CoupledL2-deadlock-v4"
endef

define PRINT_NATIVE_L1_MAPPING
echo "Usage: make native-l1"; \
echo "Runs the \"w/o Simplified L1\" ablation based on $(XIANGSHAN_CASE_ROOT)/XiangShan-CoupledL2-Native-L1."; \
echo "Optional: NATIVE_L1_TOP=$(NATIVE_L1_TOP)"
endef

help:
	@$(PRINT_MAPPING)

tltest-help:
	@$(PRINT_TLTEST_MAPPING)

ablation-help:
	@$(PRINT_ABLATION_MAPPING)

native-l1-help:
	@$(PRINT_NATIVE_L1_MAPPING)

tltest:
	@case_id="$(TLTEST_CASE)"; \
	tltest_case=""; \
	if [ -z "$$case_id" ]; then \
		case_id="all"; \
	fi; \
	case "$$case_id" in \
		all) tltest_case="all" ;; \
		0) tltest_case="XiangShan-CoupledL2-copy_equality" ;; \
		1) tltest_case="XiangShan-CoupledL2-write_read" ;; \
		2) tltest_case="XiangShan-CoupledL2-deadlock-v0" ;; \
		3) tltest_case="XiangShan-CoupledL2-deadlock-v1" ;; \
		4) tltest_case="XiangShan-CoupledL2-deadlock-v2" ;; \
		5) tltest_case="XiangShan-CoupledL2-deadlock-v3" ;; \
		6) tltest_case="XiangShan-CoupledL2-deadlock-v4" ;; \
		7) tltest_case="XiangShan-CoupledL2-peer-l2" ;; \
		XiangShan-CoupledL2-copy_equality|XiangShan-CoupledL2-write_read|XiangShan-CoupledL2-deadlock-v0|XiangShan-CoupledL2-deadlock-v1|XiangShan-CoupledL2-deadlock-v2|XiangShan-CoupledL2-deadlock-v3|XiangShan-CoupledL2-deadlock-v4|XiangShan-CoupledL2-peer-l2) tltest_case="$$case_id" ;; \
		*) \
			echo "Invalid TLTEST_CASE: $$case_id"; \
			$(PRINT_TLTEST_MAPPING); \
			exit 1 ;; \
	esac; \
	if [ "$$tltest_case" = "all" ]; then \
		tltest_target="coupledL2-verify-all-v3"; \
	else \
		tltest_target="coupledL2-verify-$$tltest_case-v3"; \
	fi; \
	for tool in python mill verilator cmake; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "Missing required command for TL-Test: $$tool"; \
			exit 1; \
		fi; \
	done; \
	echo "TL-Test target: $$tltest_target"; \
	echo "TLTEST_THREADS_BUILD=$(TLTEST_THREADS_BUILD)"; \
	echo "TLTEST_VERIFY_MAX_CYCLE=$(TLTEST_VERIFY_MAX_CYCLE)"; \
	( cd $(XIANGSHAN_CASE_ROOT)/XiangShan-CoupledL2-TL-Test && \
	  $(MAKE) "$$tltest_target" THREADS_BUILD="$(TLTEST_THREADS_BUILD)" VERIFY_MAX_CYCLE="$(TLTEST_VERIFY_MAX_CYCLE)" )

native-l1:
	@for tool in java python mill; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "Missing required command for Native-L1 ablation: $$tool"; \
			exit 1; \
		fi; \
	done; \
	if ! command -v jg >/dev/null 2>&1; then \
		echo "Missing required command for verification: jg"; \
		exit 1; \
	fi; \
	echo "Compile in $(XIANGSHAN_CASE_ROOT)/XiangShan-CoupledL2-Native-L1/Chisel: make auto"; \
	( cd $(XIANGSHAN_CASE_ROOT)/XiangShan-CoupledL2-Native-L1/Chisel && $(MAKE) auto ) || exit $$?; \
	echo "Verify in $(XIANGSHAN_CASE_ROOT)/XiangShan-CoupledL2-Native-L1/Verilog: ./setup.sh $(NATIVE_L1_TOP)"; \
	( cd $(XIANGSHAN_CASE_ROOT)/XiangShan-CoupledL2-Native-L1/Verilog && ./setup.sh "$(NATIVE_L1_TOP)" ) || exit $$?

verify:
	@idx="$(IDX)"; \
	verify_mode="$(VERIFY_MODE)"; \
	verify_input_mode="$(VERIFY_INPUT_MODE)"; \
	verify_ablation="$(VERIFY_ABLATION)"; \
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
	case "$$verify_ablation" in \
		none|wo-bounded-liveness) ;; \
		*) \
			echo "Invalid VERIFY_ABLATION: $$verify_ablation"; \
			$(PRINT_ABLATION_MAPPING); \
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
	if [ "$$dir" = "RocketChip-InclusiveCache" ]; then \
		case_root="$(ROCKETCHIP_CASE_ROOT)"; \
	else \
		case_root="$(XIANGSHAN_CASE_ROOT)"; \
	fi; \
	case_path="$$case_root/$$dir"; \
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
	echo "Selected path: $$case_path"; \
	echo "VERIFY_ABLATION=$$verify_ablation"; \
	if [ "$$dir" != "RocketChip-InclusiveCache" ]; then \
		echo "VERIFY_MODE=$$verify_mode"; \
		echo "Compile in $$case_path/Chisel: make $$auto_target"; \
		( cd "$$case_path/Chisel" && VERIFY_MODE="$$verify_mode" VERIFY_INPUT_MODE="$$verify_input_mode" $(MAKE) $$auto_target ) || exit $$?; \
	else \
		echo "Compile in $$case_path/Chisel: make $$auto_target"; \
		( cd "$$case_path/Chisel" && $(MAKE) $$auto_target ) || exit $$?; \
	fi; \
	if ! command -v jg >/dev/null 2>&1; then \
		echo "Missing required command for verification: jg"; \
		exit 1; \
	fi; \
	if [ "$$verify_ablation" = "wo-bounded-liveness" ]; then \
		case "$$dir" in \
			XiangShan-CoupledL2-deadlock-v0|XiangShan-CoupledL2-deadlock-v1|XiangShan-CoupledL2-deadlock-v2|XiangShan-CoupledL2-deadlock-v3|XiangShan-CoupledL2-deadlock-v4) ;; \
			*) \
				echo "VERIFY_ABLATION=wo-bounded-liveness only supports XiangShan deadlock cases."; \
				exit 1 ;; \
		esac; \
		echo "Apply ablation preprocessing in $$case_path/Verilog"; \
		python code/preprocess_sva.py --root "$(XIANGSHAN_CASE_ROOT)" --case "$$dir" || exit $$?; \
	fi; \
	echo "Verify in $$case_path/Verilog: ./setup.sh VerifyTop*.sv"; \
	cd "$$case_path/Verilog" && ./setup.sh VerifyTop*.sv

0 1 2 3 4 5 6 7 8 all XiangShan-CoupledL2-copy_equality XiangShan-CoupledL2-write_read XiangShan-CoupledL2-deadlock-v0 XiangShan-CoupledL2-deadlock-v1 XiangShan-CoupledL2-deadlock-v2 XiangShan-CoupledL2-deadlock-v3 XiangShan-CoupledL2-deadlock-v4 XiangShan-CoupledL2-peer-l2:
	@:
