#!/usr/bin/env python3
"""The developer's view of the reports players send from the game (#player-reports on Discord).

The add-on posts each report through the channel's webhook and lists it in the report index (see ReportIndex.java)
as "<id> <kind> <version>": a root message whose "slots:" line names the index messages (a new one is added when
they're full). A webhook can read and edit its own messages by id, so with the same webhook this tool reads every
report and its files, sorts them by version and kind, and marks them read or fixed: a Status field on the report in
Discord, so the state lives there and is the same for every session.

Kinds: P problem (the player wrote), S stopped by itself, F finished, U stopped by the player, L log from the menu,
D disconnected, C the game closed or crashed during the run.

Webhook: PLAYER_REPORTS_WEBHOOK, else src/main/resources/printer_addon/log-channel.txt (reversed, Base64; never in git).
Root message: its id in src/main/resources/printer_addon/report-index.txt.

  python3 tools/reports.py sync                 fetch the reports and their files into reports/ (git-ignored)
  python3 tools/reports.py list [--all] [--version V] [--kind K]   open ones first, newest first; --all also the fixed ones
  python3 tools/reports.py versions             how many of each kind per version
  python3 tools/reports.py show <id>            one report: its text and where its files are
  python3 tools/reports.py mark <id> read|fixed|wontfix|new [note]   its Status in Discord
  python3 tools/reports.py dashboard            update the overview message in the channel
  python3 tools/reports.py analyze [<id>]       read a report's files critically (all synced ones without an id)
  python3 tools/reports.py trends               blocks a minute, missed clicks, stalls, errors per version
  python3 tools/reports.py add <link or id> [kind]   list a report sent before the index (its message link)
  python3 tools/reports.py setup [slots]        once: the root, the index messages and the overview
  python3 tools/reports.py forget <id>          take a report out of the index and delete it (tests)
"""
import base64
import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESOURCES = os.path.join(ROOT_DIR, "src", "main", "resources", "printer_addon")
CACHE = os.path.join(ROOT_DIR, "reports")
UA = "PrinterAddon-reports (https://github.com/sindrens/litematica-printer-addon)"
ID = re.compile(r"\b\d{17,20}\b")
KINDS = {"P": "Problem", "S": "Stopped", "F": "Finished", "U": "By player", "L": "Log", "D": "Disconnected", "C": "Closed/crashed"}
ENTRY = re.compile(r"^(\d{17,20})\s+([PSFULDC])\b(?:\s+(\S+))?")
DONE_COLOR = 0x64748B


def webhook():
    url = os.environ.get("PLAYER_REPORTS_WEBHOOK", "").strip()
    if not url:
        path = os.path.join(RESOURCES, "log-channel.txt")
        if not os.path.exists(path):
            sys.exit("No webhook: set PLAYER_REPORTS_WEBHOOK or put log-channel.txt back (see the README).")
        url = base64.b64decode(open(path).read().strip()).decode()[::-1]
    return url.split("?")[0]


def root_id():
    path = os.path.join(RESOURCES, "report-index.txt")
    m = ID.search(open(path).read()) if os.path.exists(path) else None
    if not m:
        sys.exit("No report index yet: run setup.")
    return m.group()


def call(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    for _ in range(6):
        req = urllib.request.Request(url, data=data, method=method, headers={"User-Agent": UA})
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read()
                if r.headers.get("X-RateLimit-Remaining") == "0":
                    time.sleep(float(r.headers.get("X-RateLimit-Reset-After", "1")) + 0.1)
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            text = e.read()
            if e.code == 429:
                time.sleep(float(json.loads(text).get("retry_after", 1)) + 0.2)
                continue
            raise RuntimeError(f"{method} -> {e.code}: {text[:300]!r}")
    raise RuntimeError("rate limited too long")


def message(hook, mid):
    return call("GET", f"{hook}/messages/{mid}")


def slots(hook):
    """The index messages named in the root's "slots:" line."""
    for line in message(hook, root_id())["content"].split("\n"):
        if line.startswith("slots:"):
            return ID.findall(line)
    return []


def index(hook):
    """[(report id, kind, version or None, slot id)] from the index messages, oldest first."""
    out = []
    for slot in slots(hook):
        for entry in message(hook, slot)["content"].split("\n"):
            m = ENTRY.match(entry.strip())
            if m:
                out.append((m.group(1), m.group(2), m.group(3), slot))
    return sorted(out, key=lambda e: int(e[0]))


def root_line(hook, key):
    for line in message(hook, root_id())["content"].split("\n"):
        if line.startswith(key + ":"):
            m = ID.search(line)
            return m.group() if m else None
    return None


def when(mid):
    ms = (int(mid) >> 22) + 1420070400000
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc)


