"""Deadlock / stall dependency-graph builder for CoupledL2-based FST waveforms.

Compared with the earlier hard-coded edition, this analyzer derives its
rule base directly from the design rather than from Python literals, so
that minor Chisel commits (renamed FSM bits, added cache levels,
retitled module instances) no longer break the analysis chain.  The
three concrete extensions are:

  1. **Schema discovery** — parse the design's FIRRTL (when available)
     and extract, for every MSHR module, the *exact* set of
     ``state.s_*`` / ``state.w_*`` register fields and the boolean
     expression of ``will_free``.  When FIRRTL is unavailable the
     analyzer falls back to a built-in schema bundled in
     :func:`builtin_coupledl2_schema`.
  2. **Driver provenance mining** — for every FSM bit, walk the FIRRTL
     ``state.<bit> <= ...`` assignment and the ``when (...) :`` context
     that guards it, then attribute the clearing event to its driver
     port (``io.resps.sink_d.*``, ``io.replResp.*``, etc.).  The
     resulting :class:`DriverInfo` is what tells the analyzer "a zero on
     ``state_w_replResp`` means we are waiting on the local Directory" —
     no Python-side hard-coding required.
  3. **Topology inference** — open the FST, enumerate every module
     instance whose path matches one of the schema's MSHR regexes, turn
     that into the cache pool, and infer parent/child links between
     caches by matching cache-label conventions plus the schema's
     instance map.  The classic L1/L2/L3 hierarchy emerges without any
     hand-written ``_peer_cache`` table.

Pipeline:

  1. Discover the schema and driver-provenance map (steps 1+2 above).
  2. Open the FST, enumerate cache instances, and infer the topology
     (step 3 above).
  3. Replay — for each MSHR, freeze the final value of every signal
     declared in the schema.
  4. Stall set + edge synthesis — compute the stalled set ``Σ`` and
     emit edges per the existing wait/sched resolvers.  Specialised
     hand-written resolvers take priority; bits that are *new* in this
     commit and have no specialised resolver fall back on a *generic
     resolver* synthesised from the driver-provenance map (so the cycle
     remains closeable instead of being silently dropped).
  5. Rule-coverage telemetry — at the end, the analyzer emits a per-bit
     trigger count and flags every stalled MSHR with zero out-degree as
     a hard error.  CI can wire this up as a gate to detect rule decay
     within the same commit that introduces it.

The original CLI behaviour (``--fst``/``--dot``/``--png``) is preserved.
New flags:

  --firrtl PATH   path to a Chisel/FIRRTL ``*.fir`` file used for
                  schema and driver-provenance discovery; if omitted
                  the analyzer auto-detects one near the FST and falls
                  back to a built-in schema if none is found.
  --no-auto       disable the auto-discovery pipeline entirely and use
                  the bundled hard-coded rule base (matches the legacy
                  behaviour).
  --strict        exit with non-zero status if any stalled MSHR has
                  zero out-degree (CI gate against rule decay).
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

import pylibfst as pf
from pylibfst import lib, ffi, string as fst_string

try:
    import networkx as nx
except ImportError:
    sys.exit("[fatal] networkx is required. Run `uv add networkx` inside fst_analysis/.")


# ---------------------------------------------------------------------------
# Schema discovery and driver provenance --------------------------------------
# ---------------------------------------------------------------------------

# Map from FIRRTL port-name fragments to (driver_kind, channel, direction).
# These fragments are *structural* (matching TileLink's wire naming, not
# any user-given name), so they do not need to be revisited per-commit.
DRIVER_PORT_PATTERNS: List[Tuple["re.Pattern", Tuple[str, Optional[str], Optional[str]]]] = [
    (re.compile(r"io\.resps\.sink_d\."),    ("channel", "D", "in")),
    (re.compile(r"io\.resps\.sink_c\."),    ("channel", "C", "in")),
    (re.compile(r"io\.resps\.sink_e\."),    ("channel", "E", "in")),
    (re.compile(r"io\.replResp\."),         ("directory", None, None)),
    (re.compile(r"io\.tasks\.source_a\."),  ("channel", "A", "out")),
    (re.compile(r"io\.tasks\.source_b\."),  ("channel", "B", "out")),
    (re.compile(r"io\.tasks\.source_c\."),  ("channel", "C", "out")),
    (re.compile(r"io\.tasks\.source_d\."),  ("pipeline", "sourceD", None)),
    (re.compile(r"io\.tasks\.source_e\."),  ("channel", "E", "out")),
    (re.compile(r"io\.tasks\.mainpipe\."),  ("pipeline", "mainpipe", None)),
    (re.compile(r"io\.dirResult\."),        ("directory", None, None)),
    (re.compile(r"io\.dir_write\."),        ("directory", None, None)),
    (re.compile(r"io\.tag_write\."),        ("directory", None, None)),
    (re.compile(r"io\.client_dir_write\."), ("directory", None, None)),
    (re.compile(r"io\.client_tag_write\."), ("directory", None, None)),
    (re.compile(r"io\.aMergeTask\."),       ("pipeline", "mainpipe", None)),
    (re.compile(r"io\.nestedwb\."),         ("directory", None, None)),
]


@dataclass
class DriverInfo:
    """How an FSM bit transitions to 1.

    ``trigger_signals`` are the FIRRTL signal names appearing in the rhs
    or in the enclosing ``when (...) :`` predicate of a
    ``state.<bit> <= 1`` assignment.  ``kind``/``channel``/``direction``
    are derived from those signals via :data:`DRIVER_PORT_PATTERNS`.
    """
    bit: str
    kind: str = "unknown"
    channel: Optional[str] = None
    direction: Optional[str] = None
    trigger_signals: List[str] = field(default_factory=list)
    raw_rhs: List[str] = field(default_factory=list)


@dataclass
class Schema:
    """The complete, version-specific shape of one cache family's MSHR FSM."""
    family: str
    mshr_path_re: "re.Pattern"
    scheduling_bits: List[str]
    waiting_bits: List[str]
    will_free_terms: List[str]
    guard_map: Dict[str, List[str]]
    drivers: Dict[str, DriverInfo]
    context_fields: List[str]
    flat_prefix: str = "state_"

    def all_bits(self) -> List[str]:
        return self.scheduling_bits + self.waiting_bits

    def all_signal_leaves(self) -> List[str]:
        return list(dict.fromkeys(self.all_bits() + self.context_fields))


# ---- Built-in schema (used when no FIRRTL is supplied) ----------------------

_BUILTIN_CONTEXT_FIELDS = [
    "req_valid", "req_channel", "req_opcode", "req_param",
    "req_set", "req_tag", "req_sourceId",
    "dirResult_hit", "dirResult_tag", "dirResult_way",
    "dirResult_meta_state",
    "gotT", "gotDirty", "gotGrantData", "probeDirty", "probeGotN",
    "io_status_bits_will_free",
    "io_msInfo_bits_set", "io_msInfo_bits_way",
    "io_msInfo_bits_reqTag", "io_msInfo_bits_metaTag",
    "io_msInfo_bits_dirHit", "io_msInfo_bits_blockRefill",
    "io_msInfo_bits_needRelease", "io_msInfo_bits_nestB",
    "io_msInfo_bits_willFree",
    "io_tasks_source_a_valid", "io_tasks_source_a_ready",
    "io_tasks_source_b_valid", "io_tasks_source_b_ready",
    "io_tasks_mainpipe_valid", "io_tasks_mainpipe_ready",
    "io_resps_sink_c_valid", "io_resps_sink_d_valid",
    "io_replResp_valid", "io_replResp_bits_retry", "io_replResp_bits_way",
]

_BUILTIN_DRIVER_HINTS = {
    "state_w_replResp":       ("directory", None, None),
    "state_w_grantfirst":     ("channel",   "D",  "in"),
    "state_w_grantlast":      ("channel",   "D",  "in"),
    "state_w_grant":          ("channel",   "D",  "in"),
    "state_w_releaseack":     ("channel",   "D",  "in"),
    "state_w_pprobeackfirst": ("channel",   "C",  "in"),
    "state_w_pprobeacklast":  ("channel",   "C",  "in"),
    "state_w_pprobeack":      ("channel",   "C",  "in"),
    "state_w_rprobeackfirst": ("channel",   "C",  "in"),
    "state_w_rprobeacklast":  ("channel",   "C",  "in"),
    "state_s_acquire":        ("channel",   "A",  "out"),
    "state_s_pprobe":         ("channel",   "B",  "out"),
    "state_s_rprobe":         ("channel",   "B",  "out"),
    "state_s_refill":         ("pipeline",  "mainpipe", None),
    "state_s_release":        ("pipeline",  "mainpipe", None),
    "state_s_probeack":       ("pipeline",  "mainpipe", None),
    "state_s_retry":          ("pipeline",  "mainpipe", None),
}


