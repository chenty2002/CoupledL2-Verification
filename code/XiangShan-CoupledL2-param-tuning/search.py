#!/usr/bin/env python3
"""Ordered candidate search and complete formal-result accounting."""

import argparse
import hashlib
import heapq
import itertools
import json
import math
from collections import Counter
from pathlib import Path


BASELINE = {
    "l1.ways": 2, "l1.sets": 2, "l1.blockBytes": 2,
    "l1.mshrs": 4, "l1.channelBytes": 1,
    "l2.ways": 2, "l2.sets": 4, "l2.blockBytes": 2,
    "l2.mshrs": 4, "l2.channelBytes": 1,
    "l3.ways": 2, "l3.sets": 4, "l3.blockBytes": 2,
    "l3.mshrs": 6, "l3.channelBytes": 1,
    "ram.width": 5,
}
KEYS = tuple(sorted(BASELINE))
MIN_MSHRS = {"l1": 4, "l2": 4, "l3": 6}
REJECTED = {"static_reject", "compile_failed", "cex"}
FEASIBLE = {"feasible_proved", "bounded_no_cex"}
UNRESOLVED = {"tool_failed", "property_missing", "unknown"}
STATUSES = REJECTED | FEASIBLE | UNRESOLVED
ROOT = Path(__file__).resolve().parent
PROJECT = ROOT / "Chisel"
VERILOG = ROOT / "verilog"
RUNS = VERILOG / "runs-l3-space"
PROPERTY_NAMES = {
    "param_way_index", "param_way_isolation", "param_unique_live_tag",
    "param_address_decode", "param_block_boundary", "param_block_size",
    "param_beat_shape", "param_beat_stable", "param_block_assembly",
    "param_mshr_capacity", "param_mshr_unique", "param_mshr_progress",
    "param_ram_lanes", "param_ram_data",
}


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def parse_config(path):
    config = json.loads(path.read_text(), object_pairs_hook=unique_pairs)
    if not isinstance(config, dict) or set(config) != {"parameters", "search"}:
        raise ValueError("configuration requires only parameters and search")
    if not isinstance(config["parameters"], dict) or not isinstance(config["search"], dict):
        raise ValueError("parameters and search must be objects")
    if set(config["search"]) != {"max_candidates"}:
        raise ValueError("search requires only max_candidates")
    cap = config["search"]["max_candidates"]
    if type(cap) is not int or cap < 0:
        raise ValueError("max_candidates must be a nonnegative integer")
    ranges = {}
    for key, spec in config["parameters"].items():
        if key not in BASELINE or not isinstance(spec, dict) or set(spec) != {"min", "max", "step"}:
            raise ValueError(f"invalid parameter or range: {key}")
        lo, hi, step = (spec[k] for k in ("min", "max", "step"))
        if any(type(x) is not int for x in (lo, hi, step)) or step <= 0 or lo > hi:
            raise ValueError(f"invalid integer range: {key}")
        ranges[key] = range(lo, hi + 1, step)
    return ranges, cap


def score(p):
    capacity = sum(n * p[f"{layer}.ways"] * p[f"{layer}.sets"] * p[f"{layer}.blockBytes"]
                   for layer, n in (("l1", 2), ("l2", 2), ("l3", 1)))
    mshrs = 2 * p["l1.mshrs"] + 2 * p["l2.mshrs"] + p["l3.mshrs"]
    channels = sum(p[f"{layer}.channelBytes"] for layer in ("l1", "l2", "l3"))
    return capacity, mshrs, channels, tuple(p[k] for k in KEYS)


def candidates(ranges):
    names = tuple(sorted(ranges))
    def candidate(indices):
        return BASELINE | {key: ranges[key][index] for key, index in zip(names, indices)}

    origin = (0,) * len(names)
    heap = [(score(candidate(origin)), origin)]
    seen = {origin}
    while heap:
        _, indices = heapq.heappop(heap)
        yield candidate(indices)
        for dimension, key in enumerate(names):
            if indices[dimension] + 1 >= len(ranges[key]):
                continue
            other = indices[:dimension] + (indices[dimension] + 1,) + indices[dimension + 1:]
            if other not in seen:
                seen.add(other)
                heapq.heappush(heap, (score(candidate(other)), other))


def parameter_reject(key, value):
    layer, field = key.split(".")
    if field == "ways" and value < 2:
        return f"{key} must be >= 2 (two-way coverage)"
    if field == "sets" and (value < 2 or value & (value - 1)):
        return f"{key} must be a power of two >= 2"
    if field == "mshrs" and value < MIN_MSHRS[layer]:
        return f"{key} must be >= {MIN_MSHRS[layer]} (parameter-check minimum)"
    if key == "l1.mshrs" and value > 16:
        return "l1.mshrs must be <= 16 (Message Generator source IDs)"
    if field in {"blockBytes", "channelBytes"} and value != BASELINE[key]:
        return f"{key} is fixed at {BASELINE[key]} by ParamVerifyTop"
    if key == "ram.width" and not 5 <= value <= 32:
        return "ram.width must be in 5..32"
    return None


