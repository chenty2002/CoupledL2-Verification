#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT_ROOT = ROOT / "results" / "coupledl2_verify_bench"
MAX_CYCLE_PATTERN = re.compile(r"^(\s*maximum_cycle\s*=\s*)(\S+)(\s*)$", re.MULTILINE)
BRACKET_CYCLE_PATTERN = re.compile(r"^\[(\d+)\]", re.MULTILINE)
ASSERTION_FAILURE_PATTERN = re.compile(r"^\[(\d+)\].*\[assertion failure\]", re.MULTILINE)
VERILATOR_ASSERTION_PATTERN = re.compile(r"Assertion failed", re.IGNORECASE)


@dataclass(frozen=True)
class Variant:
    key: str
    make_target: str
    label: str


@dataclass
class BuildResult:
    variant: str
    label: str
    make_target: str
    build_ok: bool
    exit_code: int
    duration_seconds: float
    build_log: str
    bundle_dir: str | None
    error: str | None = None


@dataclass
class RunResult:
    variant: str
    label: str
    status: str
    counterexample_found: bool
    exit_code: int
    last_observed_cycle: int | None
    counterexample_cycle: int | None
    wall_seconds: float | None
    user_seconds: float | None
    system_seconds: float | None
    cpu_seconds: float | None
    cpu_percent: float | None
    max_rss_kb: int | None
    verify_max_cycle: int
    wall_timeout_seconds: int
    work_dir: str
    sim_log: str
    time_log: str
    error: str | None = None


VARIANTS: tuple[Variant, ...] = (
    Variant("0712", "coupledL2-verify-0712", "TileLink directory 0712"),
    Variant("0508", "coupledL2-verify-0508", "mshrCtl 0508"),
    Variant("0531", "coupledL2-verify-0531", "mshrCtl 0531"),
    Variant("0607", "coupledL2-verify-0607", "mshrCtl 0607"),
    Variant("0621", "coupledL2-verify-0621", "mshrCtl 0621"),
    Variant("1017", "coupledL2-verify-1017", "consistency HuanCun 1017"),
)

VARIANT_MAP = {variant.key: variant for variant in VARIANTS}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Build, package, and benchmark the six CoupledL2 verify variants "
            "with isolated run directories and unified timing/memory reports."
        )
    )
    parser.add_argument(
        "--variants",
        default="all",
        help="Comma-separated subset such as 0621,1017, or 'all'",
    )
    parser.add_argument(
        "--output-dir",
        default="",
        help="Result directory. Default: results/coupledl2_verify_bench/<timestamp>",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Reuse existing packaged bundles in the output directory",
    )
    parser.add_argument(
        "--threads-build",
        type=int,
        default=64,
        help="THREADS_BUILD passed to make during sequential bundle builds",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=6,
        help="Parallel simulation jobs",
    )
    parser.add_argument(
        "--verify-max-cycle",
        type=int,
        default=0,
        help="Value written to tltest.ini maximum_cycle for each run",
    )
    parser.add_argument(
        "--wall-time-seconds",
        type=int,
        default=24 * 60 * 60,
        help="Wall-clock timeout per simulation process",
    )
    return parser.parse_args()


def selected_variants(spec: str) -> list[Variant]:
    if spec.strip().lower() == "all":
        return list(VARIANTS)

    variants: list[Variant] = []
    for key in (item.strip() for item in spec.split(",")):
        if not key:
            continue
        if key not in VARIANT_MAP:
            raise SystemExit(f"Unknown variant '{key}'. Choices: all, " + ", ".join(VARIANT_MAP))
        variants.append(VARIANT_MAP[key])
    if not variants:
        raise SystemExit("No variants selected")
    return variants


def timestamp_dir() -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return DEFAULT_OUTPUT_ROOT / stamp


def ensure_output_dir(path_arg: str) -> Path:
    output_dir = Path(path_arg).resolve() if path_arg else timestamp_dir()
    output_dir.mkdir(parents=True, exist_ok=True)
    return output_dir


def variant_dir(output_dir: Path, variant: Variant) -> Path:
    return output_dir / variant.key


def bundle_dir(output_dir: Path, variant: Variant) -> Path:
    return variant_dir(output_dir, variant) / "bundle"


def run_dir(output_dir: Path, variant: Variant) -> Path:
    return variant_dir(output_dir, variant) / "run"


def summary_paths(output_dir: Path) -> tuple[Path, Path, Path]:
    return (
        output_dir / "summary.json",
        output_dir / "summary.csv",
        output_dir / "summary.txt",
    )


def shell(command: str, cwd: Path, log_path: Path) -> subprocess.CompletedProcess[str]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log_file:
        return subprocess.run(
            ["bash", "-lc", command],
            cwd=cwd,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )


