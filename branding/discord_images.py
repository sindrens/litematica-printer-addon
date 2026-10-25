"""The Discord image set, in the logo's style: cyan to indigo gradient, white line icons (Lucide), Inter.
Writes discord/<name>.html for each image and discord/pages.txt (name width height) for render-pages.js."""
import html
import math
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "discord")
os.makedirs(OUT, exist_ok=True)
LOGO = os.path.join(HERE, "logo.png")
C30 = math.cos(math.radians(30))


def icon(name, size=96, stroke=1.8, color="#fff"):
    text = open(os.path.join(HERE, "icons", f"{name}.svg")).read()
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r'class="[^"]*"', "", text)
    text = text.replace('width="24"', f'width="{size}"').replace('height="24"', f'height="{size}"')
    text = text.replace('stroke="currentColor"', f'stroke="{color}"').replace('stroke-width="2"', f'stroke-width="{stroke}"')
    return text


def lattice(n=3, s=120, cx=0, cy=0, opacity=0.09, width=3):
    """A faint isometric block grid, for decoration."""
    p = lambda a, b, c: (cx + (a - c) * C30 * s, cy + (a + c) * 0.5 * s - b * s)
    lines = []
    for i in range(n + 1):
        lines += [(p(i, n, 0), p(i, n, n)), (p(0, n, i), p(n, n, i)), (p(n, i, 0), p(n, i, n)),
                  (p(n, 0, i), p(n, n, i)), (p(0, i, n), p(n, i, n)), (p(i, 0, n), p(i, n, n))]
    return "".join(f'<line x1="{a:.1f}" y1="{b:.1f}" x2="{c:.1f}" y2="{d:.1f}" stroke="#fff" stroke-opacity="{opacity}" '
                   f'stroke-width="{width}" stroke-linecap="round"/>' for (a, b), (c, d) in lines)


BASE_CSS = """
@font-face { font-family: Inter; src: local('Inter'); }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body { font-family: Inter, sans-serif; color: #fff; overflow: hidden; position: relative;
  background: linear-gradient(135deg, #22d3ee 0%, #0ea5e9 42%, #4f46e5 100%); }
.grid { position: absolute; inset: 0;
  background-image: linear-gradient(rgba(255,255,255,.07) 2px, transparent 2px), linear-gradient(90deg, rgba(255,255,255,.07) 2px, transparent 2px);
  background-size: 64px 64px; }
.light { position: absolute; inset: 0; background: radial-gradient(ellipse 60% 90% at 12% 10%, rgba(255,255,255,.26), transparent 70%); }
.shade { position: absolute; inset: 0; background: radial-gradient(ellipse 90% 120% at 50% 40%, transparent 55%, rgba(30,27,75,.38)); }
.deco { position: absolute; inset: 0; }
.brand { position: absolute; top: 44px; right: 56px; display: flex; align-items: center; gap: 16px;
  font-size: 30px; font-weight: 700; letter-spacing: -0.3px; opacity: .95; }
.brand img { width: 56px; height: 56px; border-radius: 16px; box-shadow: 0 6px 18px rgba(30,27,75,.35); }
.tile { display: flex; align-items: center; justify-content: center; border-radius: 48px;
  background: rgba(255,255,255,.16); border: 3px solid rgba(255,255,255,.38); box-shadow: 0 18px 40px rgba(30,27,75,.25); }
h1 { margin: 0; font-weight: 800; letter-spacing: -2.5px; line-height: 1.02; text-shadow: 0 4px 18px rgba(30,27,75,.25); }
.sub { font-weight: 500; color: rgba(255,255,255,.9); }
.pill { font-size: 26px; font-weight: 600; padding: 10px 24px; border-radius: 999px; background: rgba(255,255,255,.16);
  border: 2px solid rgba(255,255,255,.4); white-space: nowrap; }
.card { background: rgba(255,255,255,.13); border: 2px solid rgba(255,255,255,.3); border-radius: 28px;
  box-shadow: 0 14px 34px rgba(30,27,75,.18); }
.num { width: 64px; height: 64px; border-radius: 50%; background: #fff; color: #3730a3; font-weight: 800; font-size: 32px;
  display: flex; align-items: center; justify-content: center; flex: none; box-shadow: 0 6px 16px rgba(30,27,75,.25); }
.key { display: inline-flex; align-items: center; justify-content: center; min-width: 64px; height: 64px; padding: 0 16px;
  background: #fff; color: #1e1b4b; border-radius: 14px; font-weight: 800; font-size: 28px;
  box-shadow: 0 6px 0 #c7d2fe, 0 10px 22px rgba(30,27,75,.3); }
.plus { font-size: 34px; font-weight: 700; margin: 0 12px; opacity: .9; }
"""