def static_reject(p):
    for key in KEYS:
        reason = parameter_reject(key, p[key])
        if reason:
            return reason
    if p["l3.sets"] <= p["l2.sets"]:
        return "l3.sets must be > l2.sets (larger L3 set-index space)"
    for layer in ("l1", "l2", "l3"):
        if 2 * p[f"{layer}.sets"] * p[f"{layer}.blockBytes"] > 1 << p["ram.width"]:
            return f"{layer}: RAM cannot address all sets at two tags"
    return None  # Structural and TL checks remain Scala's responsibility.


def static_spaces(ranges):
    """Factor exact static constraints before heap enumeration, including RAM/set coupling."""
    domains = {key: tuple(value for value in ranges.get(key, (BASELINE[key],))
                          if parameter_reject(key, value) is None) for key in KEYS}
    for width in domains["ram.width"]:
        space = domains | {"ram.width": (width,)}
        for layer in ("l1", "l2", "l3"):
            space[f"{layer}.sets"] = tuple(sets for sets in domains[f"{layer}.sets"]
                                           if 4 * sets <= 1 << width)
        for l2_sets in space["l2.sets"]:
            coupled = space | {"l2.sets": (l2_sets,),
                               "l3.sets": tuple(sets for sets in space["l3.sets"]
                                                if sets > l2_sets)}
            if all(coupled.values()):
                yield coupled


def eligible_candidates(spaces):
    return heapq.merge(*(candidates(space) for space in spaces), key=score)


