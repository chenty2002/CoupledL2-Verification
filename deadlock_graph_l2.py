"""Deadlock / stall dependency-graph builder for CoupledL2-based FST waveforms.

This script is self-contained.  It:

  1. Opens an FST waveform.
  2. Auto-discovers every MSHR instance under every cache slice that follows
     the ``*.slices_<n>.mshrCtl.mshrs_<i>`` convention used by XiangShan's
     CoupledL2 / CoupledL2AsL1 modules.
  3. Replays the FST and records, for every MSHR and every time step, the
     value of the FSM scheduling bits (``state.s_*``), waiting bits
     (``state.w_*``), the externally visible ``io_msInfo_*`` / ``io_status_*``
     fields plus the low-level response / task handshake signals that are
     relevant for dependency resolution.
  4. Picks a "stall window" (end of simulation) and, for each MSHR that is
     still ``req_valid`` but not ``will_free`` at that time, builds a
     directed "wait-for" graph whose nodes include MSHRs, the per-cache
     Directory, the per-cache SinkB / SourceB channels, and MainPipe stages.
     Edges are created from each unresolved ``w_*`` or blocked ``s_*`` bit
     according to the static Chisel-derived rule base encoded below.
  5. Finds all simple cycles in that graph.  A non-empty cycle set is the
     canonical evidence of a (live)lock; an empty set (pure DAG) indicates a
     one-way stall or input starvation.
  6. Emits ``deadlock_graph.dot`` (render with ``dot -Tpng``) and a textual
     report on stdout.
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
# Configuration ---------------------------------------------------------------
# ---------------------------------------------------------------------------

# All "stateful" per-MSHR signals we need for dependency resolution.  The
# trailing ``" [n:0]"`` is stripped transparently when we look them up.
SCHEDULING_BITS = [
    "state_s_acquire", "state_s_rprobe", "state_s_pprobe",
    "state_s_probeack", "state_s_refill", "state_s_release", "state_s_retry",
]
WAITING_BITS = [
    "state_w_rprobeackfirst", "state_w_rprobeacklast",
    "state_w_pprobeackfirst", "state_w_pprobeacklast", "state_w_pprobeack",
    "state_w_grantfirst", "state_w_grantlast", "state_w_grant",
    "state_w_releaseack", "state_w_replResp",
]
CONTEXT_FIELDS = [
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
    # task & response ports (observed to corroborate graph edges)
    "io_tasks_source_a_valid", "io_tasks_source_a_ready",
    "io_tasks_source_b_valid", "io_tasks_source_b_ready",
    "io_tasks_mainpipe_valid", "io_tasks_mainpipe_ready",
    "io_resps_sink_c_valid", "io_resps_sink_d_valid",
    "io_replResp_valid", "io_replResp_bits_retry", "io_replResp_bits_way",
]
ALL_MSHR_FIELDS = SCHEDULING_BITS + WAITING_BITS + CONTEXT_FIELDS


# Regex that matches the fully-qualified scope of a single MSHR instance in
# CoupledL2's FST, e.g.
#   VerifyTop.coupledL2AsL1.slices_0.mshrCtl.mshrs_3
#   VerifyTop.coupledL2_1.slices_0.mshrCtl.mshrs_0
MSHR_PATH_RE = re.compile(r"^(?P<prefix>.+?\.slices_\d+\.mshrCtl)\.mshrs_(?P<idx>\d+)\.(?P<leaf>.+)$")


# ---------------------------------------------------------------------------
# Data structures -------------------------------------------------------------
# ---------------------------------------------------------------------------

@dataclass
class MSHRSnapshot:
    """Value of every tracked signal for one MSHR at the chosen time."""
    cache: str
    mshr_id: int
    fields: Dict[str, str] = field(default_factory=dict)

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
        return self.bit("io_status_bits_will_free") == 1 or self.bit("io_msInfo_bits_willFree") == 1

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

def discover_mshr_instances(signals) -> Dict[str, CacheIndex]:
    """Walk signal names and group them by cache / MSHR id."""
    by_cache: Dict[str, CacheIndex] = {}
    for full in signals.by_name:
        m = MSHR_PATH_RE.match(full)
        if not m:
            continue
        base = m.group("prefix")
        idx = int(m.group("idx"))
        label = _cache_label_of(base)
        ci = by_cache.setdefault(label, CacheIndex(cache=label, base=base))
        ci.mshrs.setdefault(idx, MSHRSnapshot(cache=label, mshr_id=idx))
    return by_cache


def _cache_label_of(base: str) -> str:
    """Turn ``VerifyTop.coupledL2AsL1.slices_0.mshrCtl`` into ``L1_0``, etc."""
    # Strip slices_N.mshrCtl
    core = base
    core = re.sub(r"\.slices_\d+\.mshrCtl$", "", core)
    core = core.split(".", 1)[-1]   # drop 'VerifyTop'
    # coupledL2AsL1[_N] -> L1_N    (N defaults to 0 when absent)
    m = re.fullmatch(r"coupledL2AsL1(?:_(\d+))?", core)
    if m:
        return f"L1_{m.group(1) or '0'}"
    m = re.fullmatch(r"coupledL2(?:_(\d+))?", core)
    if m:
        return f"L2_{m.group(1) or '0'}"
    return core


def build_handle_table(fst, by_cache: Dict[str, CacheIndex], signals):
    """Map FST handles to ``(cache_label, mshr_id, leaf_signal)``."""
    table: Dict[int, Tuple[str, int, str]] = {}
    for ci in by_cache.values():
        for mi in ci.mshrs:
            prefix = f"{ci.base}.mshrs_{mi}."
            for leaf in ALL_MSHR_FIELDS:
                exact = prefix + leaf
                sig = signals.by_name.get(exact)
                if sig is None:
                    # try with bracketed width suffix (e.g. " [2:0]")
                    for name, s2 in signals.by_name.items():
                        if name.startswith(exact) and name[len(exact):].startswith(" ["):
                            sig = s2
                            break
                if sig is not None:
                    table[sig.handle] = (ci.cache, mi, leaf)
    return table


def replay_fst(fst_path: str) -> Tuple[Dict[str, CacheIndex], int, Dict[Tuple[str, int, str], List[Tuple[int, str]]]]:
    """Replay the waveform; return (cache index, end_time, per-signal traces)."""
    fst = lib.fstReaderOpen(fst_path.encode())
    if fst == ffi.NULL:
        sys.exit(f"[fatal] could not open FST: {fst_path}")
    _, signals = pf.get_scopes_signals2(fst)
    by_cache = discover_mshr_instances(signals)
    handle_table = build_handle_table(fst, by_cache, signals)
    if not handle_table:
        sys.exit("[fatal] no MSHR signals found; is the FST path correct?")

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

    # Freeze final snapshots
    for handle, (cache, mi, leaf) in handle_table.items():
        val = latest.get(handle, (0, "x"))[1]
        by_cache[cache].mshrs[mi].fields[leaf] = val

    lib.fstReaderClose(fst)
    return by_cache, int(end_time), traces


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


# ----- level / peer identification -----

def _peer_cache(cache: str) -> Optional[str]:
    """Return the "other side" of a TileLink link for TL-message edges."""
    # Topology assumption (verified against VerifyTop.scala):
    #   L1_n <-> L2_n   (n in {0,1})
    #   L2_n <-> L3     (L3 is out of visible scope; we leave it external)
    m = re.fullmatch(r"L1_(\d+)", cache)
    if m:
        return f"L2_{m.group(1)}"
    m = re.fullmatch(r"L2_(\d+)", cache)
    if m:
        return None   # upstream is L3 but L3's MSHRs are HuanCun; out of model
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

Resolver = Callable[[MSHRSnapshot, Dict[str, CacheIndex]], List[Edge]]


def res_w_replResp(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """w_replResp = 0: Directory keeps issuing ``retry`` because every
    candidate way in the requested set is masked by some active MSHR's
    ``dirHit || blockRefill`` signal."""
    src_node = mshr_node(src)
    dir_node = directory_node(src.cache)
    edges = [Edge(src_node, dir_node, "w_replResp",
                  "Directory cannot pick a non-conflicting way")]
    # All MSHRs in the same cache + same set that still hold the set are
    # the concrete blockers of the replacer.
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


def res_w_grant(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """w_grant* = 0: waiting Grant/GrantData on sink_d.  The supplier is the
    upstream peer cache (L2 for an L1 MSHR).  We model the shared TL-D bus
    as one ``CH`` node per cache; concrete blockers upstream are:

      * Any peer MSHR currently processing a Probe (``channel=B`` with
        ``w_pprobeack*=0``) — it holds the D-channel because the peer
        cannot simultaneously issue a Grant while an un-ack'd Probe is
        outstanding (order-of-operations in MainPipe).
      * Any peer MSHR on channel A that is itself stalled on a Grant from
        further upstream (transitive back-pressure).

    In both cases we draw an edge; set/tag comparison is *not* reliable
    across cache levels because ``setBits`` differs, so we connect on the
    structural fact "peer is busy and can't fire Grant toward us"."""
    src_node = mshr_node(src)
    peer = _peer_cache(src.cache)
    edges: List[Edge] = [
        Edge(src_node, channel_node(src.cache, "D", "in"), "w_grant",
             "waiting on sink_d (Grant/GrantData from upstream)"),
    ]
    if peer is None or peer not in pool:
        edges.append(Edge(channel_node(src.cache, "D", "in"),
                          f"EXT::{src.cache}::upstream", "ext_grant",
                          "upstream is L3/HuanCun (out of model)"))
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


def res_w_releaseack(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """w_releaseack = 0: waiting ReleaseAck on sink_d from the upstream."""
    src_node = mshr_node(src)
    return [Edge(src_node, channel_node(src.cache, "D", "in"), "w_releaseack",
                 "waiting on sink_d (ReleaseAck)")]


def res_w_pprobe(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """w_pprobeack* = 0: waiting ProbeAck on sink_c from downstream clients.
    The missing ProbeAck is almost always caused by the downstream cache
    rejecting / holding the Probe at its SinkB (addrConflict or replace
    conflict) or by its own MSHR being unable to send ProbeAck."""
    src_node = mshr_node(src)
    edges: List[Edge] = [Edge(src_node, channel_node(src.cache, "C", "in"),
                              "w_pprobe", "waiting on sink_c (ProbeAck)")]
    # Which downstream cache should be responding?  It's the *child* of this
    # cache in our topology; i.e. L2_n is waiting on L1_n.
    m = re.fullmatch(r"L2_(\d+)", src.cache)
    child = f"L1_{m.group(1)}" if m else None
    if child is None or child not in pool:
        edges.append(Edge(channel_node(src.cache, "C", "in"),
                          f"EXT::{src.cache}::child", "ext_probeack",
                          "no downstream cache modelled"))
        return edges
    my_set = src.int_of("req_set")
    my_tag = src.int_of("req_tag")          # L2 tag equals B-probe tag on wire
    # Case 1: some L1 MSHR still holds the line => SinkB.addrConflict / replaceConflict
    collisions = _find_target_mshr_by_set_tag(child, pool, my_set, None)
    addr_conflict = []
    replace_conflict = []
    for b in collisions:
        # SinkB.addrConflict: same set, b.reqTag == my_tag, !willFree && !nestB
        if b.int_of("io_msInfo_bits_reqTag") == my_tag and not b.will_free:
            if b.bit("io_msInfo_bits_nestB") != 1:
                addr_conflict.append(b)
        # SinkB.replaceConflict: same set, b.metaTag == my_tag, blockRefill
        if (b.int_of("io_msInfo_bits_metaTag") == my_tag
                and b.bit("io_msInfo_bits_blockRefill") == 1):
            replace_conflict.append(b)
    for b in addr_conflict:
        edges.append(Edge(channel_node(src.cache, "C", "in"), mshr_node(b),
                          "probe_rejected_addrConflict",
                          "downstream SinkB.addrConflict holds the Probe"))
    for b in replace_conflict:
        edges.append(Edge(channel_node(src.cache, "C", "in"), mshr_node(b),
                          "probe_rejected_replaceConflict",
                          "downstream SinkB.replaceConflict holds the Probe"))
    if not addr_conflict and not replace_conflict:
        # Case 2: MSHR-full capacity back-pressure (mshrFull -> blockB_s1)
        active = [m for m in _same_cache_mshrs(pool[child]) if m.req_valid]
        if len(active) >= max(1, len(pool[child].mshrs) - 1):
            for b in active:
                edges.append(Edge(channel_node(src.cache, "C", "in"), mshr_node(b),
                                  "probe_blocked_mshrFull",
                                  "downstream MSHR capacity full (blockB_s1)"))
        else:
            edges.append(Edge(channel_node(src.cache, "C", "in"),
                              f"EXT::{child}::probeack_source", "ext_probeack",
                              "no visible conflict; starvation in downstream Probe pipeline"))
    return edges


def res_w_rprobe(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """w_rprobeack* = 0: release-induced Probe to clients; resolves the same
    way as pprobe because both come in on sink_c."""
    return res_w_pprobe(src, pool)


# ----- scheduling-bit resolvers -----

def res_s_acquire(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """s_acquire = 0: want to send Acquire but source_a.fire has never been
    asserted.  Possible holders: upstream cache back-pressure."""
    src_node = mshr_node(src)
    return [Edge(src_node, channel_node(src.cache, "A", "out"), "s_acquire",
                 "Acquire ready=0, upstream not accepting")]


def res_s_pprobe(src: MSHRSnapshot, pool: Dict[str, CacheIndex]) -> List[Edge]:
    """s_pprobe/s_rprobe = 0: want to send Probe out but SourceB can't fire."""
    src_node = mshr_node(src)
    return [Edge(src_node, channel_node(src.cache, "B", "out"), "s_pprobe",
                 "Probe can't fire (SourceB queue full or addr-conflict)")]