def summary(msg, kind, version=None):
    """What the list shows of a report: from its embed (title, versions line, Problem line) and its Status field.
    The version is the add-on's that wrote the log: from the index, else the title ("v0.17.2 · ..."), else the
    versions line (reports before 0.17.2)."""
    embed = (msg.get("embeds") or [{}])[0]
    title = embed.get("title", "")
    desc = embed.get("description", "")
    m = re.match(r"^v(\S+) · ", title)
    if m:
        version = version or m.group(1)
        title = title[m.end():]
    if not version:
        # Before 0.17.2: the log's name has the version that wrote it, the versions line the one sending it
        logs = [a["filename"] for a in msg.get("attachments", []) if ".jsonl" in a["filename"]]
        named = re.search(r"_v(\d[^_]*?)(?:-end)?\.jsonl", logs[0]) if logs else None
        old = named or re.search(r"Printer Addon (\S+)", desc)
        version = old.group(1) if old else "?"
    player = "?"
    for pattern in (r"^Problem from (.+?)(?::|$)", r"· ([^·]+)$", r"^Log from (.+)$"):
        m = re.search(pattern, title)
        if m:
            player = m.group(1).strip()
            break
    status = next((f["value"] for f in embed.get("fields", []) if f.get("name") == "Status"), "🆕 New")
    return {"id": msg["id"], "kind": kind, "time": when(msg["id"]).strftime("%Y-%m-%d %H:%M"), "player": player,
            "version": version, "title": title, "status": status,
            "files": [a["filename"] for a in msg.get("attachments", [])]}


def version_key(version):
    """0.17.10 after 0.17.9."""
    return [int(p) if p.isdigit() else 0 for p in re.split(r"[.+-]", version or "0")]


def folder(info):
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", info["player"])[:24]
    kind = re.sub(r"[^A-Za-z]+", "", KINDS[info["kind"]])
    return os.path.join(CACHE, f"v{info['version']}", f"{info['time'].replace(':', '').replace(' ', '_')}_{kind}_{safe}_{info['id']}")


def sync(hook):
    os.makedirs(CACHE, exist_ok=True)
    known = {}
    entries = index(hook)
    for mid, kind, version, _ in entries:
        try:
            msg = message(hook, mid)
        except RuntimeError as e:
            print(f"  {mid}: gone ({e})")
            continue
        info = summary(msg, kind, version)
        path = folder(info)
        os.makedirs(path, exist_ok=True)
        json.dump(msg, open(os.path.join(path, "message.json"), "w"), indent=1)
        for a in msg.get("attachments", []):
            target = os.path.join(path, a["filename"])
            if not os.path.exists(target) or os.path.getsize(target) != a.get("size", -1):
                req = urllib.request.Request(a["url"], headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=120) as r, open(target, "wb") as f:
                    f.write(r.read())
        info["folder"] = os.path.relpath(path, ROOT_DIR)
        known[mid] = info
    json.dump(known, open(os.path.join(CACHE, "reports.json"), "w"), indent=1)
    print(f"{len(known)} reports in {os.path.relpath(CACHE, ROOT_DIR)}/")
    return known


def done(status):
    return status.startswith("✅") or status.startswith("🚫")


def show_list(known, everything, version=None, kind=None):
    rows = sorted(known.values(), key=lambda r: r["time"], reverse=True)
    rows = sorted(rows, key=lambda r: done(r["status"]))
    for r in rows:
        if done(r["status"]) and not everything or version and r["version"] != version.lstrip("v") or kind and r["kind"] != kind:
            continue
        print(f"{r['status'][:14]:14} {r['time']}  {KINDS[r['kind']]:14} {r['player'][:16]:16} v{r['version']:8} "
              f"{r['title'][:70]}  ({len(r['files'])} files)  {r['id']}")


