#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ASSERT_OR_WRITE_TOKENS = ("assert(", "$fwrite(", "$error(", "$fatal")
PRINTF_IFDEF = "`ifdef PRINTF_COND"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Remove non-formal assertion/printf blocks from VerifyTop-style SV files."
    )
    parser.add_argument(
        "--mode",
        required=True,
        choices=("resetcounter-only", "property-only"),
        help="Sanitization strategy for the target file",
    )
    parser.add_argument(
        "--in-place",
        action="store_true",
        help="Rewrite files in place. Without this flag, only validates and prints stats.",
    )
    parser.add_argument("paths", nargs="+", help="Files to sanitize")
    return parser.parse_args()


def find_matching_end(lines: list[str], start: int) -> int | None:
    depth = 0
    for index in range(start, len(lines)):
        line = lines[index]
        depth += len(re.findall(r"\bbegin\b", line))
        depth -= len(re.findall(r"\bend\b", line))
        if depth <= 0:
            return index
    return None


def find_statement_end(lines: list[str], start: int) -> int:
    end = start
    while end < len(lines) and ";" not in lines[end]:
        end += 1
    return min(end, len(lines) - 1)


def find_if_end(lines: list[str], start: int) -> int | None:
    for index in range(start, len(lines)):
        line = lines[index]
        if "begin" in line:
            return find_matching_end(lines, index)
        if ";" in line:
            return index
    return None


def contains_action(text: str) -> bool:
    return any(token in text for token in ASSERT_OR_WRITE_TOKENS)


def detect_printf_wrapper(lines: list[str], start: int) -> int | None:
    if lines[start].strip() != PRINTF_IFDEF:
        return None
    if start + 5 >= len(lines):
        return None

    open_line = lines[start + 1].strip()
    if open_line != "if (`PRINTF_COND) begin":
        return None
    if lines[start + 2].strip() != "`endif":
        return None

    inner_start = start + 3
    inner_line = lines[inner_start].lstrip()
    if not inner_line.startswith("if "):
        return None

    if "begin" in inner_line:
        inner_end = find_matching_end(lines, inner_start)
    else:
        inner_end = find_statement_end(lines, inner_start)
    if inner_end is None:
        return None

    close = inner_end + 1
    if close + 2 >= len(lines):
        return None
    if lines[close].strip() != PRINTF_IFDEF:
        return None
    if lines[close + 1].strip() != "end":
        return None
    if lines[close + 2].strip() != "`endif":
        return None
    return close + 2


def detect_if_block(lines: list[str], start: int) -> int | None:
    line = lines[start].lstrip()
    if not line.startswith("if "):
        return None
    if "assert property" in line:
        return None

    end = find_if_end(lines, start)
    if end is None:
        return None

    block = "".join(lines[start : end + 1])
    return end if contains_action(block) else None


def sanitize_resetcounter_only(text: str) -> tuple[str, int]:
    lines = text.splitlines(keepends=True)
    output: list[str] = []
    removed = 0
    index = 0

    while index < len(lines):
        wrapper_end = detect_printf_wrapper(lines, index)
        if wrapper_end is not None:
            block = "".join(lines[index : wrapper_end + 1])
            if contains_action(block) and "resetCounter_notChaos" not in block:
                removed += 1
                index = wrapper_end + 1
                continue
            output.extend(lines[index : wrapper_end + 1])
            index = wrapper_end + 1
            continue

        block_end = detect_if_block(lines, index)
        if block_end is not None:
            block = "".join(lines[index : block_end + 1])
            if "resetCounter_notChaos" not in block:
                removed += 1
                index = block_end + 1
                continue
            output.extend(lines[index : block_end + 1])
            index = block_end + 1
            continue

        output.append(lines[index])
        index += 1

    return "".join(output), removed


ASSERT_VERBOSE_RE = re.compile(r"^\s*if\s*\(`ASSERT_VERBOSE_COND_\)")
STOP_COND_RE = re.compile(r"^\s*if\s*\(`STOP_COND_\)")
PROPERTY_ONLY_REMOVE_TOKENS = ("`ASSERT_VERBOSE_COND_", "`STOP_COND_", "$error(", "$fatal")


def detect_property_only_if_block(lines: list[str], start: int) -> int | None:
    line = lines[start].lstrip()
    if not line.startswith("if "):
        return None
    if "assert property" in line:
        return None

    end = find_if_end(lines, start)
    if end is None:
        return None

    header_end = start
    while header_end < end and "begin" not in lines[header_end] and ";" not in lines[header_end]:
        header_end += 1
    header = "".join(lines[start : header_end + 1])
    if any(token in header for token in PROPERTY_ONLY_REMOVE_TOKENS):
        return end
    return None


def sanitize_property_only(text: str) -> tuple[str, int]:
    lines = text.splitlines(keepends=True)
    output: list[str] = []
    removed = 0
    index = 0

    while index < len(lines):
        block_end = detect_property_only_if_block(lines, index)
        if block_end is not None:
            removed += 1
            index = block_end + 1
            continue

        line = lines[index]
        if ASSERT_VERBOSE_RE.match(line):
            end = find_matching_end(lines, index) if "begin" in line else index + 1
            if end < len(lines):
                block = "".join(lines[index : end + 1])
            else:
                block = line
            if "$error(" in block:
                removed += 1
                index = end + 1
                continue
        if STOP_COND_RE.match(line):
            end = find_matching_end(lines, index) if "begin" in line else index + 1
            if end < len(lines):
                block = "".join(lines[index : end + 1])
            else:
                block = line
            if "$fatal" in block:
                removed += 1
                index = end + 1
                continue
        output.append(line)
        index += 1

    return "".join(output), removed


def sanitize_text(text: str, mode: str) -> tuple[str, int]:
    if mode == "resetcounter-only":
        return sanitize_resetcounter_only(text)
    if mode == "property-only":
        return sanitize_property_only(text)
    raise ValueError(f"Unsupported mode: {mode}")


def main() -> int:
    args = parse_args()
    exit_code = 0

    for raw_path in args.paths:
        path = Path(raw_path)
        original = path.read_text(encoding="utf-8")
        sanitized, removed = sanitize_text(original, args.mode)

        if args.in_place and sanitized != original:
            path.write_text(sanitized, encoding="utf-8")

        status = "updated" if sanitized != original else "unchanged"
        print(f"{path}: {status}, removed_blocks={removed}")

        if not args.in_place and sanitized != original:
            exit_code = 1

    return exit_code


if __name__ == "__main__":
    sys.exit(main())