def digest(p):
    return hashlib.sha256(json.dumps(p, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def load_result(path, p):
    if not path.exists():
        return None
    result = json.loads(path.read_text())
    if result.get("candidate") != p or result.get("status") not in STATUSES:
        raise ValueError(f"invalid result: {path}")
    # Counterexamples are tied to the monitor version just as passing results are.
    for key, file in {
        "source_sha256": PROJECT / "src/test/scala/coupledL2Verification/VerifyTop.scala",
        "tcl_sha256": VERILOG / "verify.tcl",
    }.items():
        if key in result and result[key] != hashlib.sha256(file.read_bytes()).hexdigest():
            raise ValueError(f"result evidence is stale: {path}")
    if result["status"] in FEASIBLE:
        # A summary must never infer feasibility from an exit code or partial log.
        required = ("sv_sha256", "tcl_sha256", "source_sha256", "helper_sha256",
                    "source_revision", "submodule_revisions",
                    "property_version", "assumption_version", "tool_versions",
                    "expected_properties", "properties", "expected_coverage", "coverage",
                    "generate_log", "jg_log", "elapsed_seconds")
        if any(not result.get(key) for key in required):
            raise ValueError(f"missing feasibility evidence: {path}")
        current = {
            "source_sha256": PROJECT / "src/test/scala/coupledL2Verification/VerifyTop.scala",
            "tcl_sha256": VERILOG / "verify.tcl",
        }
        if any(result[key] != hashlib.sha256(file.read_bytes()).hexdigest()
               for key, file in current.items()):
            raise ValueError(f"feasibility evidence is stale: {path}")
        helpers = {name: hashlib.sha256((VERILOG / name).read_bytes()).hexdigest() for name in
                   ("LogPerfHelper.v", "ResetCounter.sv", "STD_CLKGT_func.v",
                    "ClockGate.v", "TLLogWriter.v")}
        if result["helper_sha256"] != helpers:
            raise ValueError(f"helper Verilog evidence is stale: {path}")
        properties = result["properties"]
        expected = result["expected_properties"]
        if (not isinstance(properties, dict) or not isinstance(expected, list)
                or len(expected) != len(properties) or set(properties) != set(expected)
                or any("param_" not in name for name in properties)
                or {"param_" + name.rsplit("param_", 1)[1].split("__", 1)[0]
                    for name in properties if "param_" in name} != PROPERTY_NAMES
                or any(state not in {"proved", "bounded_no_cex"}
                       for state in properties.values())):
            raise ValueError(f"invalid property results: {path}")
        coverage = result["coverage"]
        expected = result["expected_coverage"]
        if (not isinstance(coverage, dict) or not isinstance(expected, list)
                or not coverage or len(expected) != len(coverage)
                or set(coverage) != set(expected) or any(
                    not isinstance(item, dict) or
                    (item.get("reached") is not True and
                     (item.get("reached") is not False or
                      not isinstance(item.get("reason"), str) or
                      not item["reason"].strip()))
                    for item in coverage.values())):
            raise ValueError(f"invalid coverage evidence: {path}")
        if result["status"] == "feasible_proved" and any(
            state != "proved" for state in properties.values()
        ):
            raise ValueError(f"unproved property in feasible_proved: {path}")
    return result


def inspect(ranges, cap, runs):
    total = math.prod(len(values) for values in ranges.values())
    spaces = list(static_spaces(ranges))
    eligible = sum(math.prod(map(len, space.values())) for space in spaces)
    counts = Counter(static_reject=total - eligible)
    attempted = 0
    best = blocker = next_row = None
    verdict = "not_found"
    for rank, p in enumerate(eligible_candidates(spaces), 1):
        reason = static_reject(p)
        path = runs / digest(p)
        result = load_result(path / "result.json", p)
        if result and (result["status"] == "static_reject") != bool(reason):
            raise ValueError(f"static rejection disagrees with candidate: {path}")
        status = "static_reject" if reason else result["status"] if result else "unrun"
        row = {"rank": rank, "hash": digest(p), "candidate": p,
               "score": score(p)[:3], "status": status,
               "reason": reason, "path": str(path)}
        if status == "unrun":
            next_row, verdict = row, "incomplete"
            break
        attempted += 1
        counts[status] += 1
        if status in UNRESOLVED:
            blocker, verdict = row, "undetermined"
            break
        if status in FEASIBLE:
            best, verdict = row, "pending_recheck"
            recheck = load_result(runs / (digest(p) + "-recheck") / "result.json", p)
            if recheck:
                keys = ("sv_sha256", "source_sha256", "source_state", "submodule_revisions",
                        "tcl_sha256", "helper_sha256")
                if recheck["status"] in FEASIBLE and all(
                    recheck.get(key) == result.get(key) for key in keys
                ):
                    verdict = "minimum"
                else:
                    blocker, verdict = dict(row, reason="independent recheck failed"), "undetermined"
            break
        if cap and attempted >= cap and attempted < eligible:
            verdict = "candidate_limit"
            break
    counts["unrun"] = eligible - attempted
    return {"total": total, "eligible_total": eligible, "counts": dict(counts),
            "rank_scope": "statically_eligible", "static_pruning": {
                "ranges": {key: {"min": values.start, "max": values[-1], "step": values.step}
                           for key, values in sorted(ranges.items())},
                "checker_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "domains": {key: sorted({value for space in spaces for value in space[key]})
                            for key in KEYS},
                "ram_set_rule": "4 * sets <= 2**ram.width for each layer",
                "hierarchy_set_rule": "l3.sets > l2.sets",
                "total": total, "eligible_total": eligible,
            },
            "attempted": attempted, "max_candidates": cap, "verdict": verdict,
            "best": best, "blocker": blocker, "next": next_row}


def prepare(ranges, runs, row):
    pruning = inspect(ranges, 0, runs)["static_pruning"]
    evidence = runs / "static-pruning" / (hashlib.sha256(
        json.dumps(pruning, sort_keys=True).encode()).hexdigest() + ".json")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    if not evidence.exists():
        evidence.write_text(json.dumps(pruning, indent=2, sort_keys=True) + "\n")
    if row is not None:
        p = row["candidate"]
        path = runs / digest(p)
        path.mkdir(parents=True, exist_ok=True)
        snapshot = path / "candidate.json"
        if snapshot.exists() and json.loads(snapshot.read_text()) != p:
            raise ValueError(f"conflicting candidate snapshot: {snapshot}")
        if not snapshot.exists():
            snapshot.write_text(json.dumps(p, indent=2, sort_keys=True) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("next", "prepare", "summary", "list"))
    parser.add_argument("--limit", type=int, default=20, help="number of rows for list")
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("search.json"))
    parser.add_argument("--runs", type=Path, default=RUNS)
    args = parser.parse_args()
    ranges, cap = parse_config(args.config)
    if args.command == "list":
        if args.limit < 1:
            parser.error("--limit must be positive")
        for rank, p in enumerate(itertools.islice(
                eligible_candidates(list(static_spaces(ranges))), args.limit), 1):
            print(json.dumps({"rank": rank, "hash": digest(p), "score": score(p)[:3],
                              "candidate": p, "static_reject": static_reject(p)}, sort_keys=True))
    else:
        report = inspect(ranges, cap, args.runs)
        if args.command == "prepare":
            prepare(ranges, args.runs, report["next"])
        print(json.dumps(report if args.command == "summary" else report["next"], indent=2))


if __name__ == "__main__":
    main()