def mark(hook, mid, state, note=""):
    msg = message(hook, mid)
    today = datetime.date.today().isoformat()
    value = {"read": f"👀 Read · {today}", "fixed": f"✅ Fixed{' in ' + note if note else ''} · {today}",
             "wontfix": f"🚫 Won't fix{': ' + note if note else ''} · {today}", "new": "🆕 New"}[state]
    if note and state == "read":
        value += f" · {note}"
    embeds = []
    for i, e in enumerate(msg.get("embeds", [])):
        clean = {k: e[k] for k in ("title", "description", "color", "footer", "url") if k in e}
        clean["fields"] = [f for f in e.get("fields", []) if f.get("name") != "Status"]
        if i == 0:
            clean["fields"].append({"name": "Status", "value": value[:1024]})
            if state in ("fixed", "wontfix"):
                clean["color"] = DONE_COLOR
        if "image" in e and any(a["filename"] == "screenshot.png" for a in msg.get("attachments", [])):
            clean["image"] = {"url": "attachment://screenshot.png"}
        embeds.append(clean)
    call("PATCH", f"{hook}/messages/{mid}", {"embeds": embeds, "allowed_mentions": {"parse": []}})
    known = cached()
    if mid in known:
        known[mid]["status"] = value
        json.dump(known, open(os.path.join(CACHE, "reports.json"), "w"), indent=1)
    print(f"{mid}: {value}")


def versions(known):
    """Reports per version (newest first) and kind."""
    by = {}
    for r in known.values():
        by.setdefault(r["version"], []).append(r)
    for version in sorted(by, key=version_key, reverse=True):
        rows = by[version]
        counts = " ".join(f"{KINDS[k]} {sum(1 for r in rows if r['kind'] == k)}" for k in KINDS if any(r["kind"] == k for r in rows))
        open_count = sum(1 for r in rows if not done(r["status"]))
        print(f"v{version:9} {len(rows):4} reports, {open_count:4} open   {counts}")