def res_mp_task(src: MSHRSnapshot, pool: Dict[str, CacheIndex], tag: str) -> List[Edge]:
    """Common resolver for ``s_refill / s_probeack / s_release = 0``.

    The scheduler grants the MainPipe only when all guarding ``w_*`` bits
    are set.  We only emit an edge if every guarding ``w_*`` is already 1 
    (otherwise the real wait is on the ``w_*`` bit, and we'd double-count).
    When emitted, it points at the MainPipe pipeline node because 
    ``mainpipe.ready`` is the sole remaining knob."""
    return [Edge(mshr_node(src), pipeline_node(src.cache, "mainpipe"),
                 tag, "mainpipe.ready=0 (s1..s5 backpressure)")]


# ---------------------------------------------------------------------------
# Rule table ------------------------------------------------------------------
# ---------------------------------------------------------------------------

# For each ``w_*`` bit: it's a resolver that fires when ``bit == 0``.
WAIT_RESOLVERS: List[Tuple[str, Resolver]] = [
    ("state_w_replResp",        res_w_replResp),
    ("state_w_grantlast",       res_w_grant),
    ("state_w_grant",           res_w_grant),
    ("state_w_grantfirst",      res_w_grant),
    ("state_w_releaseack",      res_w_releaseack),
    ("state_w_pprobeacklast",   res_w_pprobe),
    ("state_w_pprobeackfirst",  res_w_pprobe),
    ("state_w_pprobeack",       res_w_pprobe),
    ("state_w_rprobeacklast",   res_w_rprobe),
    ("state_w_rprobeackfirst",  res_w_rprobe),
]