def _builtin_drivers() -> Dict[str, DriverInfo]:
    return {
        bit: DriverInfo(bit=bit, kind=k, channel=ch, direction=d,
                        trigger_signals=["<built-in>"])
        for bit, (k, ch, d) in _BUILTIN_DRIVER_HINTS.items()
    }


def builtin_coupledl2_schema() -> Schema:
    """Hand-written schema used when no FIRRTL is available."""
    return Schema(
        family="coupledl2-builtin",
        mshr_path_re=re.compile(
            r"^(?P<prefix>.+?\.slices_\d+\.mshrCtl)\.mshrs_(?P<idx>\d+)\.(?P<leaf>.+)$"),
        scheduling_bits=[
            "state_s_acquire", "state_s_rprobe", "state_s_pprobe",
            "state_s_probeack", "state_s_refill", "state_s_release",
            "state_s_retry",
        ],
        waiting_bits=[
            "state_w_rprobeackfirst", "state_w_rprobeacklast",
            "state_w_pprobeackfirst", "state_w_pprobeacklast", "state_w_pprobeack",
            "state_w_grantfirst",     "state_w_grantlast",     "state_w_grant",
            "state_w_releaseack",     "state_w_replResp",
        ],
        will_free_terms=[
            "state_s_refill", "state_s_probeack", "state_s_release",
            "state_w_rprobeacklast", "state_w_pprobeacklast",
            "state_w_grantlast", "state_w_releaseack", "state_w_replResp",
        ],
        guard_map={
            "state_s_refill":   ["state_w_grantlast", "state_w_rprobeacklast", "state_w_replResp"],
            "state_s_release":  ["state_w_rprobeacklast", "state_w_grantlast", "state_w_replResp"],
            "state_s_probeack": ["state_w_pprobeacklast"],
        },
        drivers=_builtin_drivers(),
        context_fields=list(_BUILTIN_CONTEXT_FIELDS),
        flat_prefix="state_",
    )


# ---- FIRRTL-based schema synthesis -----------------------------------------

_MSHR_MODULE_HEADER_RE = re.compile(r"^\s*module\s+(MSHR(?:_\d+)?)\s*:\s*$")
_MODULE_RE             = re.compile(r"^\s*module\s+(\S+)\s*:\s*$")
_INST_RE               = re.compile(r"^\s*inst\s+(\S+)\s+of\s+(\S+)\s*(?:@\[.*\])?$")
_REG_STATE_RE          = re.compile(
    r"reg\s+state\s*:\s*\{(.+?)\},\s*clock", re.DOTALL)
_BUNDLE_FIELD_RE       = re.compile(r"\b([sw]_[A-Za-z0-9]+)\s*:\s*UInt<1>")
_STATE_ASSIGN_RE       = re.compile(r"^(\s+)state\.([sw]_[A-Za-z0-9]+)\s*<=\s*(.+?)\s*(?:@\[.*\])?$")
_WHEN_RE               = re.compile(r"^(\s+)when\s+(.+?)\s*:\s*$")
_NODE_WILL_FREE_RE     = re.compile(r"^\s*node\s+will_free\s*=\s*and\((\w+)\s*,\s*(\w+)\)")
_NODE_AND_RE           = re.compile(r"^\s*node\s+(\w+)\s*=\s*and\((.+?)\)\s*(?:@\[.*\])?$")
_NODE_EQ_RE            = re.compile(r"^\s*node\s+(\w+)\s*=\s*eq\(\s*(.+?)\s*,\s*UInt<1>\(\"h0\"\)\s*\)")
_MP_GUARD_RE           = re.compile(r"^\s*node\s+(mp_(?:release|probeack|grant)_valid)\s*=\s*and\((.+?)\)\s*(?:@\[.*\])?$")


def _extract_mshr_state_bundle(body: str) -> Optional[List[str]]:
    """Return the list of ``s_*``/``w_*`` field names from
    ``reg state : { ... }``."""
    m = _REG_STATE_RE.search(body)
    if not m:
        return None
    return [fm.group(1) for fm in _BUNDLE_FIELD_RE.finditer(m.group(1))]


def _split_and_args(rhs: str) -> List[str]:
    """Split the top-level args of an ``and(a, b, c)`` expression."""
    if not rhs.startswith("and(") or not rhs.endswith(")"):
        return [rhs]
    inner = rhs[len("and("):-1]
    depth = 0
    buf: List[str] = []
    out: List[str] = []
    for ch in inner:
        if ch == "(":
            depth += 1; buf.append(ch)
        elif ch == ")":
            depth -= 1; buf.append(ch)
        elif ch == "," and depth == 0:
            out.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
    if buf:
        out.append("".join(buf).strip())
    return out


def _resolve_node_chain(node_name: str, definitions: Dict[str, str],
                        seen: Optional[Set[str]] = None) -> List[str]:
    """Recursively flatten ``and(a, and(b, c))`` chains into a list of leaves."""
    seen = seen or set()
    if node_name in seen:
        return [node_name]
    seen.add(node_name)
    rhs = definitions.get(node_name)
    if rhs is None:
        return [node_name]
    rhs = rhs.strip()
    if rhs.startswith("and("):
        out: List[str] = []
        for a in _split_and_args(rhs):
            out.extend(_resolve_node_chain(a, definitions, seen))
        return out
    return [node_name]


def _classify_driver(triggers: Iterable[str]) -> Tuple[str, Optional[str], Optional[str]]:
    for sig in triggers:
        for pat, info in DRIVER_PORT_PATTERNS:
            if pat.search(sig):
                return info
    return ("unknown", None, None)


