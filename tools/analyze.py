"""Reads a report's files critically: what broke, what stopped it, where the time went, what to fix first.

Used by reports.py (analyze, trends). Works on a report folder from sync: the run log (.jsonl, .jsonl.gz or the
-end.jsonl tail), debug.txt, game-log.txt and crash-report.txt. Every finding gets a level, worst first:
BUG (an error, an exception from the add-on), STOP (it stopped by itself), QUALITY (missed clicks, wrong or given-up
blocks), SPEED (time not spent placing), INFO. The numbers go into analysis.json for the trends over versions.
"""
import collections
import glob
import gzip
import json
import os
import re

LEVELS = ["BUG", "STOP", "QUALITY", "SPEED", "INFO"]
# Build log kinds that mean something went wrong or got in the way
TROUBLE = {"ERROR", "WARN", "STUCK", "STUCK-SUPPRESSED", "STALL", "DEADEND", "WATCHDOG", "GIVEUP", "SAFETY", "GUARD",
           "LAG", "DISCONNECT", "RECONNECT", "FARMLAND-REVERTED", "NOCHANGE", "WRONG", "CLICK-SKIP", "BREAK-SKIP", "SKIP",
           "CONFLICT", "IGNORED"}


def _num(value, default=0.0):
    try:
        return float(str(value).rstrip("s/").replace(",", ""))
    except ValueError:
        return default


def _normal(text):
    """The same message with other numbers and spots counts once."""
    text = re.sub(r"-?\d+(\.\d+)?", "#", text)
    return re.sub(r"(# ?){2,}", "# ", text)[:160]


