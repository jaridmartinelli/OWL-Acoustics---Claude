"""Compares the trace-screen stills rendered by TraceUiTest with the approved design stills in docs/capture-trace.

Report only: it prints, per screen, how many pixels changed and where, so a reviewer (or Claude, from the run log)
can tell an intended layout edit from a broken screen. Pure Python, no extra packages."""
import glob, os, struct, sys, zlib

def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, w = 8, b"", 0
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and interlace == 0, f"unsupported PNG {depth} {ctype} {interlace}"
            bpp = 4 if ctype == 6 else 3
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    raw, stride, out, prev = zlib.decompress(idat), w * bpp, [], bytearray(w * bpp)
    for y in range(h):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b, c = prev[i], prev[i - bpp] if i >= bpp else 0
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out.append(bytes(line[j:j + 3]) for j in range(0, stride, bpp))
        out[-1] = list(out[-1]); prev = line
    return w, h, out

rows, missing = [], 0
print("## Trace screens vs approved design stills (docs/capture-trace)")
print("| screen | size | changed pixels | changed area (x0,y0)-(x1,y1) |")
print("|---|---|---|---|")
for ref in sorted(glob.glob("docs/capture-trace/[0-9][0-9]-*.png")):
    name = os.path.basename(ref)
    new = os.path.join("lab/build/trace-stills", name)
    if not os.path.exists(new):
        print(f"| {name} | | **not rendered** | |"); missing += 1; continue
    rw, rh, rp = read_png(ref); nw, nh, np_ = read_png(new)
    if (rw, rh) != (nw, nh):
        print(f"| {name} | {rw}x{rh} -> {nw}x{nh} | **size changed** | |"); continue
    n, x0, y0, x1, y1 = 0, rw, rh, -1, -1
    for y in range(rh):
        for x, (p, q) in enumerate(zip(rp[y], np_[y])):
            if max(abs(p[0] - q[0]), abs(p[1] - q[1]), abs(p[2] - q[2])) > 24:
                n += 1; x0, y0, x1, y1 = min(x0, x), min(y0, y), max(x1, x), max(y1, y)
    area = f"({x0},{y0})-({x1},{y1})" if n else "none"
    print(f"| {name} | {rw}x{rh} | {n} ({100 * n / (rw * rh):.2f}%) | {area} |")
sys.exit(1 if missing else 0)