def copy_bundle_files(target_dir: Path) -> None:
    source_dir = ROOT / "main" / "build"
    target_dir.mkdir(parents=True, exist_ok=True)
    for name in ("tltest_v3lt", "tltest_portgen.so", "tltest.ini"):
        source_path = source_dir / name
        if not source_path.exists():
            raise FileNotFoundError(f"Missing build artifact: {source_path}")
        shutil.copy2(source_path, target_dir / name)


def build_variant(variant: Variant, output_dir: Path, threads_build: int, verify_max_cycle: int) -> BuildResult:
    build_log = variant_dir(output_dir, variant) / "build.log"
    start = time.monotonic()
    command = (
        "source ~/.bashrc && "
        f"make {variant.make_target}-v3 "
        f"VERIFY_MAX_CYCLE={verify_max_cycle} "
        f"THREADS_BUILD={threads_build}"
    )
    completed = shell(command, ROOT, build_log)
    duration = time.monotonic() - start

    bundle = bundle_dir(output_dir, variant)
    error = None
    build_ok = completed.returncode == 0

    if build_ok:
        try:
            if bundle.exists():
                shutil.rmtree(bundle)
            copy_bundle_files(bundle)
        except Exception as exc:  # pragma: no cover
            build_ok = False
            error = str(exc)
    else:
        error = f"make exited with code {completed.returncode}"

    return BuildResult(
        variant=variant.key,
        label=variant.label,
        make_target=variant.make_target,
        build_ok=build_ok,
        exit_code=completed.returncode,
        duration_seconds=duration,
        build_log=str(build_log),
        bundle_dir=str(bundle) if build_ok else None,
        error=error,
    )


def rewrite_max_cycle(config_path: Path, verify_max_cycle: int) -> None:
    text = config_path.read_text(encoding="utf-8")
    updated, count = MAX_CYCLE_PATTERN.subn(rf"\g<1>{verify_max_cycle}\g<3>", text, count=1)
    if count != 1:
        raise RuntimeError(f"Failed to rewrite maximum_cycle in {config_path}")
    config_path.write_text(updated, encoding="utf-8")


def parse_wall_seconds(raw_value: str) -> float | None:
    text = raw_value.strip()
    if not text:
        return None

    day_part = 0
    if "-" in text:
        prefix, suffix = text.split("-", 1)
        if prefix.isdigit():
            day_part = int(prefix)
            text = suffix

    parts = text.split(":")
    try:
        if len(parts) == 3:
            hours = int(parts[0])
            minutes = int(parts[1])
            seconds = float(parts[2])
        elif len(parts) == 2:
            hours = 0
            minutes = int(parts[0])
            seconds = float(parts[1])
        else:
            return float(text)
    except ValueError:
        return None

    return day_part * 86400 + hours * 3600 + minutes * 60 + seconds


def parse_time_log(time_path: Path) -> dict[str, float | int | None]:
    metrics: dict[str, float | int | None] = {
        "wall_seconds": None,
        "user_seconds": None,
        "system_seconds": None,
        "cpu_seconds": None,
        "cpu_percent": None,
        "max_rss_kb": None,
    }
    if not time_path.exists():
        return metrics

    prefixes = {
        "User time (seconds)": "user_seconds",
        "System time (seconds)": "system_seconds",
        "Percent of CPU this job got": "cpu_percent",
        "Elapsed (wall clock) time (h:mm:ss or m:ss)": "wall_seconds",
        "Maximum resident set size (kbytes)": "max_rss_kb",
    }

    for line in time_path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        for prefix, field in prefixes.items():
            needle = prefix + ":"
            if not stripped.startswith(needle):
                continue
            value = stripped[len(needle):].strip()
            if field in {"user_seconds", "system_seconds"}:
                metrics[field] = float(value)
            elif field == "cpu_percent":
                metrics[field] = float(value.rstrip("%"))
            elif field == "wall_seconds":
                metrics[field] = parse_wall_seconds(value)
            elif field == "max_rss_kb":
                metrics[field] = int(value)
            break

    user_seconds = metrics["user_seconds"] or 0.0
    system_seconds = metrics["system_seconds"] or 0.0
    if metrics["user_seconds"] is not None or metrics["system_seconds"] is not None:
        metrics["cpu_seconds"] = user_seconds + system_seconds
    return metrics


def parse_cycles(log_path: Path) -> tuple[int | None, int | None, bool]:
    if not log_path.exists():
        return None, None, False

    text = log_path.read_text(encoding="utf-8", errors="replace")
    all_cycles = [int(match.group(1)) for match in BRACKET_CYCLE_PATTERN.finditer(text)]
    failure_cycles = [int(match.group(1)) for match in ASSERTION_FAILURE_PATTERN.finditer(text)]
    last_cycle = max(all_cycles) if all_cycles else None
    verilator_assertion = bool(VERILATOR_ASSERTION_PATTERN.search(text))

    first_failure_cycle = failure_cycles[0] if failure_cycles else None
    if verilator_assertion and (first_failure_cycle is None or first_failure_cycle == 0) and last_cycle is not None:
        first_failure_cycle = last_cycle

    return last_cycle, first_failure_cycle, bool(failure_cycles) or verilator_assertion


