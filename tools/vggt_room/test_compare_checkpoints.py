#!/usr/bin/env python3
"""
Offline check of the MapAnything checkpoint comparison in vggt_room.py, with no model weights.

Builds a synthetic box room (15' 0" x 12' 0", 8' 0" ceiling) seen from 8 phone positions, then stands in for the
models: "VGGT" returns the room in arbitrary units, and two fake "MapAnything" checkpoints return it in metres, one
exact and one 3% too large. It runs vggt_room.main() end to end and checks that the report finds the 3% gap and the
tape errors. This tests the plumbing and arithmetic only; it says nothing about the real checkpoints' accuracy.

    python tools/vggt_room/test_compare_checkpoints.py
"""
import json
import math
import os
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vggt_room  # noqa: E402

FT = 0.3048
ROOM_X, ROOM_Y, ROOM_Z = 15 * FT, 12 * FT, 8 * FT  # metres
X0, Y0 = -2.1, -1.7  # room corner, so the phone is off-centre
S, H, W = 8, 96, 72  # frames, image height, width (portrait)
FX = FY = (H / 2) / math.tan(math.radians(45))  # 90 degree vertical field of view


def synthetic_room(seed=1):
    rng = np.random.default_rng(seed)
    lo = np.array([X0, Y0, 0.0])
    hi = lo + [ROOM_X, ROOM_Y, ROOM_Z]
    vv, uu = np.mgrid[0:H, 0:W].astype(np.float64)
    cam_dirs = np.stack([(uu + 0.5 - W / 2) / FX, (vv + 0.5 - H / 2) / FY, np.ones_like(uu)], -1)  # z = 1
    pts, depth, extr = [], [], []
    for i in range(S):
        th = 2 * math.pi * i / S
        c = np.array([0.15 * math.cos(th), 0.15 * math.sin(th), 1.3])
        f = np.array([math.cos(th), math.sin(th), 0.0])
        d = np.array([0.0, 0.0, -1.0])
        r = np.cross(d, f)
        c2w = np.stack([r, d, f], 1)  # OpenCV camera: x right, y down, z forward
        dirs = cam_dirs @ c2w.T
        with np.errstate(divide="ignore", invalid="ignore"):
            t1 = (lo - c) / dirs
            t2 = (hi - c) / dirs
        t = np.where(dirs > 0, t2, np.where(dirs < 0, t1, np.inf)).min(-1)  # leave the box from inside
        p = c + t[..., None] * dirs + rng.normal(0, 0.002, (H, W, 3))
        R = c2w.T
        pts.append(p)
        depth.append(t)
        extr.append(np.concatenate([R, (-R @ c)[:, None]], 1))
    cols = np.full((S, H, W, 3), 0.5)
    return np.stack(pts), np.stack(depth), np.stack(extr), cols


def fake_models(units_per_metre=1 / 3.1, checkpoint_scale=None):
    pts, depth, extr, cols = synthetic_room()
    rng = np.random.default_rng(2)
    conf = 1 + rng.random((S, H, W))

    def run_vggt(paths, weights_dir, long_side=518):
        e = extr.copy()
        e[:, :, 3] *= units_per_metre
        intr = np.tile(np.array([[FX, 0, W / 2], [0, FY, H / 2], [0, 0, 1.0]]), (S, 1, 1))
        return pts * units_per_metre, conf, cols, e, intr, depth * units_per_metre

    def run_mapanything(paths, weights_dir):
        k = checkpoint_scale[os.path.basename(weights_dir)]
        e = extr.copy()
        e[:, :, 3] *= k
        return pts * k, conf, cols, e, depth * k

    return run_vggt, run_mapanything


def main():
    tmp = tempfile.mkdtemp(prefix="vggt_room_test_")
    frames = os.path.join(tmp, "room")
    os.makedirs(frames)
    for i in range(S):
        open(os.path.join(frames, f"{i:02d}.jpg"), "wb").close()  # names only; the fake models ignore pixels
    for n in ("ma_nc", "ma_apache"):
        os.makedirs(os.path.join(tmp, n))
    run_vggt, run_ma = fake_models(checkpoint_scale={"ma_nc": 1.0, "ma_apache": 1.03})
    vggt_room.run_vggt = run_vggt
    vggt_room.run_mapanything = run_ma
    out = os.path.join(tmp, "out")
    sys.argv = ["vggt_room.py", frames, "--out", out, "--tape", "15 0",
                "--mapanything", f"nc={tmp}/ma_nc", "--mapanything", f"apache={tmp}/ma_apache"]
    vggt_room.main()
    rep = json.load(open(os.path.join(out, "report.json")))
    cmp_ = rep["checkpoint_comparison"]
    nc, ap = cmp_["checkpoints"]
    fails = []

    def check(name, got, want, tol):
        ok = got is not None and abs(got - want) <= tol
        print(f"{'ok  ' if ok else 'FAIL'} {name}: {got if got is None else round(got, 3)} (want {want} +/- {tol})")
        if not ok:
            fails.append(name)

    # true room through the "exact" checkpoint (2 inches on 15 ft is what the wall finder's bins allow)
    check("nc VGGT-shape longest wall vs tape, inches", nc["vggt_shape_longest_wall_vs_tape"]["error_inches"], 0, 2)
    check("nc own-room longest wall vs tape, inches", nc["own_room_longest_wall_vs_tape"]["error_inches"], 0, 2)
    check("nc ceiling height, m", nc["vggt_shape_m"].get("ceiling_height"), ROOM_Z, 0.05)
    # the 3% checkpoint
    for k in ("wall_a", "wall_b", "longest_wall"):
        check(f"apache VGGT-shape {k} % vs nc", ap["vggt_shape_pct_vs_nc"].get(k), 3.0, 0.1)
        check(f"apache own-room {k} % vs nc", ap["own_room_pct_vs_nc"].get(k), 3.0, 1.0)
    check("apache VGGT-shape longest wall vs tape, %", ap["vggt_shape_longest_wall_vs_tape"]["error_pct"], 3.0, 1.2)
    check("apache metres per VGGT unit", ap["metres_per_vggt_unit"], 3.1 * 1.03, 0.01)
    for f in ("plan.png", "plan_mapanything_nc.png", "plan_mapanything_apache.png"):
        if not os.path.isfile(os.path.join(out, f)):
            fails.append(f)
            print("FAIL missing", f)
    # single checkpoint keeps the old report layout
    sys.argv = ["vggt_room.py", frames, "--out", out + "_one", "--mapanything", f"{tmp}/ma_nc"]
    vggt_room.main()
    one = json.load(open(os.path.join(out + "_one", "report.json")))
    if "checkpoint_comparison" in one or "from_mapanything" not in one["in_feet"] or "in_feet" not in one["mapanything_only"]:
        fails.append("single-checkpoint layout")
        print("FAIL single-checkpoint layout changed")
    else:
        print("ok   single-checkpoint layout unchanged")
    print("\nFAILED: " + ", ".join(fails) if fails else "\nall checks passed")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
