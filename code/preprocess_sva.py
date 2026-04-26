#!/usr/bin/env python3
"""
Preprocess deadlock-ablation VerifyTop_*.sv files in CoupledL2 Verilog directories.

Converts timer-based assertions from:
    if (condition) begin
      assert(timers_N <= value);
    end

To unbounded SVA eventuality properties for the "w/o bounded liveness as safety
primitive" ablation:
        Timer_liveness_property_N:
            assert property (@(posedge clock) disable iff (~hasBeenReset) condition
                                             |-> ##[0:$] (timers_N == 64'h0));

Also inserts hasBeenReset infrastructure once per module:
    reg          hasBeenResetReg;
    initial
      hasBeenResetReg = 1'bx;
    wire         hasBeenReset = hasBeenResetReg === 1'h1 & reset === 1'h0;
And in the first if(reset) begin block:
      hasBeenResetReg <= 1'h1;

Usage:
    python preprocess_sva.py [--dry-run]
    python preprocess_sva.py --case XiangShan-CoupledL2-deadlock-v0
    python preprocess_sva.py --case all
"""

from __future__ import annotations

import glob
import os
import re
import shutil
import sys
import argparse
from typing import Dict, List, Optional, Tuple

ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CASE_DIRS: Dict[str, str] = {
    "XiangShan-CoupledL2-deadlock-v0": "XiangShan-CoupledL2-deadlock-v0",
    "XiangShan-CoupledL2-deadlock-v1": "XiangShan-CoupledL2-deadlock-v1",
    "XiangShan-CoupledL2-deadlock-v2": "XiangShan-CoupledL2-deadlock-v2",
    "XiangShan-CoupledL2-deadlock-v3": "XiangShan-CoupledL2-deadlock-v3",
    "XiangShan-CoupledL2-deadlock-v4": "XiangShan-CoupledL2-deadlock-v4",
}


def normalize_root(root: str) -> str:
    root = os.path.abspath(root)
    if os.path.basename(root) == "code":
        return root
    code_root = os.path.join(root, "code")
    if os.path.isdir(code_root):
        return code_root
    return root


def resolve_case_dirs(root: str, requested_case: str) -> List[Tuple[str, str]]:
    """Resolve selected deadlock case names to directories under the code root."""
    if requested_case == "all":
        case_names = list(DEFAULT_CASE_DIRS.keys())
    else:
        case_names = [requested_case]

    resolved = []
    for case_name in case_names:
        if case_name not in DEFAULT_CASE_DIRS:
            raise ValueError(
                f"Unsupported case '{case_name}'. Expected one of: "
                f"{', '.join(DEFAULT_CASE_DIRS.keys())}, all"
            )
        case_dir = os.path.join(root, DEFAULT_CASE_DIRS[case_name])
        if not os.path.isdir(case_dir):
            print(f"  [SKIP] Missing case directory: {case_dir}")
            continue
        resolved.append((case_name, case_dir))
    return resolved


def find_deadlock_sv_files(root: str, requested_case: str) -> List[str]:
    """Find VerifyTop_*.sv files in the selected deadlock case directories."""
    result = []
    for case_name, case_dir in resolve_case_dirs(root, requested_case):
        vdir = os.path.join(case_dir, "Verilog")
        if not os.path.isdir(vdir):
            continue
        sv_files = glob.glob(os.path.join(vdir, "VerifyTop*.sv"))
        if len(sv_files) == 0:
            print(f"  [SKIP] No VerifyTop*.sv in {case_name}: {vdir}")
        elif len(sv_files) == 1:
            result.append(sv_files[0])
        else:
            print(f"  [WARN] Multiple VerifyTop*.sv in {case_name}: {sv_files}")
    return result


def find_module_with_timer_asserts(lines: List[str]) -> Tuple[Optional[int], Optional[str]]:
    """
    Return (module_start_line_idx, module_name) for the first module that
    contains 'assert(timers_' lines.
    """
    timer_idx = next((i for i, l in enumerate(lines) if "assert(timers_" in l), None)
    if timer_idx is None:
        return None, None
    for i in range(timer_idx, -1, -1):
        m = re.match(r"^module (\w+)", lines[i])
        if m:
            return i, m.group(1)
    return None, None