def page(name, width, height, body, css=""):
    doc = (f'<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}{css}'
           f'body {{ width: {width}px; height: {height}px; }}</style></head><body>'
           '<div class="grid"></div><div class="light"></div><div class="shade"></div>'
           f"{body}</body></html>")
    open(os.path.join(OUT, f"{name}.html"), "w").write(doc)
    PAGES.append((name, width, height))


def brand():
    return f'<div class="brand"><img src="file://{LOGO}">Printer Addon</div>'


def deco(width, height, s=150, x=None, y=None):
    x = width - 330 if x is None else x
    y = height * 0.62 if y is None else y
    return f'<svg class="deco" width="{width}" height="{height}">{lattice(3, s, x, y)}</svg>'


PAGES = []


def header(name, icon_name, title, subtitle):
    w, h = 1600, 500
    body = (deco(w, h, 140, w - 300, 330) + brand()
            + f'<div class="tile" style="position:absolute;left:96px;top:150px;width:200px;height:200px">{icon(icon_name, 112, 1.7)}</div>'
            + f'<div style="position:absolute;left:350px;top:150px;right:120px">'
            + f'<h1 style="font-size:100px">{html.escape(title)}</h1>'
            + f'<div class="sub" style="font-size:38px;margin-top:20px">{html.escape(subtitle)}</div></div>')
    page(name, w, h, body)


def welcome():
    w, h = 1600, 640
    tags = "".join(f'<div class="pill">{t}</div>' for t in ["Minecraft 26.2", "Fabric", "Litematica Printer add-on"])
    feats = "".join(f'<div style="display:flex;align-items:center;gap:14px;font-size:28px;font-weight:600;white-space:nowrap">'
                    f'{icon(i, 36, 2.2)}{t}</div>' for i, t in
                    [("layers", "Layer by layer"), ("plane", "Flies for you"), ("shopping-cart", "Buys what's missing"),
                     ("pickaxe", "Auto break")])
    body = (deco(w, h, 170, w - 260, 430)
            + f'<img src="file://{LOGO}" style="position:absolute;left:96px;top:110px;width:420px;height:420px;border-radius:96px;'
              f'box-shadow:0 28px 60px rgba(30,27,75,.45);border:4px solid rgba(255,255,255,.35)">'
            + '<div style="position:absolute;left:600px;top:118px;right:80px">'
            + '<h1 style="font-size:128px">Printer Addon</h1>'
            + '<div class="sub" style="font-size:42px;margin-top:22px">Builds your Litematica schematics for you.</div>'
            + f'<div style="display:flex;gap:16px;margin-top:34px">{tags}</div>'
            + f'<div style="display:grid;grid-template-columns:auto auto;justify-content:start;gap:16px 56px;margin-top:36px">{feats}</div></div>')
    page("welcome", w, h, body)


def steps(name, title, subtitle, items, icon_name, side=""):
    """A numbered list of steps (number, icon, title, detail), optionally with a picture at the right."""
    w, h = 1600, 960
    width = 860 if side else 1400
    rows = "".join(
        f'<div class="card" style="display:flex;align-items:center;gap:28px;padding:16px 30px;margin-bottom:14px;width:{width}px">'
        f'<div class="num">{k + 1}</div>{icon(ic, 52, 2)}'
        f'<div><div style="font-size:36px;font-weight:700;letter-spacing:-.5px">{html.escape(t)}</div>'
        f'<div class="sub" style="font-size:26px;margin-top:4px">{d}</div></div></div>'
        for k, (ic, t, d) in enumerate(items))
    body = (deco(w, h, 170, w - 260, 760) + brand()
            + f'<div style="position:absolute;left:100px;top:72px;display:flex;align-items:center;gap:30px">'
              f'<div class="tile" style="width:128px;height:128px;border-radius:34px">{icon(icon_name, 72, 1.8)}</div>'
              f'<div><h1 style="font-size:76px">{html.escape(title)}</h1><div class="sub" style="font-size:32px;margin-top:8px">'
              f'{html.escape(subtitle)}</div></div></div>'
            + f'<div style="position:absolute;left:100px;top:248px">{rows}</div>' + side)
    page(name, w, h, body)