def _scan_fir_file(path: str) -> Tuple[Optional[Schema], Dict[str, Dict[str, str]]]:
    """Parse a FIRRTL file and synthesise a :class:`Schema`.

    Returns ``(schema, instance_map)`` where ``instance_map`` is
    ``{module_name -> {child_inst_name: child_module}}``.
    """
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError as err:
        print(f"[schema] cannot read FIRRTL: {err}", file=sys.stderr)
        return None, {}

    # split into modules
    modules: Dict[str, str] = {}
    cur_mod: Optional[str] = None
    cur_buf: List[str] = []
    for line in text.splitlines():
        m = _MODULE_RE.match(line)
        if m:
            if cur_mod is not None:
                modules[cur_mod] = "\n".join(cur_buf)
            cur_mod = m.group(1)
            cur_buf = [line]
        else:
            cur_buf.append(line)
    if cur_mod is not None:
        modules[cur_mod] = "\n".join(cur_buf)

    # instance map (used by topology layer)
    instance_map: Dict[str, Dict[str, str]] = {}
    for mod_name, body in modules.items():
        children: Dict[str, str] = {}
        for line in body.splitlines():
            im = _INST_RE.match(line)
            if im:
                children[im.group(1)] = im.group(2)
        instance_map[mod_name] = children

    # find a representative MSHR module
    mshr_mods = [n for n in modules if _MSHR_MODULE_HEADER_RE.match(f"  module {n} :")]
    if not mshr_mods:
        return None, instance_map

    body = modules[mshr_mods[0]]
    fields = _extract_mshr_state_bundle(body)
    if not fields:
        return None, instance_map

    sched_bits = [f"state_{n}" for n in fields if n.startswith("s_")]
    wait_bits  = [f"state_{n}" for n in fields if n.startswith("w_")]

    # collect node-equality definitions for will_free / mp_*_valid resolution
    node_defs: Dict[str, str] = {}
    will_free_lhs: Optional[Tuple[str, str]] = None
    mp_valids: Dict[str, str] = {}
    for raw in body.splitlines():
        m = _NODE_AND_RE.match(raw)
        if m:
            node_defs[m.group(1)] = "and(" + m.group(2) + ")"
        m = _NODE_EQ_RE.match(raw)
        if m:
            node_defs[m.group(1)] = f"eq({m.group(2)},h0)"
        m = _NODE_WILL_FREE_RE.match(raw)
        if m:
            will_free_lhs = (m.group(1), m.group(2))
        m = _MP_GUARD_RE.match(raw)
        if m:
            mp_valids[m.group(1)] = m.group(2)

    will_free_terms: List[str] = []
    if will_free_lhs:
        for op in will_free_lhs:
            for t in _resolve_node_chain(op, node_defs):
                if t.startswith("state."):
                    will_free_terms.append("state_" + t.split(".", 1)[1])
    if not will_free_terms:
        # generic fallback
        will_free_terms = [b for b in sched_bits if not b.endswith("_retry")]
        will_free_terms += [b for b in wait_bits
                            if "last" in b or b.endswith("releaseack") or b.endswith("replResp")]

    guard_map: Dict[str, List[str]] = {}
    sched_to_node = {
        "state_s_release":  "mp_release_valid",
        "state_s_probeack": "mp_probeack_valid",
        "state_s_refill":   "mp_grant_valid",
    }
    for sbit, gnode in sched_to_node.items():
        if gnode not in mp_valids:
            continue
        node_defs.setdefault(gnode, "and(" + mp_valids[gnode] + ")")
        leaves = _resolve_node_chain(gnode, node_defs)
        guards = ["state_" + l.split(".", 1)[1] for l in leaves if l.startswith("state.w_")]
        if guards:
            guard_map[sbit] = guards

    # driver provenance: walk the body, track when-stack via indentation
    drivers: Dict[str, DriverInfo] = {b: DriverInfo(bit=b) for b in sched_bits + wait_bits}
    when_stack: List[Tuple[int, str]] = []
    for raw in body.splitlines():
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        while when_stack and indent <= when_stack[-1][0]:
            when_stack.pop()
        wm = _WHEN_RE.match(raw)
        if wm:
            when_stack.append((len(wm.group(1)), wm.group(2)))
            continue
        am = _STATE_ASSIGN_RE.match(raw)
        if am:
            full_bit = "state_" + am.group(2)
            rhs = am.group(3)
            preds = [p for _, p in when_stack]
            sigs: List[str] = []
            for t in list(preds) + [rhs]:
                for s in re.finditer(r"io\.[A-Za-z0-9_.]+", t):
                    sigs.append(s.group(0))
            drv = drivers.setdefault(full_bit, DriverInfo(bit=full_bit))
            drv.trigger_signals.extend(sigs)
            drv.raw_rhs.append(rhs)

    for bit, drv in drivers.items():
        drv.trigger_signals = sorted(set(drv.trigger_signals))
        kind, ch, direction = _classify_driver(drv.trigger_signals)
        # If the FIRRTL gave no hint (e.g. pure state-only assignment),
        # fall back to the built-in hint table so the bit still routes
        # to a meaningful node.
        if kind == "unknown" and bit in _BUILTIN_DRIVER_HINTS:
            kind, ch, direction = _BUILTIN_DRIVER_HINTS[bit]
        drv.kind = kind
        drv.channel = ch
        drv.direction = direction

    # decide path-regex flavour from the instance map: a CoupledL2-style
    # MSHR is instantiated *inside* an MSHRCtl module ("...mshrCtl.mshrs_N"),
    # while a HuanCun-noninclusive MSHR sits directly in a Slice ("...ms_N").
    parents_of: Dict[str, Set[str]] = defaultdict(set)
    for parent_mod, kids in instance_map.items():
        for _inst, child_mod in kids.items():
            parents_of[child_mod].add(parent_mod)
    has_mshrctl = any(p.startswith("MSHRCtl") for mshr_mod in mshr_mods
                      for p in parents_of.get(mshr_mod, ()))
    if has_mshrctl:
        path_re = re.compile(
            r"^(?P<prefix>.+?\.slices_\d+\.mshrCtl)\.mshrs_(?P<idx>\d+)\.(?P<leaf>.+)$")
    else:
        path_re = re.compile(
            r"^(?P<prefix>.+?\.slices_\d+)\.ms_(?P<idx>\d+)\.(?P<leaf>.+)$")

    schema = Schema(
        family=f"coupledl2-fir({os.path.basename(path)})",
        mshr_path_re=path_re,
        scheduling_bits=sched_bits,
        waiting_bits=wait_bits,
        will_free_terms=sorted(set(will_free_terms)),
        guard_map=guard_map,
        drivers=drivers,
        context_fields=list(_BUILTIN_CONTEXT_FIELDS),
        flat_prefix="state_",
    )
    return schema, instance_map


def auto_detect_firrtl(fst_path: str) -> Optional[str]:
    """Best-effort search for a sibling ``VerifyTop.fir`` near ``fst_path``."""
    fst_dir = os.path.dirname(os.path.abspath(fst_path))
    search_roots = [fst_dir, os.path.dirname(fst_dir)]
    for root in search_roots:
        if not root or not os.path.isdir(root):
            continue
        for dirpath, _, filenames in os.walk(root):
            for fn in filenames:
                if fn.endswith(".fir"):
                    return os.path.join(dirpath, fn)
    return None


# ---------------------------------------------------------------------------
# Topology inference ----------------------------------------------------------
# ---------------------------------------------------------------------------


@dataclass
class Topology:
    """Discovered cache hierarchy."""
    parent_of: Dict[str, Optional[str]] = field(default_factory=dict)
    children_of: Dict[str, List[str]] = field(default_factory=lambda: defaultdict(list))
    raw: Dict[str, dict] = field(default_factory=dict)

    def parent(self, cache: str) -> Optional[str]:
        return self.parent_of.get(cache)

    def children(self, cache: str) -> List[str]:
        return list(self.children_of.get(cache, []))


# Heuristic instance-name → cache-label rules (used as fallback / canonical
# labelling).  More specific patterns first.
_INSTANCE_NAME_RULES: List[Tuple["re.Pattern", str]] = [
    (re.compile(r"^coupledL2AsL1(?:_(\d+))?$"),  "L1"),
    (re.compile(r"^l1(?:_(\d+))?$"),              "L1"),
    (re.compile(r"^coupledL2(?:_(\d+))?$"),       "L2"),
    (re.compile(r"^l2(?:_(\d+))?$"),              "L2"),
    (re.compile(r"^l3(?:_(\d+))?$"),              "L3"),
    (re.compile(r"^huancun(?:_(\d+))?$"),         "L3"),
]


def _label_from_instance_name(inst: str) -> Optional[str]:
    for pat, prefix in _INSTANCE_NAME_RULES:
        m = pat.fullmatch(inst)
        if m:
            idx = m.group(1) if m.lastindex else None
            return f"{prefix}_{idx if idx is not None else '0'}"
    return None


def _cache_label_from_base(base: str) -> str:
    """Convert ``VerifyTop.coupledL2_1.slices_0.mshrCtl`` → ``L2_1`` etc."""
    core = re.sub(r"\.slices_\d+(?:\.mshrCtl)?$", "", base)
    core = core.split(".", 1)[-1] if "." in core else core
    return _label_from_instance_name(core) or core


def _infer_topology(pool: Dict[str, "CacheIndex"]) -> Topology:
    """Infer parent/child links between caches based on canonical
    ``L<level>_<index>`` labels (assigned by :func:`_cache_label_from_base`)."""
    topo = Topology()
    caches = sorted(pool.keys())
    levels: Dict[int, List[str]] = defaultdict(list)
    parsed: Dict[str, Tuple[int, int]] = {}
    for c in caches:
        m = re.fullmatch(r"L(\d+)_(\d+)", c)
        if m:
            lvl = int(m.group(1)); idx = int(m.group(2))
            levels[lvl].append(c); parsed[c] = (lvl, idx)
    if not levels:
        for c in caches:
            topo.parent_of[c] = None
        return topo
    sorted_lvls = sorted(levels.keys())
    top_lvl = max(sorted_lvls)
    for lvl in sorted_lvls:
        upper = [u for u in sorted_lvls if u > lvl]
        for c in levels[lvl]:
            idx = parsed[c][1]
            parent: Optional[str] = None
            if upper:
                up = upper[0]
                same_idx = f"L{up}_{idx}"
                if same_idx in pool:
                    parent = same_idx
                elif len(levels[up]) == 1:
                    parent = levels[up][0]
            topo.parent_of[c] = parent
            if parent:
                topo.children_of[parent].append(c)
    for c in levels[top_lvl]:
        topo.parent_of.setdefault(c, None)
    topo.raw["levels"] = dict(levels)
    return topo