def prepare_run_workspace(output_dir: Path, variant: Variant, verify_max_cycle: int) -> Path:
    src = bundle_dir(output_dir, variant)
    if not src.exists():
        raise FileNotFoundError(f"Missing bundle for {variant.key}: {src}")

    dst = run_dir(output_dir, variant)
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    rewrite_max_cycle(dst / "tltest.ini", verify_max_cycle)
    return dst


def classify_status(exit_code: int, counterexample_found: bool) -> str:
    if exit_code == 124:
        return "timeout"
    if exit_code == 137:
        return "timeout-killed"
    if counterexample_found:
        return "counterexample"
    if exit_code == 0:
        return "completed"
    return "runtime-error"


def run_variant(variant: Variant, output_dir: Path, verify_max_cycle: int, wall_timeout_seconds: int) -> RunResult:
    work = prepare_run_workspace(output_dir, variant, verify_max_cycle)
    sim_log = variant_dir(output_dir, variant) / "sim.log"
    time_log = variant_dir(output_dir, variant) / "time.log"
    command = (
        "source ~/.bashrc && "
        "/usr/bin/time -v -o time.log "
        f"timeout --foreground --signal=TERM --kill-after=30s {wall_timeout_seconds}s "
        "./tltest_v3lt > sim.log 2>&1"
    )
    completed = shell(command, work, variant_dir(output_dir, variant) / "run-driver.log")

    produced_sim_log = work / "sim.log"
    produced_time_log = work / "time.log"
    if produced_sim_log.exists():
        shutil.copy2(produced_sim_log, sim_log)
    if produced_time_log.exists():
        shutil.copy2(produced_time_log, time_log)

    metrics = parse_time_log(time_log)
    last_cycle, counterexample_cycle, counterexample_found = parse_cycles(sim_log)
    status = classify_status(completed.returncode, counterexample_found)
    error = None
    if status == "runtime-error":
        error = f"simulation exited with code {completed.returncode}"

    return RunResult(
        variant=variant.key,
        label=variant.label,
        status=status,
        counterexample_found=counterexample_found,
        exit_code=completed.returncode,
        last_observed_cycle=last_cycle,
        counterexample_cycle=counterexample_cycle,
        wall_seconds=metrics["wall_seconds"],
        user_seconds=metrics["user_seconds"],
        system_seconds=metrics["system_seconds"],
        cpu_seconds=metrics["cpu_seconds"],
        cpu_percent=metrics["cpu_percent"],
        max_rss_kb=metrics["max_rss_kb"],
        verify_max_cycle=verify_max_cycle,
        wall_timeout_seconds=wall_timeout_seconds,
        work_dir=str(work),
        sim_log=str(sim_log),
        time_log=str(time_log),
        error=error,
    )


def run_parallel(variants: Iterable[Variant], output_dir: Path, verify_max_cycle: int, wall_timeout_seconds: int, jobs: int) -> list[RunResult]:
    import concurrent.futures

    results: list[RunResult] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, jobs)) as executor:
        future_map = {
            executor.submit(run_variant, variant, output_dir, verify_max_cycle, wall_timeout_seconds): variant
            for variant in variants
        }
        for future in concurrent.futures.as_completed(future_map):
            variant = future_map[future]
            try:
                result = future.result()
            except Exception as exc:  # pragma: no cover
                result = RunResult(
                    variant=variant.key,
                    label=variant.label,
                    status="runtime-error",
                    counterexample_found=False,
                    exit_code=255,
                    last_observed_cycle=None,
                    counterexample_cycle=None,
                    wall_seconds=None,
                    user_seconds=None,
                    system_seconds=None,
                    cpu_seconds=None,
                    cpu_percent=None,
                    max_rss_kb=None,
                    verify_max_cycle=verify_max_cycle,
                    wall_timeout_seconds=wall_timeout_seconds,
                    work_dir=str(run_dir(output_dir, variant)),
                    sim_log=str(variant_dir(output_dir, variant) / "sim.log"),
                    time_log=str(variant_dir(output_dir, variant) / "time.log"),
                    error=str(exc),
                )
            results.append(result)
            print(
                f"[run] {result.variant}: status={result.status}, exit={result.exit_code}, "
                f"cycle={result.counterexample_cycle or result.last_observed_cycle}, "
                f"wall={result.wall_seconds}"
            )
            sys.stdout.flush()
    return sorted(results, key=lambda item: item.variant)