def find_timer_assert_always_block(
    lines: List[str], search_start: int
) -> Tuple[Optional[int], Optional[int], Optional[List]]:
    """
    Find the always@(posedge clock) block that contains only timer assertions.

        Returns (start_idx, end_idx, asserts) where:
      - start_idx: line index of 'always @(posedge clock) begin'
      - end_idx:   line index of the closing 'end' of that always block
            - asserts:   list of (condition_str, timer_name_str, bound_str) tuples

    Returns (None, None, None) if not found or block is not a pure timer-assert block.
    """
    # Find the first timer-assert line at or after search_start
    first_timer_idx = next(
        (i for i in range(search_start, len(lines)) if "assert(timers_" in lines[i]),
        None,
    )
    if first_timer_idx is None:
        return None, None, None

    # Walk backward to find the enclosing always block
    always_start = None
    for i in range(first_timer_idx, search_start - 1, -1):
        if re.match(r"\s*always @\(posedge clock\) begin\s*$", lines[i]):
            always_start = i
            break
    if always_start is None:
        return None, None, None

    # Parse the block line by line
    asserts = []
    i = always_start + 1
    end_idx = None

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # Closing 'end' of the always block
        if stripped == "end":
            end_idx = i
            break

        # Skip blank or comment-only lines
        if stripped == "" or stripped == "//":
            i += 1
            continue

        # Expect: if (CONDITION) begin
        m_if = re.match(r"\s+if \((.+)\) begin\s*$", line)
        if m_if:
            condition = m_if.group(1)
            i += 1
            # Expect: assert(timers_N <= VALUE); [optional comment]
            m_assert = re.match(r"\s+assert\((timers_\d+) <= ([^)]+)\);", lines[i])
            if m_assert:
                asserts.append((condition, m_assert.group(1), m_assert.group(2).strip()))
                i += 1
                # Expect inner 'end'
                if lines[i].strip() == "end":
                    i += 1
                continue
            else:
                # Not a pure timer-assert block
                return None, None, None
        else:
            # Unexpected content — not a pure timer-assert block
            return None, None, None

    if end_idx is None:
        return None, None, None

    return always_start, end_idx, asserts


def parse_verilog_int_literal(value: str) -> Optional[int]:
    """Parse simple Verilog integer literals like 64'h3e8 into Python ints."""
    value = value.strip().lower().replace("_", "")
    m = re.fullmatch(r"(?:\d+)?'([bdho])([0-9a-fxz?]+)", value)
    if m:
        base_map = {"b": 2, "d": 10, "h": 16, "o": 8}
        digits = m.group(2)
        if any(ch in digits for ch in "xz?"):
            return None
        return int(digits, base_map[m.group(1)])
    if re.fullmatch(r"\d+", value):
        return int(value, 10)
    return None


def find_first_reset_insert_point(
    lines: List[str], module_start: int, before_idx: int
) -> Optional[int]:
    """
    Return the line index of the first 'if (reset) begin' statement in the
    module (between module_start+1 and before_idx).
    hasBeenResetReg <= 1'h1; will be inserted AFTER this line.
    """
    for i in range(module_start + 1, before_idx):
        if re.match(r"\s+if \(reset\) begin", lines[i]):
            return i
    return None