def keys(combo):
    parts = combo.split("+")
    return '<span class="plus">+</span>'.join(f'<span class="key">{html.escape(p.strip())}</span>' for p in parts)


def hotkeys():
    w, h = 1600, 960
    items = [("J + B", "Build menu", "Start, stop, report"), ("J + A", "Add-on on / off", "Off: the plain printer"),
             ("J + C", "Settings", "Every option, with search"), ("J + R", "Build report", "Holes and wrong blocks"),
             ("J + P", "Cost", "What's left, what it costs"), ("J + S", "Scan the shop", "Learns prices for buying"),
             ("J + F", "Fix wrong blocks", "Breaks them near you"), ("J + X", "Clear extra blocks", "Where it should be air"),
             ("J + PgUp", "Next layer", "PgDn: the layer below"), ("J + Q", "Stop everything", "Works in menus too")]
    cells = "".join(
        f'<div class="card" style="display:flex;align-items:center;gap:22px;padding:14px 24px">'
        f'<div style="flex:none;width:250px;display:flex;align-items:center">{keys(k)}</div>'
        f'<div style="white-space:nowrap"><div style="font-size:31px;font-weight:700;letter-spacing:-.4px">{html.escape(t)}</div>'
        f'<div class="sub" style="font-size:22px;margin-top:2px">{html.escape(d)}</div></div></div>' for k, t, d in items)
    body = (deco(w, h, 170, w - 260, 760) + brand()
            + f'<div style="position:absolute;left:100px;top:72px;display:flex;align-items:center;gap:30px">'
              f'<div class="tile" style="width:128px;height:128px;border-radius:34px">{icon("keyboard", 72, 1.8)}</div>'
              f'<div><h1 style="font-size:76px">Hotkeys</h1><div class="sub" style="font-size:32px;margin-top:8px">'
              f'Hold J, then press the second key.</div></div></div>'
            + f'<div style="position:absolute;left:100px;right:100px;top:244px;display:grid;grid-template-columns:1fr 1fr;gap:14px 24px">{cells}</div>')
    page("hotkeys", w, h, body)


def report_form():
    """A drawing of the Report / share log screen, next to the steps."""
    chips = ["Skips blocks", "Wrong blocks", "Too slow", "Gets stuck", "Flying", "Items", "Shop", "Vaults", "Tilling",
             "Auto break", "Error", "Other"]
    picked = {"Skips blocks", "Too slow"}
    chip_html = "".join(
        f'<div style="padding:8px 0;text-align:center;border-radius:8px;font-size:17px;font-weight:600;'
        f'background:{"#334155" if c in picked else "#1e293b"};color:{"#4ade80" if c in picked else "#e2e8f0"};'
        f'border:2px solid {"#4ade80" if c in picked else "#475569"}">{"✔ " if c in picked else ""}{c}</div>' for c in chips)
    return (
        '<div style="position:absolute;right:96px;top:248px;width:520px;padding:26px;border-radius:26px;background:rgba(15,23,42,.88);'
        'border:2px solid rgba(255,255,255,.25);box-shadow:0 24px 50px rgba(30,27,75,.45);font-size:20px">'
        '<div style="font-weight:700;font-size:22px;margin-bottom:12px">Report a problem / share the log</div>'
        '<div style="color:#cbd5e1;margin-bottom:8px">What went wrong?</div>'
        '<div style="height:96px;border-radius:10px;background:#020617;border:2px solid #64748b;padding:12px;color:#e2e8f0;font-size:18px;line-height:1.35">'
        'It skipped a row of pistons on layer 12 and flew back for them later.</div>'
        '<div style="color:#cbd5e1;margin:16px 0 8px">Click what fits:</div>'
        f'<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:8px">{chip_html}</div>'
        '<div style="color:#cbd5e1;margin:16px 0 8px">What were you building?</div>'
        '<div style="height:44px;border-radius:10px;background:#020617;border:2px solid #64748b;padding:9px 12px;color:#e2e8f0;font-size:18px">'
        'Pumpkin_farm, 32 x 280 x 32 blocks</div>'
        '<div style="margin-top:18px;display:flex;gap:12px">'
        '<div style="flex:1;padding:12px;text-align:center;border-radius:10px;background:#475569;color:#4ade80;font-weight:700">✉ Send to the developer</div>'
        '<div style="width:110px;padding:12px;text-align:center;border-radius:10px;background:#334155">Cancel</div></div></div>')