def dashboard(hook, known):
    info = call("GET", hook)
    link = lambda mid: f"https://discord.com/channels/{info['guild_id']}/{info['channel_id']}/{mid}"
    open_reports = sorted((r for r in known.values() if not done(r["status"])), key=lambda r: r["time"], reverse=True)
    counts = {k: sum(1 for r in open_reports if r["kind"] == k) for k in KINDS}
    by_version = {}
    for r in open_reports:
        by_version[r["version"]] = by_version.get(r["version"], 0) + 1
    lines = [f"**Open:** {len(open_reports)} · **All:** {len(known)}",
             " · ".join(f"{KINDS[k]} {counts[k]}" for k in KINDS if counts[k]),
             "**By version:** " + " · ".join(f"v{v} {n}" for v, n in sorted(by_version.items(), key=lambda kv: version_key(kv[0]), reverse=True)), ""]
    for r in open_reports[:15]:
        lines.append(f"{r['status'][:2]} [{r['time']}]({link(r['id'])}) `v{r['version']}` **{KINDS[r['kind']]}** {r['title'][:80]}")
    if len(open_reports) > 15:
        lines.append(f"... and {len(open_reports) - 15} more")
    embed = {"title": "📊 Reports overview", "description": "\n".join(lines)[:4000], "color": 0x4F46E5,
             "footer": {"text": "Updated " + datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")}}
    mid = root_line(hook, "dashboard")
    if mid:
        call("PATCH", f"{hook}/messages/{mid}", {"embeds": [embed], "allowed_mentions": {"parse": []}})
        print("overview updated")
    else:
        print("no overview message (run setup)")


def setup(hook, slots=8):
    header = "📇 **Report index** · the add-on lists every report here. Please don't delete these messages."
    root = call("POST", hook + "?wait=true", {"content": header + "\nslots: (being made)", "allowed_mentions": {"parse": []}})
    ids = []
    for n in range(1, slots + 1):
        m = call("POST", hook + "?wait=true", {"content": f"📇 Report index · part {n} of {slots}", "allowed_mentions": {"parse": []}})
        ids.append(m["id"])
        time.sleep(0.4)
    dash = call("POST", hook + "?wait=true", {"embeds": [{"title": "📊 Reports overview", "description": "Nothing yet.", "color": 0x4F46E5}],
                                              "allowed_mentions": {"parse": []}})
    call("PATCH", f"{hook}/messages/{root['id']}", {"content": header + "\nslots: " + " ".join(ids) + "\ndashboard: " + dash["id"],
                                                   "allowed_mentions": {"parse": []}})
    open(os.path.join(RESOURCES, "report-index.txt"), "w").write(root["id"] + "\n")
    print("root", root["id"], "slots", len(ids), "overview", dash["id"])


def forget(hook, mid):
    for rid, kind, version, slot in index(hook):
        if rid == mid:
            text = message(hook, slot)["content"]
            call("PATCH", f"{hook}/messages/{slot}", {"content": "\n".join(l for l in text.split("\n") if not l.startswith(mid)),
                                                      "allowed_mentions": {"parse": []}})
    try:
        call("DELETE", f"{hook}/messages/{mid}")
    except RuntimeError as e:
        print(f"delete: {e}")
    print(f"{mid}: forgotten")


def add_old(hook, ref, kind=None):
    """A report from before the index (0.15.3): listed in the first slot with room (a new one when all are full)."""
    mid = ID.findall(ref)[-1]
    msg = message(hook, mid)
    title = (msg.get("embeds") or [{}])[0].get("title", "")
    info = summary(msg, kind or "L")
    if kind is None:
        plain = re.sub(r"^v\S+ · ", "", title)
        kind = ("P" if plain.startswith("Problem") else "F" if plain.startswith("Finished") else "U" if plain.startswith("Stopped by the player")
                else "S" if plain.startswith("Stopped:") else "D" if plain.startswith("Disconnected") else "C" if plain.startswith(("Crashed", "Game "))
                else "L")
    if any(rid == mid for rid, _, _, _ in index(hook)):
        print(f"{mid}: listed already")
        return
    entry = f"{mid} {kind} {info['version']}"
    for slot in slots(hook):
        text = message(hook, slot)["content"]
        if len(text) + len(entry) + 1 <= 1950:
            call("PATCH", f"{hook}/messages/{slot}", {"content": text + "\n" + entry, "allowed_mentions": {"parse": []}})
            print(f"{mid}: listed ({KINDS[kind]}, v{info['version']})")
            return
    new_slot(hook, entry)
    print(f"{mid}: listed in a new index message ({KINDS[kind]}, v{info['version']})")


def new_slot(hook, entry):
    """All index messages full: one more, with the entry in it, added to the root's slots line."""
    names = slots(hook)
    posted = call("POST", hook + "?wait=true", {"content": f"📇 Report index · part {len(names) + 1}\n{entry}", "allowed_mentions": {"parse": []}})
    root = message(hook, root_id())["content"]
    lines = [l + " " + posted["id"] if l.startswith("slots:") else l for l in root.split("\n")]
    call("PATCH", f"{hook}/messages/{root_id()}", {"content": "\n".join(lines), "allowed_mentions": {"parse": []}})


def cached():
    path = os.path.join(CACHE, "reports.json")
    return json.load(open(path)) if os.path.exists(path) else {}


if __name__ == "__main__":
    args = sys.argv[1:] or ["list"]
    hook = webhook()
    def option(name):
        return args[args.index(name) + 1] if name in args and args.index(name) + 1 < len(args) else None

    if args[0] == "sync":
        show_list(sync(hook), False)
    elif args[0] == "list":
        show_list(cached() or sync(hook), "--all" in args, option("--version"), option("--kind"))
    elif args[0] == "versions":
        versions(cached() or sync(hook))
    elif args[0] == "show":
        r = cached().get(args[1]) or summary(message(hook, args[1]), "L")
        msg = message(hook, args[1])
        print(json.dumps(r, indent=1))
        print((msg.get("embeds") or [{}])[0].get("description", ""))
    elif args[0] == "mark":
        mark(hook, args[1], args[2], " ".join(args[3:]))
    elif args[0] == "dashboard":
        dashboard(hook, sync(hook))
    elif args[0] == "analyze":
        import analyze as an
        known = cached() or sync(hook)
        for mid, info in sorted(known.items(), key=lambda kv: kv[1]["time"]):
            if len(args) > 1 and mid != args[1]:
                continue
            print(f"\n{info['time']} {KINDS[info['kind']]} · {info['player']} · {info['title'][:80]} · {mid}")
            print(an.describe(an.analyze(os.path.join(ROOT_DIR, info["folder"]), info["kind"])))
    elif args[0] == "trends":
        import analyze as an
        known = cached() or sync(hook)
        print(an.trends([an.analyze(os.path.join(ROOT_DIR, info["folder"]), info["kind"]) for info in known.values()]))
    elif args[0] == "add":
        add_old(hook, args[1], args[2] if len(args) > 2 else None)
    elif args[0] == "setup":
        setup(hook, int(args[1]) if len(args) > 1 else 8)
    elif args[0] == "forget":
        forget(hook, args[1])
    else:
        print(__doc__)
