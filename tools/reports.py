#!/usr/bin/env python3
"""The developer's view of the reports players send from the game (#player-reports on Discord).

The add-on posts each report through the channel's webhook and lists its message id in the report index (see
ReportIndex.java): a root message whose "slots:" line names a few index messages. A webhook can read and edit its own
messages by id, so with the same webhook this tool reads every report and its files, sorts them, and marks them read
or fixed: a Status field on the report in Discord, so the state lives there and is the same for every session.

Webhook: PLAYER_REPORTS_WEBHOOK, else src/main/resources/printer_addon/log-channel.txt (reversed, Base64; never in git).
Root message: its id in src/main/resources/printer_addon/report-index.txt.

  python3 tools/reports.py sync                 fetch the reports and their files into reports/ (git-ignored)
  python3 tools/reports.py list [--all]         open ones first, newest first; --all also the fixed ones
  python3 tools/reports.py show <id>            one report: its text and where its files are
  python3 tools/reports.py mark <id> read|fixed|wontfix|new [note]   its Status in Discord
  python3 tools/reports.py dashboard            update the overview message in the channel
  python3 tools/reports.py analyze [<id>]       read a report's files critically (all synced ones without an id)
  python3 tools/reports.py trends               blocks a minute, missed clicks, stalls, errors per version
  python3 tools/reports.py add <link or id> [P|S|F|U|L]   list a report sent before the index (its message link)
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
KINDS = {"P": "Problem", "S": "Stopped", "F": "Finished", "U": "By player", "L": "Log"}
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


def index(hook):
    """[(report id, kind, slot id)] from the index messages, oldest first per slot."""
    root = message(hook, root_id())["content"]
    out = []
    for line in root.split("\n"):
        if line.startswith("slots:"):
            for slot in ID.findall(line):
                for entry in message(hook, slot)["content"].split("\n"):
                    m = re.match(r"^(\d{17,20})\s+([PSFLU])\b", entry.strip())
                    if m:
                        out.append((m.group(1), m.group(2), slot))
    return out


def root_line(hook, key):
    for line in message(hook, root_id())["content"].split("\n"):
        if line.startswith(key + ":"):
            m = ID.search(line)
            return m.group() if m else None
    return None


def when(mid):
    ms = (int(mid) >> 22) + 1420070400000
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc)


def summary(msg, kind):
    """What the list shows of a report: from its embed (title, versions line, Problem line) and its Status field."""
    embed = (msg.get("embeds") or [{}])[0]
    title = embed.get("title", "")
    desc = embed.get("description", "")
    player = "?"
    for pattern in (r"^Problem from (.+?)(?::|$)", r"· ([^·]+)$", r"^Log from (.+)$"):
        m = re.search(pattern, title)
        if m:
            player = m.group(1).strip()
            break
    version = re.search(r"Printer Addon (\S+)", desc)
    status = next((f["value"] for f in embed.get("fields", []) if f.get("name") == "Status"), "🆕 New")
    return {"id": msg["id"], "kind": kind, "time": when(msg["id"]).strftime("%Y-%m-%d %H:%M"), "player": player,
            "version": version.group(1) if version else "?", "title": title, "status": status,
            "files": [a["filename"] for a in msg.get("attachments", [])]}


def folder(info):
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", info["player"])[:24]
    return os.path.join(CACHE, f"{info['time'].replace(':', '').replace(' ', '_')}_{KINDS[info['kind']]}_{safe}_{info['id']}")


def sync(hook):
    os.makedirs(CACHE, exist_ok=True)
    known = {}
    entries = index(hook)
    for mid, kind, _ in entries:
        try:
            msg = message(hook, mid)
        except RuntimeError as e:
            print(f"  {mid}: gone ({e})")
            continue
        info = summary(msg, kind)
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


def show_list(known, everything):
    rows = sorted(known.values(), key=lambda r: (done(r["status"]), r["time"]), reverse=False)
    rows = sorted(rows, key=lambda r: r["time"], reverse=True)
    rows = sorted(rows, key=lambda r: done(r["status"]))
    for r in rows:
        if done(r["status"]) and not everything:
            continue
        print(f"{r['status'][:14]:14} {r['time']}  {KINDS[r['kind']]:8} {r['player'][:16]:16} v{r['version']:8} "
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


def dashboard(hook, known):
    info = call("GET", hook)
    link = lambda mid: f"https://discord.com/channels/{info['guild_id']}/{info['channel_id']}/{mid}"
    open_reports = sorted((r for r in known.values() if not done(r["status"])), key=lambda r: r["time"], reverse=True)
    counts = {k: sum(1 for r in open_reports if r["kind"] == k) for k in KINDS}
    lines = [f"**Open:** {len(open_reports)} · **All:** {len(known)}",
             " · ".join(f"{KINDS[k]} {counts[k]}" for k in KINDS), ""]
    for r in open_reports[:15]:
        lines.append(f"{r['status'][:2]} [{r['time']}]({link(r['id'])}) **{KINDS[r['kind']]}** {r['title'][:80]}")
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
    for rid, kind, slot in index(hook):
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
    """A report from before the index (0.15.3): listed in the first slot with room."""
    mid = ID.findall(ref)[-1]
    msg = message(hook, mid)
    if kind is None:
        title = (msg.get("embeds") or [{}])[0].get("title", "")
        kind = "P" if title.startswith("Problem") else "F" if title.startswith("Finished") else "S" if title.startswith("Stopped:") else "L"
    if any(rid == mid for rid, _, _ in index(hook)):
        print(f"{mid}: listed already")
        return
    root = message(hook, root_id())["content"]
    for slot in ID.findall(next(l for l in root.split("\n") if l.startswith("slots:"))):
        text = message(hook, slot)["content"]
        if len(text) + 25 < 1950:
            call("PATCH", f"{hook}/messages/{slot}", {"content": text + f"\n{mid} {kind}", "allowed_mentions": {"parse": []}})
            print(f"{mid}: listed ({KINDS[kind]})")
            return
    print("the index is full: run setup for more slots")


def cached():
    path = os.path.join(CACHE, "reports.json")
    return json.load(open(path)) if os.path.exists(path) else {}


if __name__ == "__main__":
    args = sys.argv[1:] or ["list"]
    hook = webhook()
    if args[0] == "sync":
        show_list(sync(hook), False)
    elif args[0] == "list":
        show_list(cached() or sync(hook), "--all" in args)
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
            print(an.describe(an.analyze(os.path.join(ROOT_DIR, info["folder"]))))
    elif args[0] == "trends":
        import analyze as an
        known = cached() or sync(hook)
        print(an.trends([an.analyze(os.path.join(ROOT_DIR, info["folder"])) for info in known.values()]))
    elif args[0] == "add":
        add_old(hook, args[1], args[2] if len(args) > 2 else None)
    elif args[0] == "setup":
        setup(hook, int(args[1]) if len(args) > 1 else 8)
    elif args[0] == "forget":
        forget(hook, args[1])
    else:
        print(__doc__)