def process_file(sv_path: str, dry_run: bool = False) -> bool:
    """
    Process a single VerifyTop_*.sv file.
    Returns True if the file was (or would be) modified, False otherwise.
    """
    print(f"\n{'[DRY-RUN] ' if dry_run else ''}Processing: {sv_path}")

    with open(sv_path, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    bak = sv_path + ".bak"

    # Already processed guard
    if any("hasBeenResetReg" in l for l in lines):
        if any("|-> ##[" in l for l in lines):
            print("  [SKIP] Already processed.")
            return False
        if os.path.exists(bak):
            print(f"  [INFO] Reprocessing from backup: {bak}")
            with open(bak, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
        else:
            print("  [SKIP] Already processed, but no backup exists for migration.")
            return False

    if not any("assert(timers_" in l for l in lines):
        print("  [SKIP] No timer asserts found.")
        return False

    # ----------------------------------------------------------------
    # Find module containing timer asserts
    # ----------------------------------------------------------------
    module_start, module_name = find_module_with_timer_asserts(lines)
    if module_start is None:
        print("  [ERROR] Could not find module containing timer asserts.")
        return False
    print(f"  Module : {module_name} (line {module_start + 1})")

    # ----------------------------------------------------------------
    # Find the timer-assert always block
    # ----------------------------------------------------------------
    ta_start, ta_end, asserts = find_timer_assert_always_block(lines, module_start)
    if ta_start is None or not asserts:
        print("  [ERROR] Could not find/parse the timer-assert always block.")
        return False
    print(
        f"  Timer-assert block: lines {ta_start + 1}–{ta_end + 1}  "
        f"({len(asserts)} assertions)"
    )

    # ----------------------------------------------------------------
    # Find insertion point for hasBeenResetReg <= 1'h1
    # ----------------------------------------------------------------
    reset_insert_after = find_first_reset_insert_point(lines, module_start, ta_start)
    if reset_insert_after is None:
        print(
            "  [WARN] Could not find if(reset) begin for hasBeenResetReg insertion. "
            "The reg will be declared but not driven on reset."
        )
    else:
        print(f"  hasBeenResetReg assignment: after line {reset_insert_after + 1}")

    # ----------------------------------------------------------------
    # Generate unbounded SVA property lines for the ablation flow
    # ----------------------------------------------------------------
    sva_props: List[str] = []
    for idx, (condition, timer_name, bound_value) in enumerate(asserts):
        label = f"Timer_liveness_property_{idx}"
        bound_cycles = parse_verilog_int_literal(bound_value)
        if bound_cycles is None:
            raise ValueError(f"Unsupported timer bound literal: {bound_value}")
        sva_props.append(
            f"  {label}: assert property (@(posedge clock) "
            f"disable iff (~hasBeenReset) {condition} "
            f"|-> ##[0:$] ({timer_name} == 64'h0));\n"
        )

    # ----------------------------------------------------------------
    # Build the new file
    # ----------------------------------------------------------------
    new_lines: List[str] = []
    i = 0
    while i < len(lines):
        # Insert hasBeenResetReg <= 1'h1 right after the if(reset) begin line
        if reset_insert_after is not None and i == reset_insert_after:
            new_lines.append(lines[i])              # if (reset) begin line
            new_lines.append("      hasBeenResetReg <= 1'h1;\n")
            i += 1
            continue

        # Replace the timer-assert always block with declarations + SVA props
        if i == ta_start:
            # hasBeenReset declarations
            new_lines.append("  reg          hasBeenResetReg;\n")
            new_lines.append("  initial\n")
            new_lines.append("    hasBeenResetReg = 1'bx;\n")
            new_lines.append(
                "  wire         hasBeenReset = hasBeenResetReg === 1'h1 & reset === 1'h0;\n"
            )
            # SVA concurrent assertions
            new_lines.extend(sva_props)
            # Skip all lines of the timer-assert always block (inclusive)
            i = ta_end + 1
            continue

        new_lines.append(lines[i])
        i += 1

    # ----------------------------------------------------------------
    # Write (or just report for dry-run)
    # ----------------------------------------------------------------
    added = len(new_lines) - len(lines)
    print(
        f"  Lines: {len(lines)} → {len(new_lines)}  "
        f"({'+'if added>=0 else ''}{added})"
    )

    if dry_run:
        print("  [DRY-RUN] File not written.")
        return True

    # Backup original
    if not os.path.exists(bak):
        shutil.copy2(sv_path, bak)
        print(f"  Backup : {bak}")
    else:
        print(f"  Backup already exists: {bak}")

    with open(sv_path, "w", encoding="utf-8") as f:
        f.writelines(new_lines)

    print("  Done.")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show what would be done without writing any files."
    )
    parser.add_argument(
        "--root", default=ROOT,
        help="Repository root or code root to search (default: script directory)"
    )
    parser.add_argument(
        "--case", default="all",
        help=(
            "Deadlock case to preprocess. "
            "Use one of XiangShan-CoupledL2-deadlock-v0..v4 or all "
            "(default: all)."
        )
    )
    args = parser.parse_args()

    code_root = normalize_root(args.root)
    try:
        sv_files = find_deadlock_sv_files(code_root, args.case)
    except ValueError as exc:
        print(exc)
        sys.exit(1)

    if not sv_files:
        print("No deadlock VerifyTop*.sv files found.")
        sys.exit(1)

    print(f"Found {len(sv_files)} file(s) to process:")
    for f in sv_files:
        print(f"  {f}")

    modified = 0
    for sv_path in sv_files:
        if process_file(sv_path, dry_run=args.dry_run):
            modified += 1

    print(f"\nPreprocessing complete: {modified}/{len(sv_files)} file(s) modified.")


if __name__ == "__main__":
    main()