# ---------------------------------------------------------------------------
# Backward-compat surface (kept so external callers / tests don't break) -----
# ---------------------------------------------------------------------------

# These are *snapshots* of the built-in schema for the rare consumer that
# imported the names directly.  Internal code now goes through ``Schema``.
_BUILTIN_SCHEMA = builtin_coupledl2_schema()
SCHEDULING_BITS = list(_BUILTIN_SCHEMA.scheduling_bits)
WAITING_BITS    = list(_BUILTIN_SCHEMA.waiting_bits)
CONTEXT_FIELDS  = list(_BUILTIN_SCHEMA.context_fields)
ALL_MSHR_FIELDS = SCHEDULING_BITS + WAITING_BITS + CONTEXT_FIELDS
MSHR_PATH_RE    = _BUILTIN_SCHEMA.mshr_path_re


# ---------------------------------------------------------------------------
# Data structures -------------------------------------------------------------
# ---------------------------------------------------------------------------

@dataclass
class MSHRSnapshot:
    """Value of every tracked signal for one MSHR at the chosen time."""
    cache: str
    mshr_id: int
    fields: Dict[str, str] = field(default_factory=dict)
    schema: Optional[Schema] = None    # injected at discovery time

    # ----- convenience accessors (return None if unknown) -----
    def bit(self, name: str) -> Optional[int]:
        v = self.fields.get(name)
        if v is None or v in ("x", "z", ""):
            return None
        # some signals may be multi-bit; treat non-zero as 1
        try:
            return 0 if all(c == "0" for c in v) else 1
        except Exception:
            return None

    def raw(self, name: str) -> Optional[str]:
        return self.fields.get(name)

    def int_of(self, name: str) -> Optional[int]:
        v = self.fields.get(name)
        if v is None or any(c not in "01" for c in v):
            return None
        return int(v, 2)

    @property
    def req_valid(self) -> bool:
        return self.bit("req_valid") == 1

    @property
    def will_free(self) -> bool:
        # 1) prefer the design-exported will_free if present.
        for f in ("io_status_bits_will_free", "io_msInfo_bits_willFree"):
            v = self.bit(f)
            if v == 1:
                return True
        # 2) reconstruct from the schema's will_free_terms (derived from
        #    the FIRRTL ``will_free`` AND-chain) so commits that move or
        #    rename the exported port still work.
        if self.schema is not None and self.schema.will_free_terms:
            terms = self.schema.will_free_terms
            if all(self.bit(t) == 1 for t in terms):
                return True
        return False

    @property
    def is_stalled(self) -> bool:
        return self.req_valid and not self.will_free

    @property
    def label(self) -> str:
        ch = self.int_of("req_channel")
        chmap = {1: "A", 2: "B", 4: "C"}
        chan = chmap.get(ch, "?") if ch is not None else "?"
        s = self.int_of("req_set")
        t = self.int_of("req_tag")
        w = self.int_of("dirResult_way")
        return f"{self.cache}.mshrs_{self.mshr_id}[{chan} set={s} tag={t} way={w}]"


@dataclass
class CacheIndex:
    """All MSHRs belonging to one cache slice."""
    cache: str
    base: str           # full scope prefix up to .mshrCtl
    mshrs: Dict[int, MSHRSnapshot] = field(default_factory=dict)


@dataclass
class Edge:
    """Directed edge ``src --reason--> dst`` in the wait-for graph."""
    src: str
    dst: str
    reason: str         # short categorical name, e.g. 'w_replResp'
    detail: str = ""    # human-readable extra info


# ---------------------------------------------------------------------------
# FST scanning ----------------------------------------------------------------
# ---------------------------------------------------------------------------

def discover_mshr_instances(signals, schemas: Optional[List[Schema]] = None
                            ) -> Tuple[Dict[str, CacheIndex], Dict[str, Schema]]:
    """Walk signal names and group them by cache / MSHR id.

    When multiple schemas are active the first regex that matches a
    signal wins for the corresponding cache.  Returns the cache pool and
    a mapping ``cache_label -> schema`` so subsequent stages know which
    bit set to read for each cache.
    """
    if schemas is None:
        schemas = [_BUILTIN_SCHEMA]
    by_cache: Dict[str, CacheIndex] = {}
    cache_to_schema: Dict[str, Schema] = {}
    for full in signals.by_name:
        for sch in schemas:
            m = sch.mshr_path_re.match(full)
            if not m:
                continue
            base = m.group("prefix")
            idx = int(m.group("idx"))
            label = _cache_label_from_base(base)
            ci = by_cache.setdefault(label, CacheIndex(cache=label, base=base))
            snap = ci.mshrs.setdefault(
                idx, MSHRSnapshot(cache=label, mshr_id=idx, schema=sch))
            if snap.schema is None:
                snap.schema = sch
            cache_to_schema[label] = sch
            break
    return by_cache, cache_to_schema


def _cache_label_of(base: str) -> str:
    """Backward-compatible wrapper around :func:`_cache_label_from_base`."""
    return _cache_label_from_base(base)


def build_handle_table(fst, by_cache: Dict[str, CacheIndex], signals,
                       cache_to_schema: Optional[Dict[str, Schema]] = None):
    """Map FST handles to ``(cache_label, mshr_id, leaf_signal)``."""
    table: Dict[int, Tuple[str, int, str]] = {}
    sig_names = sorted(signals.by_name.keys())
    cache_to_schema = cache_to_schema or {}
    for ci in by_cache.values():
        sch = cache_to_schema.get(ci.cache, _BUILTIN_SCHEMA)
        # Decide the per-MSHR scope prefix from the schema's regex flavour.
        if "ms_(" in sch.mshr_path_re.pattern and ".mshrCtl" not in sch.mshr_path_re.pattern:
            inst_word = "ms_"
        else:
            inst_word = "mshrs_"
        leaves = sch.all_signal_leaves()
        for mi in ci.mshrs:
            prefix = f"{ci.base}.{inst_word}{mi}."
            for leaf in leaves:
                leaf_candidates = [leaf]
                if leaf.startswith("state_"):
                    leaf_candidates.append("state__" + leaf[len("state_"):])
                sig = None
                for cand in leaf_candidates:
                    exact = prefix + cand
                    sig = signals.by_name.get(exact)
                    if sig is not None:
                        break
                    # try with bracketed width suffix (e.g. " [2:0]")
                    for name in sig_names:
                        if not name.startswith(exact):
                            continue
                        rest = name[len(exact):]
                        if rest.startswith(" [") or rest == "":
                            sig = signals.by_name[name]
                            break
                    if sig is not None:
                        break
                if sig is not None:
                    table[sig.handle] = (ci.cache, mi, leaf)
    return table


def replay_fst(fst_path: str, schemas: Optional[List[Schema]] = None
               ) -> Tuple[Dict[str, CacheIndex], int,
                          Dict[Tuple[str, int, str], List[Tuple[int, str]]],
                          Dict[str, Schema]]:
    """Replay the waveform; return ``(pool, end_time, traces, cache->schema)``.

    The fourth tuple element is new in the auto-discovery edition;
    legacy callers that unpack only three elements should switch to
    keyword unpacking or accept a `_traces, _schemas` pair.
    """
    if schemas is None:
        schemas = [_BUILTIN_SCHEMA]
    fst = lib.fstReaderOpen(fst_path.encode())
    if fst == ffi.NULL:
        sys.exit(f"[fatal] could not open FST: {fst_path}")
    _, signals = pf.get_scopes_signals2(fst)
    by_cache, cache_to_schema = discover_mshr_instances(signals, schemas)
    handle_table = build_handle_table(fst, by_cache, signals, cache_to_schema)
    if not handle_table:
        regexes = ", ".join(s.mshr_path_re.pattern for s in schemas)
        sys.exit(f"[fatal] no MSHR signals found; tried regexes: {regexes}")

    lib.fstReaderSetFacProcessMaskAll(fst)

    latest: Dict[int, Tuple[int, str]] = {}
    traces: Dict[Tuple[str, int, str], List[Tuple[int, str]]] = defaultdict(list)

    def cb(_d, t, fac, value):
        info = handle_table.get(fac)
        if info is None:
            return
        v = fst_string(value)
        latest[fac] = (t, v)
        traces[info].append((int(t), v))

    def cb_vl(_d, _t, _fac, _value, _length):
        pass

    pf.fstReaderIterBlocks2(fst, cb, cb_vl)
    end_time = lib.fstReaderGetEndTime(fst)

    for handle, (cache, mi, leaf) in handle_table.items():
        val = latest.get(handle, (0, "x"))[1]
        by_cache[cache].mshrs[mi].fields[leaf] = val

    lib.fstReaderClose(fst)
    return by_cache, int(end_time), traces, cache_to_schema


