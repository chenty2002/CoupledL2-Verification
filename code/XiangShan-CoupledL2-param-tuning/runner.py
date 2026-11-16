#!/usr/bin/env python3
"""Run one ordered candidate with isolated Mill and JG evidence."""

import argparse
import hashlib
import itertools
import json
import os
import signal
import subprocess
import time
from pathlib import Path

from search import FEASIBLE, PROJECT, PROPERTY_NAMES, RUNS, VERILOG, inspect, parse_config, prepare


TCL = VERILOG / "verify.tcl"
MILL = Path.home() / "mill"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_resume(saved, current, output):
    provenance = ("candidate", "source_sha256", "source_state", "submodule_revisions",
                  "tcl_sha256", "helper_sha256")
    if any(saved.get(key) != current[key] for key in provenance):
        raise ValueError("resume provenance differs from current sources")
    if saved.get("sv_sha256") != sha(output):
        raise ValueError("resume SV differs from saved generation evidence")


def git_revision(path):
    subprocess.check_call(["git", "-C", str(path), "rev-parse", "--is-inside-work-tree"],
                          stdout=subprocess.DEVNULL)
    result = subprocess.run(["git", "-C", str(path), "rev-parse", "--verify", "--quiet", "HEAD"],
                            capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else "uncommitted"


def git_state(path):
    names = subprocess.check_output(["git", "-C", str(path), "ls-files", "-z",
                                     "--cached", "--others", "--exclude-standard", "--", "."])
    tree = hashlib.sha256()
    # Vendored dependencies share the parent Git repository; hash their actual source files.
    for name in sorted(set(names.split(b"\0")) - {b""}):
        file = path / os.fsdecode(name)
        tree.update(name + b"\0")
        if file.is_symlink():
            tree.update(b"link\0" + os.fsencode(os.readlink(file)))
        elif file.exists():
            tree.update(b"file\0" + hashlib.sha256(file.read_bytes()).digest())
        else:
            tree.update(b"deleted\0")
    return {"revision": git_revision(path), "tree_sha256": tree.hexdigest()}


def execute(command, cwd, log, timeout):
    start = time.monotonic()
    with log.open("w") as stream:
        try:
            process = subprocess.Popen(command, cwd=cwd, stdout=stream,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as error:
            stream.write(f"SPAWN_ERROR: {error}\n")
            return -1, time.monotonic() - start
        try:
            return process.wait(timeout=timeout), time.monotonic() - start
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            stream.write(f"\nEXTERNAL_TIMEOUT after {timeout}s\n")
            return None, time.monotonic() - start


def expected_manifest(candidate):
    assertions, covers = set(), set()
    for layer in ("l1", "l2"):
        ways, sets, mshrs = (candidate[f"{layer}.{key}"] for key in
                             ("ways", "sets", "mshrs"))
        for index in range(2):
            suffix = f"__{layer}_{index}"
            for family in ("way_index", "unique_live_tag", "address_decode",
                           "block_boundary", "block_size", "beat_shape", "mshr_capacity",
                           "mshr_progress", "beat_stable"):
                if family != "unique_live_tag":
                    assertions.add(f"VerifyTop.param_{family}{suffix}")
            for channel in ("c", "d"):
                assertions.add(f"VerifyTop.param_beat_stable{suffix}_{channel}")
                assertions.add(f"VerifyTop.param_beat_shape{suffix}_{channel}")
            for way in range(ways):
                assertions.add(f"VerifyTop.param_way_isolation{suffix}_{way}")
            for left, right in itertools.combinations(range(ways), 2):
                assertions.add(f"VerifyTop.param_unique_live_tag{suffix}_{left}_{right}")
            for left, right in itertools.combinations(range(mshrs), 2):
                assertions.add(f"VerifyTop.param_mshr_unique{suffix}_{left}_{right}")
            covers.add(f"VerifyTop.param_cover_two_mshrs{suffix}")
            covers.add(f"VerifyTop.param_cover_two_ways{suffix}")
            if layer == "l2":
                covers.add(f"VerifyTop.param_cover_miss{suffix}")
            covers.update(f"VerifyTop.param_cover_set{suffix}_{set}" for set in range(sets))
    l3_ways = candidate["l3.ways"]
    l3_mshrs = candidate["l3.mshrs"]
    assertions.update(f"VerifyTop.param_{family}__l3" for family in
                      ("way_index", "address_decode", "mshr_capacity", "mshr_progress",
                       "block_boundary", "block_size", "beat_shape", "beat_stable"))
    for channel in ("c", "d"):
        assertions.add(f"VerifyTop.param_beat_stable__l3_{channel}")
        assertions.add(f"VerifyTop.param_beat_shape__l3_{channel}")
    assertions.update(f"VerifyTop.param_way_isolation__l3_{way}"
                      for way in range(l3_ways))
    assertions.update(f"VerifyTop.param_unique_live_tag__l3_{left}_{right}"
                      for left, right in itertools.combinations(range(l3_ways), 2))
    assertions.update(f"VerifyTop.param_mshr_unique__l3_{left}_{right}"
                      for left, right in itertools.combinations(range(l3_mshrs), 2))
    covers.update({"VerifyTop.param_cover_two_ways__l3",
                   "VerifyTop.param_cover_two_mshrs__l3"})
    covers.update(f"VerifyTop.param_cover_set__l3_{set}"
                  for set in range(candidate["l3.sets"]))
    assertions.update({"VerifyTop.param_block_assembly__l3",
                       "VerifyTop.param_block_assembly__l3_pair",
                       "VerifyTop.param_ram_data__readback",
                       "VerifyTop.param_ram_lanes__masked_write"})
    covers.add("VerifyTop.param_cover_ram_readback")
    return assertions, covers


def parse_jg(directory, log, candidate=None):
    raw = log.read_text()
    manifest = directory / "property_manifest.tsv"
    statuses = directory / "property_status.tsv"
    if not manifest.exists():
        return "tool_failed", "JG did not export the property manifest", {}, {}
    entries = [line.split("\t") for line in manifest.read_text().splitlines()]
    if (not entries or any(len(item) != 3 or item[1] not in {"assert", "cover"}
                           for item in entries) or
            len({item[0] for item in entries}) != len(entries)):
        return "property_missing", "malformed or duplicate property manifest", {}, {}
    assertions = [item[0] for item in entries if len(item) >= 2 and item[1] == "assert"]
    covers = [item[0] for item in entries if len(item) >= 2 and item[1] == "cover"]
    if candidate is not None:
        expected_assertions, expected_covers = expected_manifest(candidate)
        if ({name.rsplit("::", 1)[-1] for name in assertions} != expected_assertions or
                {name.rsplit("::", 1)[-1] for name in covers} != expected_covers):
            return "property_missing", "property manifest differs from candidate geometry", {}, {}
    families = {name.split("param_", 1)[1].split("__", 1)[0] for name in assertions
                if "param_" in name}
    if families != {name.removeprefix("param_") for name in PROPERTY_NAMES} or not covers:
        return "property_missing", "incomplete parameter property or cover manifest", {}, {}
    if not statuses.exists() or "ERROR" in raw or "EXTERNAL_TIMEOUT" in raw:
        return "tool_failed", "JG did not finish status export", {}, {}
    lines = [line.split("\t") for line in statuses.read_text().splitlines()]
    if not lines or len(lines[0]) != 2 or lines[0][0] != "prove_result":
        return "unknown", "missing proof return status", {}, {}
    proof_return = lines[0][1]
    if (any(len(item) != 4 for item in lines[1:]) or
            len({item[0] for item in lines[1:]}) != len(lines) - 1):
        return "unknown", "malformed or duplicate property status", {}, {}
    states = {item[0]: (item[1], item[2], item[3]) for item in lines[1:] if len(item) == 4}
    if set(states) != set(assertions + covers):
        return "property_missing", "status list differs from manifest", {}, {}
    if (any(states[name][0] != "assert" for name in assertions) or
            any(states[name][0] != "cover" for name in covers)):
        return "property_missing", "property types differ from manifest", {}, {}
    properties = {name: states[name][1] for name in assertions}
    coverage = {name: {"reached": states[name][1] == "covered"} for name in covers}
    if any(state in {"cex", "ar_cex"} for state in properties.values()):
        return "cex", "parameter assertion has a counterexample", properties, coverage
    if any(states[name][2] != "processed" for name in assertions + covers):
        return "unknown", "target property was not processed", properties, coverage
    if not all(item["reached"] for item in coverage.values()):
        return "unknown", "required parameter cover not reached", properties, coverage
    if proof_return in {"determined", "proven", "covered"} and all(
        state == "proven" for state in properties.values()
    ):
        return "feasible_proved", "all parameter assertions proved", dict.fromkeys(properties, "proved"), coverage
    if proof_return == "time_limit" and all(
        state in {"proven", "undetermined", "bounded_proven_auto", "bounded_proven_user"}
        for state in properties.values()
    ):
        normalized = {name: "proved" if state == "proven" else "bounded_no_cex"
                      for name, state in properties.items()}
        return "bounded_no_cex", "fixed proof time limit reached without counterexample", normalized, coverage
    return "unknown", f"unresolved proof return: {proof_return}", properties, coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("search.json"))
    parser.add_argument("--runs", type=Path, default=RUNS,
                        help="evidence root, a direct subdirectory of this repository's verilog/")
    parser.add_argument("--recheck", action="store_true",
                        help="independently regenerate and verify the selected candidate")
    parser.add_argument("--search", action="store_true",
                        help="run ordered candidates and independent recheck until done or blocked")
    resumes = parser.add_mutually_exclusive_group()
    resumes.add_argument("--resume-generated", action="store_true",
                         help="continue a candidate with existing SV and generate.log")
    resumes.add_argument("--finalize-existing", action="store_true",
                         help="classify an already completed candidate without rerunning tools")
    args = parser.parse_args()
    if args.search and (args.recheck or args.resume_generated or args.finalize_existing):
        parser.error("--search cannot be combined with recheck/resume/finalize")
    ranges, cap = parse_config(args.config)
    runs = args.runs.resolve()
    if runs.parent != VERILOG.resolve():
        parser.error("--runs must be directly under this repository's verilog/ for fixed Tcl paths")
    report = inspect(ranges, cap, runs)
    if args.search:
        (VERILOG / "search_summary.json").write_text(json.dumps(report, indent=2) + "\n")
    recheck = args.recheck or (args.search and report["verdict"] == "pending_recheck")
    row = report["best"] if recheck else report["next"]
    if row is None:
        print(json.dumps(report, indent=2), flush=True)
        return
    if recheck:
        row = dict(row, path=row["path"] + "-recheck")
        Path(row["path"]).mkdir(parents=True, exist_ok=True)
        snapshot = Path(row["path"]) / "candidate.json"
        if snapshot.exists() and json.loads(snapshot.read_text()) != row["candidate"]:
            raise ValueError("conflicting recheck candidate snapshot")
        if not snapshot.exists():
            snapshot.write_text(json.dumps(row["candidate"], indent=2, sort_keys=True) + "\n")
    else:
        prepare(ranges, runs, row)
    directory = Path(row["path"])
    output = directory / "VerifyTop.sv"
    result_path = directory / "result.json"
    if result_path.exists() or (output.exists() and not
                                (args.resume_generated or args.finalize_existing)):
        raise FileExistsError(f"candidate has existing output; preserve and review: {directory}")
    if (args.resume_generated or args.finalize_existing) and (
            not output.is_file() or not (directory / "generate.log").is_file()):
        raise ValueError("resume requires generated SV/log and no JG run")
    if args.resume_generated and (directory / "jg.log").exists():
        raise ValueError("JG evidence exists; use --finalize-existing after it completes")
    if args.finalize_existing and not all((directory / name).is_file() for name in
                                          ("jg.log", "property_manifest.tsv", "property_status.tsv")):
        raise ValueError("finalize requires completed JG evidence")
    start = time.monotonic()
    result = {"candidate": row["candidate"], "rank": row["rank"],
              "source_revision": git_revision(PROJECT),
              "source_sha256": sha(PROJECT / "src/test/scala/coupledL2Verification/VerifyTop.scala"),
              "source_state": git_state(PROJECT),
              "submodule_revisions": {
                  name: git_state(PROJECT / name)
                  for name in ("coupledL2", "coupledL2/HuanCun", "coupledL2/rocket-chip",
                               "coupledL2/utility")
              },
              "tcl_sha256": sha(TCL), "config_sha256": sha(args.config),
              "helper_sha256": {name: sha(VERILOG / name) for name in
                                ("LogPerfHelper.v", "ResetCounter.sv", "STD_CLKGT_func.v",
                                 "ClockGate.v", "TLLogWriter.v")},
              "generate_log": "generate.log", "jg_log": "jg.log"}
    metadata = directory / "run_metadata.json"
    if args.resume_generated or args.finalize_existing:
        if not metadata.is_file():
            raise ValueError("resume requires saved run_metadata.json provenance")
        saved = json.loads(metadata.read_text())
        validate_resume(saved, result, output)
        result = saved
    else:
        if metadata.exists():
            raise FileExistsError(f"existing run metadata; preserve and review: {metadata}")
        metadata.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    command = ["codex-run", str(MILL), "-i", "CoupledL2Verification.test.runMain",
               "coupledL2Verification.ParamVerifyTop"]
    command.extend(f"{key}={value}" for key, value in sorted(row["candidate"].items()))
    command.append(f"output={output}")
    code, seconds = (0, 0.0) if (args.resume_generated or args.finalize_existing) else execute(
        command, PROJECT, directory / "generate.log", 900)
    if not (args.resume_generated or args.finalize_existing):
        result["generate_seconds"] = seconds
    result["resumed_generated"] = args.resume_generated or args.finalize_existing
    if code is None:
        result.update(status="tool_failed", reason="Mill process watchdog expired")
    elif code == -1:
        result.update(status="tool_failed", reason="Mill process could not start")
    elif code != 0:
        result.update(status="compile_failed", reason=f"Mill exit status {code}")
    elif not output.is_file():
        result.update(status="tool_failed", reason="Mill returned without VerifyTop.sv")
    else:
        result["sv_sha256"] = sha(output)
        metadata.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        code, seconds = (0, 0.0) if args.finalize_existing else execute(
            ["codex-run", "jg", "-no_gui", "-allow_unsupported_OS",
             "-tcl", "../../verify.tcl"], directory, directory / "jg.log", 2100)
        result["jg_seconds"] = seconds
        if code is None:
            result.update(status="tool_failed", reason="JG process watchdog expired")
        elif code != 0:
            result.update(status="tool_failed", reason=f"JG exit status {code}")
        else:
            status, reason, properties, coverage = parse_jg(directory, directory / "jg.log",
                                                             row["candidate"])
            result.update(status=status, reason=reason, properties=properties, coverage=coverage)
            if status in FEASIBLE:
                mill_version = subprocess.check_output(
                    ["codex-run", str(MILL), "-i", "--version"],
                    cwd=PROJECT, text=True).splitlines()[0]
                jg_version = (directory / "jg.log").read_text().splitlines()[0]
                result.update(property_version=result["source_sha256"],
                              assumption_version=result["source_sha256"],
                              tool_versions={"mill": mill_version, "jg": jg_version},
                              expected_properties=list(properties),
                              expected_coverage=list(coverage))
    result["elapsed_seconds"] = time.monotonic() - start
    if recheck and result["status"] in FEASIBLE:
        reference = json.loads((Path(report["best"]["path"]) / "result.json").read_text())
        keys = ("sv_sha256", "source_sha256", "source_state", "submodule_revisions",
                "tcl_sha256", "helper_sha256")
        if any(result[key] != reference[key] for key in keys):
            result.update(status="tool_failed", reason="recheck artifact hashes differ from selected run")
        result["recheck_of"] = report["best"]["hash"]
    result_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"rank": row["rank"], "status": result["status"],
                      "reason": result["reason"], "path": str(directory)}, indent=2), flush=True)
    return args.search


if __name__ == "__main__":
    while main():
        pass