def release(version, date, items, since=""):
    w, h = 1600, 960
    rows = "".join(
        f'<div style="display:flex;align-items:center;gap:24px;margin-bottom:22px">'
        f'<div style="width:58px;height:58px;border-radius:50%;background:#fff;display:flex;align-items:center;justify-content:center;flex:none">'
        f'{icon(ic, 34, 2.6, "#4338ca")}</div><div style="font-size:36px;font-weight:600;letter-spacing:-.3px">{html.escape(t)}</div></div>'
        for ic, t in items)
    body = (deco(w, h, 190, w - 250, 800) + brand()
            + '<div style="position:absolute;left:100px;top:80px">'
            + '<div class="pill" style="display:inline-block;font-size:28px">New version</div>'
            + f'<h1 style="font-size:150px;margin-top:22px">v{html.escape(version)}</h1>'
            + f'<div class="sub" style="font-size:34px;margin-top:6px">Printer Addon · {html.escape(date)}{html.escape(since)}</div></div>'
            + f'<div style="position:absolute;left:100px;top:440px">{rows}</div>')
    page(f"release-{version}", w, h, body)


if __name__ == "__main__":
    welcome()
    header("getting-started", "book-open", "Getting started", "From zero to your first auto build.")
    header("changelog", "megaphone", "Changelog", "What's new in each version, short.")
    header("downloads", "download", "Downloads", "The newest Printer Addon jar for Minecraft 26.2.")
    header("help", "circle-help", "Help", "Questions about setup and settings.")
    header("bug-reports", "bug", "Bug reports", "Something wrong? Report it from the game.")
    header("showcase", "camera", "Showcase", "Show what you built with Printer Addon.")
    header("suggestions", "lightbulb", "Suggestions", "Ideas to make it better.")
    header("general", "messages-square", "General", "Talk about anything.")
    header("player-reports", "clipboard-list", "Player reports", "Logs and problem reports sent from the game.")
    steps("install-steps", "Get started", "Five steps to your first auto build.", [
        ("package", "Install Fabric", "Fabric Loader and Fabric API for Minecraft 26.2."),
        ("box", "Add the mods", "Litematica, MaLiLib, Litematica Printer and Printer Addon into <b>mods</b>."),
        ("layers", "Place your schematic", "Load it in Litematica and place it where it should be built."),
        ("mouse-pointer-click", "Press J + B, then START", "The build menu: green START, red STOP."),
        ("sparkles", "Let it build", "It flies, places layer by layer, refills from your vaults and buys what's missing.")], "rocket")
    steps("report-steps", "Report a problem", "Straight from the game, with the full log.", [
        ("keyboard", "Press J + B", "Opens the build menu."),
        ("mouse-pointer-click", "Report / share log", "The button next to START."),
        ("file-text", "Say what went wrong", "Write it, click what fits, check what you built."),
        ("camera", "Add a screenshot", "Press F2 in game first: it goes with it."),
        ("send", "Send", "The log and debug go to the developer.")], "bug", side=report_form())
    hotkeys()
    release("0.15.3", "October 25, 2026", [
        ("send", "Report / share log: say what went wrong, in game"),
        ("file-text", "Full debug with every report"),
        ("check", "Pistons place reliably after item switches"),
        ("zap", "Fewer item switches, faster building"),
        ("clipboard-list", "Clearer build report and log names"),
        ("sparkles", "New look: logo, START and STOP buttons")], "  ·  with 0.15.1 and 0.15.2")
    open(os.path.join(OUT, "pages.txt"), "w").write("\n".join(f"{n} {w} {h}" for n, w, h in PAGES))
    print(len(PAGES), "pages")
