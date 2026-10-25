"""The Printer Addon logo (from option 10): a white box two thirds built, the top layer still open, an arrow printing
into it, on a cyan to indigo gradient. Full bleed: Discord crops it to a circle or a rounded square."""
import math
import sys

SIZE = 1024
C30 = math.cos(math.radians(30))
S, CX, CY = 300, 512, 610
FILL = 2 / 3


def p(x, y, z):
    return (CX + (x - z) * C30 * S, CY + (x + z) * 0.5 * S - y * S)


def pts(points):
    return " ".join(f"{a:.1f},{b:.1f}" for a, b in points)


arrow_top = 150
tip_x, tip_y = p(0.5, FILL, 0.5)
tip_y -= 24
arrow = (f"M{tip_x:.1f},{arrow_top} L{tip_x:.1f},{tip_y:.1f} "
         f"M{tip_x - 92:.1f},{tip_y - 92:.1f} L{tip_x:.1f},{tip_y:.1f} L{tip_x + 92:.1f},{tip_y - 92:.1f}")
STROKE = 36
ARROW = 50
GAP = 18

hexagon = [p(0, 1, 0), p(1, 1, 0), p(1, 0, 0), p(1, 0, 1), p(0, 0, 1), p(0, 1, 1)]
left_fill = [p(0, 0, 1), p(1, 0, 1), p(1, FILL, 1), p(0, FILL, 1)]
right_fill = [p(1, 0, 0), p(1, 0, 1), p(1, FILL, 1), p(1, FILL, 0)]
surface = [p(0, FILL, 0), p(1, FILL, 0), p(1, FILL, 1), p(0, FILL, 1)]
inner = [(p(1, 0, 1), p(1, 1, 1)), (p(0, 1, 1), p(1, 1, 1)), (p(1, 1, 1), p(1, 1, 0))]

grid = "" if "--no-grid" in sys.argv else (
    '<pattern id="grid" width="64" height="64" patternUnits="userSpaceOnUse">'
    '<path d="M64 0H0V64" fill="none" stroke="#fff" stroke-opacity="0.07" stroke-width="2"/></pattern>')
out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" viewBox="0 0 {SIZE} {SIZE}">', "<defs>",
       '<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
       '<stop offset="0" stop-color="#22d3ee"/><stop offset="0.45" stop-color="#0ea5e9"/><stop offset="1" stop-color="#4f46e5"/></linearGradient>',
       '<radialGradient id="light" cx="22%" cy="16%" r="60%"><stop offset="0" stop-color="#fff" stop-opacity="0.28"/>'
       '<stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>',
       '<radialGradient id="shade" cx="50%" cy="50%" r="72%"><stop offset="0.6" stop-color="#000" stop-opacity="0"/>'
       '<stop offset="1" stop-color="#1e1b4b" stop-opacity="0.35"/></radialGradient>',
       grid,
       f'<mask id="cut"><rect width="{SIZE}" height="{SIZE}" fill="#fff"/>'
       f'<path d="{arrow}" fill="none" stroke="#000" stroke-width="{ARROW + 2 * GAP}" stroke-linecap="round" stroke-linejoin="round"/>'
       f'<polygon points="{tip_x - 92:.1f},{tip_y - 92:.1f} {tip_x:.1f},{tip_y:.1f} {tip_x + 92:.1f},{tip_y - 92:.1f}" fill="#000" '
       f'stroke="#000" stroke-width="{ARROW + 2 * GAP}" stroke-linejoin="round"/></mask>',
       '<filter id="blur" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="18"/></filter>',
       "</defs>",
       f'<rect width="{SIZE}" height="{SIZE}" fill="url(#bg)"/>',
       f'<rect width="{SIZE}" height="{SIZE}" fill="url(#grid)"/>' if grid else "",
       f'<rect width="{SIZE}" height="{SIZE}" fill="url(#light)"/>',
       f'<rect width="{SIZE}" height="{SIZE}" fill="url(#shade)"/>',
       # The box's shadow from its outline only (shadows of the lines fell on the open top as smudges)
       f'<polygon points="{pts(hexagon)}" fill="#1e1b4b" fill-opacity="0.4" stroke="#1e1b4b" stroke-opacity="0.4" '
       f'stroke-width="{STROKE}" stroke-linejoin="round" filter="url(#blur)" transform="translate(0,16)"/>',
       '<g>',
       f'<polygon points="{pts(surface)}" fill="#fff" fill-opacity="0.42"/>',
       f'<polygon points="{pts(left_fill)}" fill="#fff"/>',
       f'<polygon points="{pts(right_fill)}" fill="#e0e7ff"/>',
       '<g mask="url(#cut)">',
       f'<polygon points="{pts(hexagon)}" fill="none" stroke="#fff" stroke-width="{STROKE}" stroke-linejoin="round"/>']
for (x1, y1), (x2, y2) in inner:
    out.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="#fff" stroke-width="{STROKE}" stroke-linecap="round"/>')
out.append("</g></g>")
# The arrow without the box's shadow (it fell on the open top as smudges)
out.append(f'<path d="{arrow}" fill="none" stroke="#fff" stroke-width="{ARROW}" stroke-linecap="round" stroke-linejoin="round"/>')
out.append("</svg>")
open(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "logo.svg", "w").write("".join(out))
print("ok")