# ---------------------------------------------------------------------------
# Node constructors -----------------------------------------------------------
# ---------------------------------------------------------------------------

def mshr_node(s: MSHRSnapshot) -> str:
    return f"M::{s.cache}::mshr{s.mshr_id}"


def directory_node(cache: str) -> str:
    return f"DIR::{cache}"


def channel_node(cache: str, ch: str, direction: str) -> str:
    """``direction`` = 'in' (this cache's Sink) / 'out' (this cache's Source)."""
    return f"CH::{cache}::{ch}::{direction}"


def pipeline_node(cache: str, stage: str) -> str:
    return f"PIPE::{cache}::{stage}"


# ---------------------------------------------------------------------------
# Rule-based resolvers --------------------------------------------------------
# ---------------------------------------------------------------------------

def _same_cache_mshrs(ci: CacheIndex) -> List[MSHRSnapshot]:
    return [ci.mshrs[i] for i in sorted(ci.mshrs)]


# ---------------------------------------------------------------------------
# Analyzer context ------------------------------------------------------------
# ---------------------------------------------------------------------------

@dataclass
class AnalyzerContext:
    """Per-run analysis state shared across resolvers and reporters."""
    topology: Topology
    schemas: Dict[str, Schema]                   # cache_label -> schema
    coverage: Dict[str, int] = field(default_factory=lambda: defaultdict(int))
    coverage_specialised: Set[str] = field(default_factory=set)
    coverage_generic: Set[str] = field(default_factory=set)


# ---------------------------------------------------------------------------
# Rule-based resolvers --------------------------------------------------------
# ---------------------------------------------------------------------------

def _same_cache_mshrs(ci: CacheIndex) -> List[MSHRSnapshot]:
    return [ci.mshrs[i] for i in sorted(ci.mshrs)]


# ----- level / peer identification (now topology-driven) -----

def _peer_cache(cache: str, ctx: Optional[AnalyzerContext] = None) -> Optional[str]:
    """Return the upstream peer of ``cache``.

    With topology inference enabled this delegates to the inferred
    topology; the legacy hard-coded ``L1_n -> L2_n`` mapping is kept
    only as a final fallback when no context is supplied.
    """
    if ctx is not None:
        return ctx.topology.parent(cache)
    m = re.fullmatch(r"L1_(\d+)", cache)
    if m:
        return f"L2_{m.group(1)}"
    return None


def _find_target_mshr_by_set_tag(
    target_cache: Optional[str],
    pool: Dict[str, CacheIndex],
    set_val: Optional[int],
    tag_val: Optional[int],
) -> List[MSHRSnapshot]:
    """Return every active MSHR in *target_cache* whose stored set/tag match."""
    if target_cache is None or target_cache not in pool or set_val is None:
        return []
    out = []
    for s in _same_cache_mshrs(pool[target_cache]):
        if not s.req_valid:
            continue
        s_set = s.int_of("req_set")
        s_tag = s.int_of("req_tag")
        if s_set == set_val and (tag_val is None or s_tag == tag_val):
            out.append(s)
    return out


# ----- waiting-bit resolvers -----
# Each resolver returns a list of ``Edge`` that originate from ``src``.
# Resolvers accept an optional ``ctx`` so they can consult the inferred
# topology; ``ctx=None`` keeps the legacy hard-coded behaviour.

Resolver = Callable[[MSHRSnapshot, Dict[str, CacheIndex], Optional[AnalyzerContext]], List[Edge]]