# Each entry: (s_bit, guard_w_bits, resolver)  (s_bit =0 ⇒ dependency)
SCHED_RESOLVERS: List[Tuple[str, Sequence[str], Callable[[MSHRSnapshot, Dict[str, CacheIndex]], List[Edge]]]] = [
    # s_acquire has no guard: always emit when =0
    ("state_s_acquire",  (), res_s_acquire),
    ("state_s_pprobe",   (), res_s_pprobe),
    ("state_s_rprobe",   (), res_s_pprobe),
    # the three mainpipe scheduling bits
    ("state_s_refill",   ("state_w_grantlast", "state_w_rprobeacklast", "state_w_replResp"),
                         lambda s, p: res_mp_task(s, p, "s_refill")),
    ("state_s_release",  ("state_w_rprobeacklast", "state_w_grantlast", "state_w_replResp"),
                         lambda s, p: res_mp_task(s, p, "s_release")),
    ("state_s_probeack", ("state_w_pprobeacklast",),
                         lambda s, p: res_mp_task(s, p, "s_probeack")),
]


# ---------------------------------------------------------------------------
# Graph construction ----------------------------------------------------------
# ---------------------------------------------------------------------------

def build_wait_graph(pool: Dict[str, CacheIndex]) -> Tuple["nx.MultiDiGraph", List[MSHRSnapshot]]:
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

    # 1. register every stalled MSHR as a node and attach its metadata
    for ci in pool.values():
        for s in _same_cache_mshrs(ci):
            if not s.is_stalled:
                continue
            stalled.append(s)
            g.add_node(mshr_node(s), kind="mshr", label=s.label,
                       cache=s.cache, mshr_id=s.mshr_id,
                       state=_state_bitmap(s))

    # 2. emit waiting-bit edges
    for s in stalled:
        for wbit, resolver in WAIT_RESOLVERS:
            if s.bit(wbit) == 0:
                for e in resolver(s, pool):
                    _emit(e)

    # 3. emit scheduling-bit edges (guarded to avoid duplicates)
    for s in stalled:
        for sbit, guards, resolver in SCHED_RESOLVERS:
            if s.bit(sbit) != 0:
                continue
            guards_ok = all(s.bit(g_) == 1 for g_ in guards)
            if not guards_ok:
                continue       # real wait is on the guard w_* bit
            for e in resolver(s, pool):
                _emit(e)

    return g, stalled