def _lines(folder):
    for path in sorted(glob.glob(os.path.join(folder, "*.jsonl*"))):
        opener = gzip.open if path.endswith(".gz") else open
        with opener(path, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith("{"):
                    try:
                        yield json.loads(line)
                    except json.JSONDecodeError:
                        continue


def _times(text):
    """"placing=12.8s lane-flying=3.4s" -> {"placing": 12.8, ...}"""
    return {k: _num(v) for k, v in re.findall(r"([a-z-]+)=([\d.]+)s", text or "")}


def version_key(version):
    """0.17.10 after 0.17.9; "?" first."""
    return [int(p) if p.isdigit() else 0 for p in re.split(r"[.+-]", version or "0")]


def analyze(folder, kind=None):
    """kind: the report's (see reports.py), when known: C means the game closed or crashed during the run."""
    found = []

    def add(level, text):
        found.append((level, text))

    types = collections.Counter()
    trouble = collections.defaultdict(collections.Counter)
    example = {}
    errors = []
    beats = []
    end = advice = start = None
    layers = 0
    hotbar = {}
    for d in _lines(folder):
        kind = d.get("type", "?")
        types[kind] += 1
        if kind == "RUN-START":
            start = d
        elif kind == "RUN-END":
            end = d
        elif kind == "RUN-ADVICE":
            advice = d.get("top3")
        elif kind == "BEAT":
            beats.append(d)
        elif kind in ("LAYER-DONE", "LAYER-END"):
            layers += kind == "LAYER-DONE"
        elif kind == "HOTBAR-MIN":
            for k, v in d.items():
                if isinstance(v, (int, float)) and k not in ("t",):
                    hotbar[k] = hotbar.get(k, 0) + v
        if kind == "ERROR":
            errors.append(d.get("text", ""))
        if kind in TROUBLE:
            text = d.get("text", "") or json.dumps({k: v for k, v in d.items() if k not in ("run", "v", "t", "type", "time")})
            key = _normal(text)
            trouble[kind][key] += 1
            example.setdefault((kind, key), text)

    m = {"version": (start or end or {}).get("version") or (start or end or {}).get("v", "?"), "server": (start or {}).get("server", "?"),
         "lines": sum(types.values())}
    if end:
        m.update(why=end.get("why", "?"), seconds=_num(end.get("time")), placed=int(_num(end.get("placed"))),
                 perMin=_num(end.get("placedPerMin")), clicks=int(_num(end.get("clicks"))), missed=int(_num(end.get("missedClicks"))),
                 wrong=int(_num(end.get("wrongBlocks"))), givenUp=int(_num(end.get("givenUp"))), trips=int(_num(end.get("trips"))),
                 purchases=int(_num(end.get("purchases"))), spent=_num(end.get("spent")), times=_times(end.get("times")))
    elif kind == "C":
        add("STOP", "The run never ended: the game crashed, froze or was killed during it (see crash-report.txt if there is one).")
    else:
        add("INFO", "No RUN-END: the log was sent while the run was going, or it's only the end of a big log.")
    m["layersDone"] = layers

    # BUG: the add-on's own errors, then exceptions in the game log
    first = {}
    for e in errors:
        first.setdefault(_normal(e), e)
    for key, n in collections.Counter(_normal(e) for e in errors).most_common(5):
        add("BUG", f"{n}x error in the add-on: {first[key][:400]}")
    game = os.path.join(folder, "game-log.txt")
    exceptions = collections.Counter()
    if os.path.exists(game):
        lines = open(game, encoding="utf-8", errors="replace").read().split("\n")
        for i, line in enumerate(lines):
            if re.search(r"(Exception|Error)(:|$)", line) and ("printeraddon" in line.lower() or any(
                    "printeraddon" in l.lower() for l in lines[i + 1:i + 8])):
                exceptions[_normal(line.split("]: ")[-1])] += 1
        for text, n in exceptions.most_common(5):
            add("BUG", f"{n}x exception from the add-on in the game log: {text}")
    if os.path.exists(os.path.join(folder, "crash-report.txt")):
        crash = open(os.path.join(folder, "crash-report.txt"), encoding="utf-8", errors="replace").read()
        head = re.search(r"Description: (.+)", crash)
        add("BUG" if "printeraddon" in crash.lower() else "INFO",
            "Crash report" + (" naming the add-on" if "printeraddon" in crash.lower() else "") + ": " + (head.group(1) if head else "?"))
    m["errors"] = len(errors) + sum(exceptions.values())

    # STOP: why it ended (a disconnect is one too: kicked, timed out; closing the game or a stop from Discord isn't)
    why = m.get("why", "")
    if why and why not in ("finished", "done", "stopped-by-you", "stopped", "disconnected", "stopped from Discord", "paused from Discord",
                           "could-not-start") and "the game was closed" not in why:
        add("STOP", f"Disconnected: {why[len('disconnected: '):]}" if why.startswith("disconnected: ") else f"Stopped by itself: {why}")

    # QUALITY: clicks the server refused, blocks wrong or given up
    if end:
        if m["clicks"] and m["missed"] / m["clicks"] > 0.03:
            add("QUALITY", f"{m['missed']} of {m['clicks']} clicks missed ({100 * m['missed'] / m['clicks']:.1f}%): {end.get('missCauses', '')}"[:300])
        if m["givenUp"]:
            add("QUALITY", f"{m['givenUp']} blocks given up: {end.get('givenUpBy', '')}"[:300])
        if m["wrong"]:
            add("QUALITY", f"{m['wrong']} wrong blocks placed")
    for kind in ("GIVEUP", "NOCHANGE", "FARMLAND-REVERTED", "WRONG", "STUCK", "DEADEND", "WATCHDOG", "STALL", "SAFETY", "GUARD", "WARN"):
        for key, n in trouble.get(kind, collections.Counter()).most_common(2):
            if n >= 3 or kind in ("STUCK", "DEADEND", "WATCHDOG", "STALL", "SAFETY"):
                add("QUALITY" if kind in ("GIVEUP", "NOCHANGE", "FARMLAND-REVERTED", "WRONG") else "SPEED",
                    f"{n}x {kind}, e.g. {example[(kind, key)][:220]}")

    # SPEED: where the time went, stalls with nothing placed
    if end and m["seconds"] > 0:
        total = sum(m["times"].values()) or m["seconds"]
        placing = m["times"].get("placing", 0) / total if total else 0
        m["placingShare"] = round(placing, 3)
        slow = sorted(((v / total, k) for k, v in m["times"].items() if k != "placing"), reverse=True)[:3]
        if placing < 0.6:
            add("SPEED", f"Only {placing:.0%} of the time placing; the rest: " + ", ".join(f"{k} {s:.0%}" for s, k in slow))
        if m["placed"] and m["trips"] * 1000 / m["placed"] > 3:
            add("SPEED", f"{m['trips']} trips for {m['placed']} blocks ({m['trips'] * 1000 / m['placed']:.1f} per 1000)")
    stall, longest, run_len, stall_what = 0, 0, 0, collections.Counter()
    for b in beats:
        if _num(b.get("placed10s")) == 0:
            run_len += 10
            stall += 10
            stall_what[str(b.get("activity", "?")) + ("/" + b["pause"] if b.get("pause") not in (None, "-") else "")] += 1
            longest = max(longest, run_len)
        else:
            run_len = 0
    m["stallSeconds"] = stall
    if beats and stall / (10 * len(beats)) > 0.15:
        add("SPEED", f"{stall}s with nothing placed ({stall / (10 * len(beats)):.0%}), longest {longest}s; doing: "
            + ", ".join(f"{k} {n * 10}s" for k, n in stall_what.most_common(3)))
    if hotbar.get("switchWaits"):
        add("INFO", f"{int(hotbar['switchWaits'])} clicks waited a tick after an item switch")
    if advice:
        add("INFO", f"The run's own advice: {advice}"[:300])

    debug = os.path.join(folder, "debug.txt")
    if os.path.exists(debug):
        changed = [l.strip()[2:] for l in open(debug, encoding="utf-8", errors="replace") if l.startswith("* ")]
        m["changedSettings"] = changed
        if changed:
            add("INFO", f"{len(changed)} settings changed from the defaults: " + "; ".join(changed[:8]) + (" ..." if len(changed) > 8 else ""))
    m["findings"] = [{"level": l, "text": t} for l, t in sorted(found, key=lambda f: LEVELS.index(f[0]))]
    json.dump(m, open(os.path.join(folder, "analysis.json"), "w"), indent=1)
    return m


def describe(m):
    out = [f"v{m.get('version', '?')} on {m.get('server', '?')}: " + (
        f"{m['placed']} placed in {m['seconds'] / 60:.1f} min ({m['perMin']:.0f}/min), ended: {m.get('why', '?')}" if "placed" in m else "no run summary")]
    for f in m["findings"]:
        out.append(f"  [{f['level']}] {f['text']}")
    return "\n".join(out)


def trends(analyses):
    """Per version: runs, hours, blocks a minute, missed clicks, stalls, errors an hour, how runs ended."""
    by = collections.defaultdict(list)
    for m in analyses:
        by[m.get("version", "?")].append(m)
    rows = []
    for version in sorted(by, key=version_key):
        runs = [m for m in by[version] if "placed" in m]
        secs = sum(m["seconds"] for m in runs) or 1
        placed = sum(m["placed"] for m in runs)
        clicks = sum(m["clicks"] for m in runs) or 1
        ends = collections.Counter(m.get("why", "?") for m in runs).most_common(3)
        rows.append(f"v{version:8} runs {len(by[version]):3}  {secs / 3600:5.1f} h  {placed * 60 / secs:6.0f}/min  "
                    f"missed {100 * sum(m['missed'] for m in runs) / clicks:4.1f}%  stalled {100 * sum(m.get('stallSeconds', 0) for m in runs) / secs:4.1f}%  "
                    f"errors/h {sum(m.get('errors', 0) for m in by[version]) * 3600 / secs:4.1f}  ended: "
                    + ", ".join(f"{w} {n}" for w, n in ends))
    return "\n".join(rows)