def res_w_replResp(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                   ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """w_replResp = 0: Directory keeps issuing ``retry`` because every
    candidate way in the requested set is masked by some active MSHR's
    ``dirHit || blockRefill`` signal."""
    src_node = mshr_node(src)
    dir_node = directory_node(src.cache)
    edges = [Edge(src_node, dir_node, "w_replResp",
                  "Directory cannot pick a non-conflicting way")]
    my_set = src.int_of("req_set")
    if my_set is None:
        return edges
    for other in _same_cache_mshrs(pool[src.cache]):
        if other is src or not other.req_valid:
            continue
        if other.int_of("io_msInfo_bits_set") != my_set:
            continue
        holds = (other.bit("io_msInfo_bits_dirHit") == 1 or
                 other.bit("io_msInfo_bits_blockRefill") == 1)
        if holds:
            edges.append(Edge(dir_node, mshr_node(other), "dir_wayConflict",
                              f"way={other.int_of('io_msInfo_bits_way')} "
                              f"dirHit={other.bit('io_msInfo_bits_dirHit')} "
                              f"blockRefill={other.bit('io_msInfo_bits_blockRefill')}"))
    return edges


def res_w_grant(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """w_grant* = 0: waiting Grant/GrantData on sink_d.  The supplier is the
    upstream peer cache (resolved via the inferred topology)."""
    src_node = mshr_node(src)
    peer = _peer_cache(src.cache, ctx)
    edges: List[Edge] = [
        Edge(src_node, channel_node(src.cache, "D", "in"), "w_grant",
             "waiting on sink_d (Grant/GrantData from upstream)"),
    ]
    if peer is None or peer not in pool:
        edges.append(Edge(channel_node(src.cache, "D", "in"),
                          f"EXT::{src.cache}::upstream", "ext_grant",
                          "upstream cache out of model"))
        return edges
    found = False
    for b in _same_cache_mshrs(pool[peer]):
        if not b.req_valid or b.will_free:
            continue
        # Case 1: peer has a pending Probe (B) that is not yet acked
        if b.int_of("req_channel") == 2 and (
            b.bit("state_w_pprobeacklast") == 0 or b.bit("state_s_probeack") == 0):
            edges.append(Edge(channel_node(src.cache, "D", "in"), mshr_node(b),
                              "grant_blocked_by_pprobe",
                              "upstream peer stuck on unfinished Probe"))
            found = True
        # Case 2: peer has an A-miss whose own Grant is pending (transitive)
        elif b.int_of("req_channel") == 1 and b.bit("state_w_grantlast") == 0:
            edges.append(Edge(channel_node(src.cache, "D", "in"), mshr_node(b),
                              "grant_blocked_by_transitive_miss",
                              "upstream peer also waiting on a Grant"))
            found = True
    if not found:
        edges.append(Edge(channel_node(src.cache, "D", "in"),
                          f"EXT::{peer}::grant_source", "ext_grant",
                          "no visible blocker upstream (starvation)"))
    return edges


def res_w_releaseack(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                     ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """w_releaseack = 0: waiting ReleaseAck on sink_d from the upstream."""
    return [Edge(mshr_node(src), channel_node(src.cache, "D", "in"),
                 "w_releaseack", "waiting on sink_d (ReleaseAck)")]


def res_w_pprobe(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                 ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """w_pprobeack* = 0: waiting ProbeAck on sink_c from downstream clients.

    Downstream child caches are obtained from the inferred topology
    when available, falling back to the legacy ``L2_n -> L1_n`` mapping.
    """
    src_node = mshr_node(src)
    edges: List[Edge] = [Edge(src_node, channel_node(src.cache, "C", "in"),
                              "w_pprobe", "waiting on sink_c (ProbeAck)")]
    # Resolve children via topology; fall back to the L2_n -> L1_n rule.
    children: List[str]
    if ctx is not None:
        children = ctx.topology.children(src.cache)
    else:
        m = re.fullmatch(r"L2_(\d+)", src.cache)
        children = [f"L1_{m.group(1)}"] if m else []
    children = [c for c in children if c in pool]
    if not children:
        edges.append(Edge(channel_node(src.cache, "C", "in"),
                          f"EXT::{src.cache}::child", "ext_probeack",
                          "no downstream cache modelled"))
        return edges
    my_set = src.int_of("req_set")
    my_tag = src.int_of("req_tag")
    any_blocker = False
    for child in children:
        collisions = _find_target_mshr_by_set_tag(child, pool, my_set, None)
        addr_conflict, replace_conflict = [], []
        for b in collisions:
            if b.int_of("io_msInfo_bits_reqTag") == my_tag and not b.will_free:
                if b.bit("io_msInfo_bits_nestB") != 1:
                    addr_conflict.append(b)
            if (b.int_of("io_msInfo_bits_metaTag") == my_tag
                    and b.bit("io_msInfo_bits_blockRefill") == 1):
                replace_conflict.append(b)
        for b in addr_conflict:
            edges.append(Edge(channel_node(src.cache, "C", "in"), mshr_node(b),
                              "probe_rejected_addrConflict",
                              "downstream SinkB.addrConflict holds the Probe"))
            any_blocker = True
        for b in replace_conflict:
            edges.append(Edge(channel_node(src.cache, "C", "in"), mshr_node(b),
                              "probe_rejected_replaceConflict",
                              "downstream SinkB.replaceConflict holds the Probe"))
            any_blocker = True
        if not addr_conflict and not replace_conflict:
            active = [mm for mm in _same_cache_mshrs(pool[child]) if mm.req_valid]
            if len(active) >= max(1, len(pool[child].mshrs) - 1):
                for b in active:
                    edges.append(Edge(channel_node(src.cache, "C", "in"),
                                      mshr_node(b),
                                      "probe_blocked_mshrFull",
                                      "downstream MSHR capacity full (blockB_s1)"))
                any_blocker = True
    if not any_blocker:
        edges.append(Edge(channel_node(src.cache, "C", "in"),
                          f"EXT::{src.cache}::probeack_source", "ext_probeack",
                          "no visible conflict; starvation in downstream Probe pipeline"))
    return edges


def res_w_rprobe(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                 ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """w_rprobeack* = 0: release-induced Probe; resolves like pprobe."""
    return res_w_pprobe(src, pool, ctx)


# ----- scheduling-bit resolvers -----

def res_s_acquire(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                  ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """s_acquire = 0: want to send Acquire but source_a.fire never asserted."""
    return [Edge(mshr_node(src), channel_node(src.cache, "A", "out"),
                 "s_acquire", "Acquire ready=0, upstream not accepting")]


def res_s_pprobe(src: MSHRSnapshot, pool: Dict[str, CacheIndex],
                 ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """s_pprobe/s_rprobe = 0: want to send Probe out but SourceB can't fire."""
    return [Edge(mshr_node(src), channel_node(src.cache, "B", "out"),
                 "s_pprobe", "Probe can't fire (SourceB queue full or addr-conflict)")]


def res_mp_task(src: MSHRSnapshot, pool: Dict[str, CacheIndex], tag: str,
                ctx: Optional[AnalyzerContext] = None) -> List[Edge]:
    """Common resolver for ``s_refill / s_probeack / s_release = 0``.

    The scheduler grants the MainPipe only when all guarding ``w_*`` bits
    are set.  We only emit an edge if every guarding ``w_*`` is already 1
    (otherwise the real wait is on the ``w_*`` bit, and we'd double-count).
    When emitted, it points at the MainPipe pipeline node because
    ``mainpipe.ready`` is the sole remaining knob."""
    return [Edge(mshr_node(src), pipeline_node(src.cache, "mainpipe"),
                 tag, "mainpipe.ready=0 (s1..s5 backpressure)")]


# ---------------------------------------------------------------------------
# Rule table & generic resolver -----------------------------------------------
# ---------------------------------------------------------------------------

# Specialised hand-written resolvers indexed by FSM bit.  These take
# priority over the generic resolver when a bit appears in this table.
_SPECIALISED_WAIT_RESOLVERS: Dict[str, Resolver] = {
    "state_w_replResp":        res_w_replResp,
    "state_w_grantlast":       res_w_grant,
    "state_w_grant":           res_w_grant,
    "state_w_grantfirst":      res_w_grant,
    "state_w_releaseack":      res_w_releaseack,
    "state_w_pprobeacklast":   res_w_pprobe,
    "state_w_pprobeackfirst":  res_w_pprobe,
    "state_w_pprobeack":       res_w_pprobe,
    "state_w_rprobeacklast":   res_w_rprobe,
    "state_w_rprobeackfirst":  res_w_rprobe,
}

_SPECIALISED_SCHED_RESOLVERS: Dict[str, Resolver] = {
    "state_s_acquire":  res_s_acquire,
    "state_s_pprobe":   res_s_pprobe,
    "state_s_rprobe":   res_s_pprobe,
    "state_s_refill":   lambda s, p, c=None: res_mp_task(s, p, "s_refill", c),
    "state_s_release":  lambda s, p, c=None: res_mp_task(s, p, "s_release", c),
    "state_s_probeack": lambda s, p, c=None: res_mp_task(s, p, "s_probeack", c),
}

# ---- Backward-compat list-of-tuples views (kept for external imports) ------
WAIT_RESOLVERS: List[Tuple[str, Resolver]] = list(_SPECIALISED_WAIT_RESOLVERS.items())
SCHED_RESOLVERS: List[Tuple[str, Sequence[str], Resolver]] = [
    ("state_s_acquire",  (), res_s_acquire),
    ("state_s_pprobe",   (), res_s_pprobe),
    ("state_s_rprobe",   (), res_s_pprobe),
    ("state_s_refill",   ("state_w_grantlast", "state_w_rprobeacklast", "state_w_replResp"),
                         lambda s, p, c=None: res_mp_task(s, p, "s_refill", c)),
    ("state_s_release",  ("state_w_rprobeacklast", "state_w_grantlast", "state_w_replResp"),
                         lambda s, p, c=None: res_mp_task(s, p, "s_release", c)),
    ("state_s_probeack", ("state_w_pprobeacklast",),
                         lambda s, p, c=None: res_mp_task(s, p, "s_probeack", c)),
]


def _generic_resolver(src: MSHRSnapshot, bit: str,
                      pool: Dict[str, CacheIndex],
                      ctx: AnalyzerContext) -> List[Edge]:
    """Fall-back resolver for bits not covered by a hand-written rule.

    Uses the schema's :class:`DriverInfo` (the driver-provenance map
    extracted from FIRRTL) to construct a single structural edge from
    the MSHR to the inferred driver-owner node.  This is what keeps the
    dependency chain from breaking when a new ``w_*`` / ``s_*`` bit
    appears in a future commit.
    """
    sch = src.schema
    if sch is None:
        return []
    drv = sch.drivers.get(bit)
    if drv is None:
        return []
    short = bit.replace("state_", "")
    detail = (f"[generic] driver={drv.kind}"
              + (f".{drv.channel}-{drv.direction}" if drv.kind == "channel"
                 else (f".{drv.channel}" if drv.channel else "")))
    if drv.kind == "channel" and drv.channel and drv.direction:
        return [Edge(mshr_node(src),
                     channel_node(src.cache, drv.channel, drv.direction),
                     short, detail)]
    if drv.kind == "directory":
        return [Edge(mshr_node(src), directory_node(src.cache),
                     short, detail)]
    if drv.kind == "pipeline":
        stage = drv.channel or "pipeline"
        return [Edge(mshr_node(src), pipeline_node(src.cache, stage),
                     short, detail)]
    return [Edge(mshr_node(src),
                 f"EXT::{src.cache}::driver_for_{short}", short,
                 "[generic] driver=unknown (no FIRRTL match)")]


# ---------------------------------------------------------------------------
# Graph construction ----------------------------------------------------------
# ---------------------------------------------------------------------------

def build_wait_graph(pool: Dict[str, CacheIndex],
                     ctx: Optional[AnalyzerContext] = None
                     ) -> Tuple["nx.MultiDiGraph", List[MSHRSnapshot]]:
    """Build the wait-for graph and the list of stalled MSHRs.

    When ``ctx`` is supplied (the default for auto-discovery runs),
    per-MSHR schemas drive bit enumeration and unspecialised bits fall
    through to the generic resolver derived from driver provenance.
    When ``ctx`` is ``None`` the analyzer falls back to the legacy
    hard-coded WAIT_RESOLVERS / SCHED_RESOLVERS tables.
    """
    g: "nx.MultiDiGraph" = nx.MultiDiGraph()
    stalled: List[MSHRSnapshot] = []
    seen_edges: Set[Tuple[str, str, str]] = set()

    def _emit(edge: Edge) -> None:
        key = (edge.src, edge.dst, edge.reason)
        if key in seen_edges:
            return
        seen_edges.add(key)
        _add_node_if_absent(g, edge.src)
        _add_node_if_absent(g, edge.dst)
        g.add_edge(edge.src, edge.dst, reason=edge.reason, detail=edge.detail)

    # 1. register every stalled MSHR as a node and attach metadata
    for ci in pool.values():
        for s in _same_cache_mshrs(ci):
            if not s.is_stalled:
                continue
            stalled.append(s)
            g.add_node(mshr_node(s), kind="mshr", label=s.label,
                       cache=s.cache, mshr_id=s.mshr_id,
                       state=_state_bitmap(s))

    if ctx is None:
        # ---- legacy path (no auto-discovery) -------------------------------
        for s in stalled:
            for wbit, resolver in WAIT_RESOLVERS:
                if s.bit(wbit) == 0:
                    for e in resolver(s, pool, None):
                        _emit(e)
        for s in stalled:
            for sbit, guards, resolver in SCHED_RESOLVERS:
                if s.bit(sbit) != 0:
                    continue
                if not all(s.bit(g_) == 1 for g_ in guards):
                    continue
                for e in resolver(s, pool, None):
                    _emit(e)
        return g, stalled

    # ---- auto-discovery path ------------------------------------------
    # 2. emit waiting-bit edges
    for s in stalled:
        sch = s.schema or _BUILTIN_SCHEMA
        for wbit in sch.waiting_bits:
            if s.bit(wbit) != 0:
                continue
            res = _SPECIALISED_WAIT_RESOLVERS.get(wbit)
            if res is not None:
                ctx.coverage_specialised.add(wbit)
                edges = res(s, pool, ctx)
            else:
                ctx.coverage_generic.add(wbit)
                edges = _generic_resolver(s, wbit, pool, ctx)
            ctx.coverage[wbit] += len(edges)
            for e in edges:
                _emit(e)

    # 3. emit scheduling-bit edges (guarded by schema's guard_map)
    for s in stalled:
        sch = s.schema or _BUILTIN_SCHEMA
        for sbit in sch.scheduling_bits:
            if s.bit(sbit) != 0:
                continue
            guards = sch.guard_map.get(sbit, [])
            if guards and not all(s.bit(g_) == 1 for g_ in guards):
                continue
            res = _SPECIALISED_SCHED_RESOLVERS.get(sbit)
            if res is not None:
                ctx.coverage_specialised.add(sbit)
                edges = res(s, pool, ctx)
            else:
                ctx.coverage_generic.add(sbit)
                edges = _generic_resolver(s, sbit, pool, ctx)
            ctx.coverage[sbit] += len(edges)
            for e in edges:
                _emit(e)

    return g, stalled


def _state_bitmap(s: MSHRSnapshot) -> str:
    sch = s.schema or _BUILTIN_SCHEMA
    parts = []
    for bit in sch.scheduling_bits + sch.waiting_bits:
        short = bit.replace("state_", "")
        v = s.bit(bit)
        parts.append(f"{short}={v}")
    return " ".join(parts)


def _add_node_if_absent(g: "nx.MultiDiGraph", node: str) -> None:
    if node in g.nodes:
        return
    if node.startswith("DIR::"):
        g.add_node(node, kind="directory", label=f"Directory[{node[5:]}]")
    elif node.startswith("CH::"):
        _, cache, ch, direction = node.split("::")
        g.add_node(node, kind="channel", label=f"{cache} {ch}-{direction}")
    elif node.startswith("PIPE::"):
        _, cache, stage = node.split("::")
        g.add_node(node, kind="pipeline", label=f"{cache} {stage}")
    elif node.startswith("EXT::"):
        g.add_node(node, kind="external", label=node[5:])
    else:
        g.add_node(node, kind="unknown", label=node)


# ---------------------------------------------------------------------------
# Cycle analysis & collapse ---------------------------------------------------
# ---------------------------------------------------------------------------

def find_cycles(g: "nx.MultiDiGraph") -> List[List[str]]:
    # simple_cycles wants a DiGraph; collapse multi-edges by projecting.
    dg = nx.DiGraph()
    for u, v, data in g.edges(data=True):
        if dg.has_edge(u, v):
            dg[u][v]["reasons"].add(data["reason"])
        else:
            dg.add_edge(u, v, reasons={data["reason"]})
    try:
        cycles = list(nx.simple_cycles(dg))
    except Exception as err:
        print(f"[warn] simple_cycles failed: {err}", file=sys.stderr)
        cycles = []
    # Deduplicate by canonical rotation
    seen = set()
    out = []
    for c in cycles:
        if not c:
            continue
        key = tuple(_canonicalise_cycle(c))
        if key in seen:
            continue
        seen.add(key)
        out.append(list(key))
    out.sort(key=lambda c: (len(c), c))
    return out


def _canonicalise_cycle(cycle: List[str]) -> List[str]:
    # Rotate so that the lexicographically smallest node comes first.
    pivot = cycle.index(min(cycle))
    return cycle[pivot:] + cycle[:pivot]


# ---------------------------------------------------------------------------
# Root-cause heuristics -------------------------------------------------------
# ---------------------------------------------------------------------------

def classify_cycle(cycle: List[str], g: "nx.MultiDiGraph") -> str:
    """Return a short tag describing the dominant cause of ``cycle``.

    * "livelock (replacer-retry)" if a Directory node appears and at least
      one MSHR in the cycle has ``w_replResp=0``.
    * "deadlock (Probe ↔ Grant)" if the cycle spans two caches through
      pprobe + grant edges.
    * "stall cycle" otherwise.
    """
    reasons: Set[str] = set()
    has_dir = any(n.startswith("DIR::") for n in cycle)
    caches: Set[str] = set()
    for u in cycle:
        if u.startswith("M::"):
            caches.add(u.split("::")[1])
    # collect edge reasons around the cycle (MultiDiGraph-safe)
    pairs = list(zip(cycle, cycle[1:] + cycle[:1]))
    for u, v in pairs:
        if g.has_edge(u, v):
            for _, data in g.get_edge_data(u, v).items():
                reasons.add(data.get("reason", ""))
    has_probe = any(r in reasons for r in
                    ("w_pprobeacklast", "w_pprobeackfirst", "w_pprobeack",
                     "w_rprobeacklast", "w_rprobeackfirst",
                     "probe_rejected_addrConflict",
                     "probe_rejected_replaceConflict",
                     "probe_blocked_mshrFull", "w_pprobe"))
    has_grant = any(r in reasons for r in
                    ("w_grant", "w_grantlast", "w_grantfirst",
                     "grant_blocked_by_pprobe",
                     "grant_blocked_by_transitive_miss"))
    if has_dir and "w_replResp" in reasons:
        return f"livelock (replacer-retry), caches={sorted(caches)}"
    if has_probe and has_grant and len(caches) >= 2:
        return f"deadlock (cross-cache Probe/Grant), caches={sorted(caches)}"
    if has_probe and has_grant:
        return f"deadlock (Probe/Grant interlock in {sorted(caches)})"
    return f"stall cycle, caches={sorted(caches)}"


# ---------------------------------------------------------------------------
# Reporting -------------------------------------------------------------------
# ---------------------------------------------------------------------------

def emit_dot(g: "nx.MultiDiGraph", cycles: List[List[str]], path: str) -> None:
    lines = ["digraph deadlock {", '  rankdir=LR; node [fontname="Helvetica"];']
    cycle_nodes: Set[str] = set()
    for c in cycles:
        cycle_nodes.update(c)
    cycle_edges: Set[Tuple[str, str]] = set()
    for c in cycles:
        for u, v in zip(c, c[1:] + c[:1]):
            cycle_edges.add((u, v))
    for n, data in g.nodes(data=True):
        kind = data.get("kind", "unknown")
        shape = {"mshr": "box", "directory": "hexagon",
                 "channel": "oval", "pipeline": "parallelogram",
                 "external": "note"}.get(kind, "ellipse")
        color = "red" if n in cycle_nodes else "black"
        label = data.get("label", n).replace('"', '')
        lines.append(f'  "{n}" [shape={shape}, color={color}, label="{label}"];')
    for u, v, data in g.edges(data=True):
        color = "red" if (u, v) in cycle_edges else "gray40"
        lbl = data.get("reason", "")
        lines.append(f'  "{u}" -> "{v}" [color={color}, label="{lbl}"];')
    lines.append("}")
    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n")


def print_text_report(g: "nx.MultiDiGraph", stalled: List[MSHRSnapshot],
                      cycles: List[List[str]],
                      ctx: Optional[AnalyzerContext] = None) -> None:
    print(f"=== stalled MSHRs ({len(stalled)}) ===")
    for s in stalled:
        sch = s.schema or _BUILTIN_SCHEMA
        print(f"  {s.label}    [{sch.family}]")
        print(f"    scheduling: " + ", ".join(
            f"{b.replace('state_','')}={s.bit(b)}" for b in sch.scheduling_bits))
        print(f"    waiting:    " + ", ".join(
            f"{b.replace('state_','')}={s.bit(b)}" for b in sch.waiting_bits))

    print(f"\n=== dependency edges ({g.number_of_edges()}) ===")
    for u, v, data in g.edges(data=True):
        lbl = data.get("reason", "")
        detail = data.get("detail", "")
        extra = f" ({detail})" if detail else ""
        print(f"  {u}  --[{lbl}]-->  {v}{extra}")

    if not cycles:
        print("\n=== cycles ===")
        print("  (none - graph is a DAG: longest-stall or starvation, not deadlock)")
    else:
        print(f"\n=== cycles ({len(cycles)}) ===")
        for i, c in enumerate(cycles, 1):
            tag = classify_cycle(c, g)
            print(f"  cycle #{i}  [{tag}]  len={len(c)}")
            path = c + [c[0]]
            for j in range(len(path) - 1):
                u, v = path[j], path[j + 1]
                reasons = sorted({d.get("reason", "")
                                  for _, d in (g.get_edge_data(u, v) or {}).items()})
                edge_txt = ",".join(reasons) if reasons else "?"
                u_lbl = g.nodes[u].get("label", u)
                print(f"    {u_lbl}")
                print(f"         --[{edge_txt}]-->")
            print(f"    {g.nodes[c[0]].get('label', c[0])}  (back to start)")

    # ---- rule-coverage telemetry ----------------------------------------
    if ctx is None:
        return
    print("\n=== rule coverage ===")
    print(f"  schemas in use: " +
          ", ".join(sorted(set(s.family for s in ctx.schemas.values()))))
    parents = {c: ctx.topology.parent(c) for c in sorted(ctx.schemas.keys())}
    print(f"  topology.parent_of   = {parents}")
    print(f"  topology.children_of = {dict(ctx.topology.children_of)}")
    if ctx.coverage_specialised or ctx.coverage_generic or ctx.coverage:
        print("  bit-level coverage (only zero-valued bits at end-of-sim are listed):")
        all_bits = sorted(set(ctx.coverage.keys())
                          | ctx.coverage_specialised | ctx.coverage_generic)
        for bit in all_bits:
            if bit in ctx.coverage_specialised:
                kind = "specialised"
            elif bit in ctx.coverage_generic:
                kind = "generic    "
            else:
                kind = "unused     "
            cnt = ctx.coverage.get(bit, 0)
            print(f"    {kind} {bit:36s} edges={cnt}")
    # warn on any stalled MSHR with zero out-degree
    zero_out = [s for s in stalled if g.out_degree(mshr_node(s)) == 0]
    if zero_out:
        print("  ⚠ stalled MSHRs with zero out-degree (rule decay candidate):")
        for s in zero_out:
            print(f"      {s.label}  state=[{_state_bitmap(s)}]")
    else:
        print("  every stalled MSHR has at least one outgoing edge ✓")


# ---------------------------------------------------------------------------
# CLI -------------------------------------------------------------------------
# ---------------------------------------------------------------------------

DEFAULT_FST = "/Users/ALIENWARE/Research/XiangShan/CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v2/XiangShan-CoupledL2-deadlock-v2.fst"


def _resolve_schemas(args) -> List[Schema]:
    """Build the schema list according to CLI flags.

    With ``--no-auto`` the analyzer uses only the built-in schema.
    Otherwise, if ``--firrtl`` is provided (or auto-detected next to
    the FST), schema discovery and driver-provenance mining produce a
    derived schema that supersedes the built-in for matching caches;
    the built-in is kept as a co-resident fallback so caches that the
    derived schema's regex doesn't match (e.g. HuanCun L3 alongside
    CoupledL2 L1/L2) still get picked up.
    """
    if args.no_auto:
        print("[schema] auto-discovery disabled (--no-auto): "
              "using built-in CoupledL2 schema")
        return [_BUILTIN_SCHEMA]
    fir_path = args.firrtl or auto_detect_firrtl(args.fst)
    if fir_path and os.path.exists(fir_path):
        print(f"[schema] source: {fir_path}")
        derived, _instmap = _scan_fir_file(fir_path)
        if derived is not None:
            n_uncovered = sum(1 for d in derived.drivers.values()
                              if d.kind == "unknown")
            print(f"[schema] derived: family={derived.family} "
                  f"sched={len(derived.scheduling_bits)} "
                  f"wait={len(derived.waiting_bits)} "
                  f"will_free_terms={len(derived.will_free_terms)} "
                  f"guards={len(derived.guard_map)} "
                  f"drivers_unknown={n_uncovered}/{len(derived.drivers)}")
            # keep built-in as a co-resident schema (its regex covers
            # the same paths but never wins because the derived schema
            # is tried first); harmless, but documents the fallback.
            return [derived, _BUILTIN_SCHEMA]
        print("[schema] FIRRTL parse yielded no schema; "
              "falling back to built-in")
    else:
        print("[schema] no FIRRTL found; using built-in CoupledL2 schema")
    return [_BUILTIN_SCHEMA]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fst", default=DEFAULT_FST, help="path to the FST waveform")
    ap.add_argument("--dot", default="deadlock_graph_l2.dot",
                    help="where to write the .dot file")
    ap.add_argument("--png", default=None,
                    help="optional: render the graph to this PNG via `dot`")
    ap.add_argument("--firrtl", default=None,
                    help="path to a Chisel/FIRRTL .fir file used for "
                         "schema and driver-provenance discovery")
    ap.add_argument("--no-auto", action="store_true",
                    help="disable FIRRTL-driven auto-discovery and use "
                         "the bundled hard-coded rule base "
                         "(legacy behaviour)")
    ap.add_argument("--strict", action="store_true",
                    help="exit with non-zero status if any stalled MSHR "
                         "has zero out-degree (CI gate against rule decay)")
    args = ap.parse_args()

    if not os.path.exists(args.fst):
        sys.exit(f"[fatal] no such FST: {args.fst}")

    schemas = _resolve_schemas(args)

    print(f"[info] replaying {args.fst}")
    pool, end_time, _traces, cache_to_schema = replay_fst(args.fst, schemas)
    print(f"[info] simulation end-time = {end_time}; caches found = "
          f"{sorted(pool.keys())}; total MSHRs = "
          f"{sum(len(c.mshrs) for c in pool.values())}")

    if args.no_auto:
        ctx: Optional[AnalyzerContext] = None
    else:
        topo = _infer_topology(pool)
        ctx = AnalyzerContext(topology=topo, schemas=cache_to_schema)
        print(f"[topology] inferred parent_of="
              f"{ {c: topo.parent(c) for c in sorted(pool.keys())} }")

    g, stalled = build_wait_graph(pool, ctx)
    print(f"[info] stalled MSHRs: {len(stalled)} / "
          f"{sum(len(c.mshrs) for c in pool.values())}")

    cycles = find_cycles(g)
    print_text_report(g, stalled, cycles, ctx)
    emit_dot(g, cycles, args.dot)
    print(f"\n[info] wrote {args.dot}")
    if args.png:
        import subprocess
        try:
            subprocess.run(["dot", "-Tpng", args.dot, "-o", args.png], check=True)
            print(f"[info] rendered {args.png}")
        except (subprocess.SubprocessError, FileNotFoundError) as err:
            print(f"[warn] graphviz `dot` not available: {err}")

    if args.strict:
        zero_out = [s for s in stalled if g.out_degree(mshr_node(s)) == 0]
        if zero_out:
            print(f"[strict] {len(zero_out)} stalled MSHR(s) have zero "
                  f"out-degree; treating as failure", file=sys.stderr)
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