def write_summary(output_dir: Path, build_results: list[BuildResult], run_results: list[RunResult]) -> None:
    build_map = {result.variant: result for result in build_results}
    rows: list[dict[str, object]] = []

    for run_result in run_results:
        build_result = build_map.get(run_result.variant)
        row = {
            "variant": run_result.variant,
            "label": run_result.label,
            "make_target": build_result.make_target if build_result else "",
            "build_ok": build_result.build_ok if build_result else "",
            "build_exit_code": build_result.exit_code if build_result else "",
            "build_duration_seconds": build_result.duration_seconds if build_result else "",
            "status": run_result.status,
            "counterexample_found": run_result.counterexample_found,
            "exit_code": run_result.exit_code,
            "last_observed_cycle": run_result.last_observed_cycle,
            "counterexample_cycle": run_result.counterexample_cycle,
            "verify_max_cycle": run_result.verify_max_cycle,
            "wall_timeout_seconds": run_result.wall_timeout_seconds,
            "wall_seconds": run_result.wall_seconds,
            "user_seconds": run_result.user_seconds,
            "system_seconds": run_result.system_seconds,
            "cpu_seconds": run_result.cpu_seconds,
            "cpu_percent": run_result.cpu_percent,
            "max_rss_kb": run_result.max_rss_kb,
            "bundle_dir": build_result.bundle_dir if build_result else "",
            "build_log": build_result.build_log if build_result else "",
            "work_dir": run_result.work_dir,
            "sim_log": run_result.sim_log,
            "time_log": run_result.time_log,
            "error": run_result.error or (build_result.error if build_result else None),
        }
        rows.append(row)

    summary_json, summary_csv, summary_txt = summary_paths(output_dir)
    summary_json.write_text(
        json.dumps(
            {
                "root": str(output_dir),
                "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "build_results": [asdict(result) for result in build_results],
                "run_results": [asdict(result) for result in run_results],
                "rows": rows,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    fieldnames = list(rows[0].keys()) if rows else []
    with summary_csv.open("w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=fieldnames)
        if fieldnames:
            writer.writeheader()
            writer.writerows(rows)

    lines = [
        f"output_dir: {output_dir}",
        "variant status counterexample cycle wall_seconds cpu_seconds max_rss_kb",
    ]
    for row in rows:
        lines.append(
            "{variant} {status} {counterexample_found} {cycle} {wall} {cpu} {rss}".format(
                variant=row["variant"],
                status=row["status"],
                counterexample_found=row["counterexample_found"],
                cycle=row["counterexample_cycle"] or row["last_observed_cycle"],
                wall=row["wall_seconds"],
                cpu=row["cpu_seconds"],
                rss=row["max_rss_kb"],
            )
        )
    summary_txt.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    variants = selected_variants(args.variants)
    output_dir = ensure_output_dir(args.output_dir)

    print(f"[info] repo_root={ROOT}")
    print(f"[info] output_dir={output_dir}")
    print(f"[info] variants={','.join(variant.key for variant in variants)}")
    print(f"[info] verify_max_cycle={args.verify_max_cycle}")
    print(f"[info] wall_timeout_seconds={args.wall_time_seconds}")
    sys.stdout.flush()

    build_results: list[BuildResult] = []
    if not args.skip_build:
        for variant in variants:
            print(f"[build] {variant.key}: start")
            sys.stdout.flush()
            result = build_variant(variant, output_dir, args.threads_build, args.verify_max_cycle)
            build_results.append(result)
            print(
                f"[build] {variant.key}: ok={result.build_ok}, exit={result.exit_code}, "
                f"duration={result.duration_seconds:.2f}s"
            )
            sys.stdout.flush()
    else:
        for variant in variants:
            bundle = bundle_dir(output_dir, variant)
            build_results.append(
                BuildResult(
                    variant=variant.key,
                    label=variant.label,
                    make_target=variant.make_target,
                    build_ok=bundle.exists(),
                    exit_code=0 if bundle.exists() else 1,
                    duration_seconds=0.0,
                    build_log=str(variant_dir(output_dir, variant) / "build.log"),
                    bundle_dir=str(bundle) if bundle.exists() else None,
                    error=None if bundle.exists() else f"bundle not found: {bundle}",
                )
            )

    runnable_variants = [variant for variant in variants if bundle_dir(output_dir, variant).exists()]
    if not runnable_variants:
        write_summary(output_dir, build_results, [])
        print("[error] no runnable variants available")
        return 1

    run_results = run_parallel(
        runnable_variants,
        output_dir,
        args.verify_max_cycle,
        args.wall_time_seconds,
        args.jobs,
    )
    write_summary(output_dir, build_results, run_results)

    failed_builds = [result for result in build_results if not result.build_ok]
    failed_runs = [result for result in run_results if result.status not in {"completed", "timeout", "counterexample"}]
    if failed_builds or failed_runs:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())