def _state_bitmap(s: MSHRSnapshot) -> str:
    parts = []
    for bit in SCHEDULING_BITS + WAITING_BITS:
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
        g.add_node(node, kind="pipeline", label=f"{cache} MainPipe")
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


def print_text_report(g: "nx.MultiDiGraph", stalled: List[MSHRSnapshot], cycles: List[List[str]]) -> None:
    print(f"=== stalled MSHRs ({len(stalled)}) ===")
    for s in stalled:
        print(f"  {s.label}")
        print(f"    scheduling: " + ", ".join(
            f"{b.replace('state_','')}={s.bit(b)}" for b in SCHEDULING_BITS))
        print(f"    waiting:    " + ", ".join(
            f"{b.replace('state_','')}={s.bit(b)}" for b in WAITING_BITS))

    print(f"\n=== dependency edges ({g.number_of_edges()}) ===")
    for u, v, data in g.edges(data=True):
        lbl = data.get("reason", "")
        detail = data.get("detail", "")
        extra = f" ({detail})" if detail else ""
        print(f"  {u}  --[{lbl}]-->  {v}{extra}")

    if not cycles:
        print("\n=== cycles ===")
        print("  (none - graph is a DAG: longest-stall or starvation, not deadlock)")
        return
    print(f"\n=== cycles ({len(cycles)}) ===")
    for i, c in enumerate(cycles, 1):
        tag = classify_cycle(c, g)
        print(f"  cycle #{i}  [{tag}]  len={len(c)}")
        path = c + [c[0]]
        for j in range(len(path) - 1):
            u, v = path[j], path[j + 1]
            reasons = sorted({d.get("reason", "") for _, d in (g.get_edge_data(u, v) or {}).items()})
            edge_txt = ",".join(reasons) if reasons else "?"
            u_lbl = g.nodes[u].get("label", u)
            print(f"    {u_lbl}")
            print(f"         --[{edge_txt}]-->")
        print(f"    {g.nodes[c[0]].get('label', c[0])}  (back to start)")


