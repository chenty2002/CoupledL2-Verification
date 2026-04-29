#!/bin/bash

set -euo pipefail
shopt -s nullglob

src_dir="$1"
dst_dir="$2"

copy_verilog_sources() {
	local from_dir="$1"
	local file

	for file in "$from_dir"/*.v "$from_dir"/*.sv; do
		[[ -f "$file" ]] || continue
		cp "$file" "$dst_dir"/
	done
}

sed_in_place() {
	if sed --version >/dev/null 2>&1; then
		sed -i "$@"
	else
		sed -i '' "$@"
	fi
}

find_special_verilog_dir() {
	local candidate
	for candidate in "$@"; do
		[[ -d "$candidate" ]] || continue
		if [[ -f "$candidate/VerifyTop_data_consistency.sv" ]]; then
			printf '%s\n' "$candidate"
			return 0
		fi
	done
	return 1
}

find_file_dir() {
	local file_name="$1"
	shift
	local candidate
	for candidate in "$@"; do
		[[ -d "$candidate" ]] || continue
		if [[ -f "$candidate/$file_name" ]]; then
			printf '%s\n' "$candidate"
			return 0
		fi
	done
	return 1
}

candidate_dirs=(
	"$src_dir"
	"$(dirname "$src_dir")"
	"$(dirname "$(dirname "$src_dir")")"
	"$(dirname "$(dirname "$(dirname "$src_dir")")")"
	"$(dirname "$(dirname "$src_dir")")/Verilog"
	"$(dirname "$(dirname "$(dirname "$src_dir")")")/Verilog"
)

special_verilog_dir=""
if special_verilog_dir="$(find_special_verilog_dir "${candidate_dirs[@]}")"; then
	cp "$special_verilog_dir/VerifyTop_data_consistency.sv" "$dst_dir"/

	for extra_file in TLLogWriter.v STD_CLKGT_func.v; do
		extra_dir=""
		if extra_dir="$(find_file_dir "$extra_file" "${candidate_dirs[@]}")"; then
			cp "$extra_dir/$extra_file" "$dst_dir"/
		fi
	done
else
	copy_verilog_sources "$src_dir"
fi

for staged_sv in "$dst_dir"/*.sv; do
	[[ -f "$staged_sv" ]] || continue

	if grep -q 'firrtl_black_box_resource_files\.f' "$staged_sv"; then
		awk '/firrtl_black_box_resource_files\.f/ { exit } { print }' "$staged_sv" > "$staged_sv.tmp"
		mv "$staged_sv.tmp" "$staged_sv"
	fi

	if grep -q '^assign timer[[:space:]]*=' "$staged_sv"; then
		sed_in_place \
			-e "s/^assign timer[[:space:]]*=.*;/assign timer         = 64'b0;/" \
			-e "s/^assign logEnable[[:space:]]*=.*;/assign logEnable     = 1'b0;/" \
			-e "s/^assign clean[[:space:]]*=.*;/assign clean         = 1'b0;/" \
			-e "s/^assign dump[[:space:]]*=.*;/assign dump          = 1'b0;/" \
			"$staged_sv"
	fi

	case "$(basename "$staged_sv")" in
		VerifyTop_data_consistency.sv)
			python3 ./scripts/sanitize_verifytop_asserts.py --mode property-only --in-place "$staged_sv"
			;;
		VerifyTop*.sv)
			python3 ./scripts/sanitize_verifytop_asserts.py --mode resetcounter-only --in-place "$staged_sv"
			;;
	esac
done

if [[ ! -f "$dst_dir/chisel_db.cpp" ]]; then
cat > "$dst_dir/chisel_db.cpp" <<'EOF'
#include <cstdint>

extern "C" void init_db(bool, bool, const char*) {}
extern "C" void save_db(const char*) {}

#define TLTEST_STUB_ROLLING_0(name) \
extern "C" void name##_rolling_0_write(uint64_t, uint64_t, uint64_t, char*) {}

TLTEST_STUB_ROLLING_0(L2PrefetchAccuracyBOP)
TLTEST_STUB_ROLLING_0(L2PrefetchAccuracyPBOP)
TLTEST_STUB_ROLLING_0(L2PrefetchAccuracySMS)
TLTEST_STUB_ROLLING_0(L2PrefetchAccuracyStream)
TLTEST_STUB_ROLLING_0(L2PrefetchAccuracyStride)
TLTEST_STUB_ROLLING_0(L2PrefetchAccuracyTP)
TLTEST_STUB_ROLLING_0(L2PrefetchAccuracy)
TLTEST_STUB_ROLLING_0(L2PrefetchCoverageBOP)
TLTEST_STUB_ROLLING_0(L2PrefetchCoveragePBOP)
TLTEST_STUB_ROLLING_0(L2PrefetchCoverageSMS)
TLTEST_STUB_ROLLING_0(L2PrefetchCoverageStream)
TLTEST_STUB_ROLLING_0(L2PrefetchCoverageStride)
TLTEST_STUB_ROLLING_0(L2PrefetchCoverageTP)
TLTEST_STUB_ROLLING_0(L2PrefetchCoverage)
TLTEST_STUB_ROLLING_0(L2PrefetchLate)

#undef TLTEST_STUB_ROLLING_0
EOF
fi

if [[ ! -f "$dst_dir/perfCCT.cpp" ]]; then
cat > "$dst_dir/perfCCT.cpp" <<'EOF'
// VerifyTop builds do not emit PerfCCT sources; keep a stub translation unit.
EOF
fi