# ---------------------------------------------------------------------------
# CLI -------------------------------------------------------------------------
# ---------------------------------------------------------------------------

DEFAULT_FST = "/Users/ALIENWARE/Research/XiangShan/CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v2/XiangShan-CoupledL2-deadlock-v2.fst"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fst", default=DEFAULT_FST, help="path to the FST waveform")
    ap.add_argument("--dot", default="deadlock_graph_l2.dot", help="where to write the .dot file")
    ap.add_argument("--png", default=None,
                    help="optional: render the graph to this PNG via `dot`")
    args = ap.parse_args()

    if not os.path.exists(args.fst):
        sys.exit(f"[fatal] no such FST: {args.fst}")

    print(f"[info] replaying {args.fst}")
    pool, end_time, _traces = replay_fst(args.fst)
    print(f"[info] simulation end-time = {end_time}; caches found = "
          f"{sorted(pool.keys())}; total MSHRs = {sum(len(c.mshrs) for c in pool.values())}")

    g, stalled = build_wait_graph(pool)
    print(f"[info] stalled MSHRs: {len(stalled)} / {sum(len(c.mshrs) for c in pool.values())}")

    cycles = find_cycles(g)
    print_text_report(g, stalled, cycles)
    emit_dot(g, cycles, args.dot)
    print(f"\n[info] wrote {args.dot}")
    if args.png:
        import subprocess
        try:
            subprocess.run(["dot", "-Tpng", args.dot, "-o", args.png], check=True)
            print(f"[info] rendered {args.png}")
        except (subprocess.SubprocessError, FileNotFoundError) as err:
            print(f"[warn] graphviz `dot` not available: {err}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
