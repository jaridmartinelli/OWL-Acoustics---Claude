#!/usr/bin/env python3
"""
Room walls from a phone video with VGGT (feasibility test, not part of the app).

Steps: pick sharp frames from the video -> VGGT (Meta, VGGT-1B-Commercial weights) predicts cameras and depth ->
3D points -> Open3D finds the floor and ceiling planes -> wall points near the ceiling (clutter rarely reaches
there) -> walls fitted at right angles in the room's own axes -> sizes in feet.

VGGT's result has no units. Two ways to put it in feet are reported side by side:
  * your tape measurement of the longest wall (--tape), and
  * an assumed 8 ft ceiling (only if both the floor and the ceiling were found).
Nothing here is a verified accuracy figure; compare it with your tape.
"""
import argparse
import json
import math
import os
import sys
import time

import cv2
import numpy as np

FT = 0.3048


def parse_feet(s: str) -> float:
    """'15 0 1/8', "15' 0 1/8\"", '15.5', '4.6m' -> metres."""
    t = s.strip().lower().replace("’", "'").replace("”", '"')
    if t.endswith("m") and not t.endswith("mm"):
        return float(t[:-1])
    t = t.replace("ft", "'").replace("in", '"')
    ft = inch = 0.0
    if "'" in t:
        a, b = t.split("'", 1)
        ft = float(a or 0)
        t = b
        parts = t.replace('"', " ").split()
    else:
        parts = t.replace('"', " ").split()
        if parts:
            ft = float(parts[0])
            parts = parts[1:]
    for p in parts:
        inch += (float(p.split("/")[0]) / float(p.split("/")[1])) if "/" in p else float(p)
    return ft * FT + inch * 0.0254


def fmt_ft(m: float) -> str:
    total = round(m / 0.0254 * 8) / 8
    ft = int(total // 12)
    inch = total - ft * 12
    whole = int(inch)
    frac = inch - whole
    fr = "" if frac < 1e-9 else " " + {0.125: "1/8", 0.25: "1/4", 0.375: "3/8", 0.5: "1/2", 0.625: "5/8", 0.75: "3/4", 0.875: "7/8"}[round(frac, 3)]
    return f"{ft}' {whole}{fr}\""


# ---------------------------------------------------------------- frames

def pick_frames(video: str, n: int, outdir: str, long_side: int = 1280):
    """Splits the video into n equal time slices and keeps the sharpest frame of each."""
    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        sys.exit(f"cannot open {video}")
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
    best = {}
    i = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if total <= 4 * n or i % 2 == 0:
            slot = min(n - 1, int(i * n / max(total, 1))) if total else i // 15
            small = cv2.resize(frame, None, fx=0.25, fy=0.25)
            sharp = cv2.Laplacian(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var()
            if slot not in best or sharp > best[slot][0]:
                best[slot] = (sharp, frame.copy())
        i += 1
    cap.release()
    os.makedirs(outdir, exist_ok=True)
    paths = []
    for slot in sorted(best):
        f = best[slot][1]
        s = long_side / max(f.shape[:2])
        if s < 1:
            f = cv2.resize(f, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        p = os.path.join(outdir, f"frame_{slot:03d}.jpg")
        cv2.imwrite(p, f, [cv2.IMWRITE_JPEG_QUALITY, 92])
        paths.append(p)
    return paths, i


# ---------------------------------------------------------------- VGGT

def run_vggt(paths, weights_dir: str, long_side: int = 518):
    import torch
    from PIL import Image
    from vggt.models.vggt import VGGT
    from vggt.utils.geometry import unproject_depth_map_to_point_map
    from vggt.utils.pose_enc import pose_encoding_to_extri_intri

    # same size for every frame, long side 518, short side a multiple of 14, no cropping (keeps floor and ceiling)
    w0, h0 = Image.open(paths[0]).size
    if w0 >= h0:
        W, H = long_side, max(14, round(long_side * h0 / w0 / 14) * 14)
    else:
        H, W = long_side, max(14, round(long_side * w0 / h0 / 14) * 14)
    imgs = []
    for p in paths:
        im = Image.open(p).convert("RGB").resize((W, H), Image.BICUBIC)
        imgs.append(torch.from_numpy(np.array(im)).permute(2, 0, 1).float() / 255.0)
    images = torch.stack(imgs)[None]  # [1, S, 3, H, W]

    torch.set_num_threads(os.cpu_count() or 4)
    if not os.path.isfile(os.path.join(weights_dir, "model.safetensors")):
        sys.exit(f"VGGT weights not found in {weights_dir} (need config.json + model.safetensors)")
    model = VGGT.from_pretrained(weights_dir).eval()
    with torch.no_grad():
        tokens, ps_idx = model.aggregator(images)
        pose_enc = model.camera_head(tokens)[-1]
        extr, intr = pose_encoding_to_extri_intri(pose_enc, images.shape[-2:])
        depth, conf = model.depth_head(tokens, images, ps_idx)
    extr = extr.squeeze(0).numpy()
    intr = intr.squeeze(0).numpy()
    depth = depth.squeeze(0).numpy()
    conf = conf.squeeze(0).numpy()
    pts = unproject_depth_map_to_point_map(depth, extr, intr)  # [S, H, W, 3]
    cols = images.squeeze(0).permute(0, 2, 3, 1).numpy()
    return pts, conf, cols, extr, intr, depth[..., 0]


# ---------------------------------------------------------------- MapAnything (real-world size)

def run_mapanything(paths, weights_dir: str):
    """Meta's MapAnything on the same frames. Its output is in metres; returns points, confidence, colours,
    world-to-camera matrices and depth, in the same layout as run_vggt."""
    import torch
    from mapanything.models import MapAnything
    from mapanything.utils.image import load_images

    if not os.path.isfile(os.path.join(weights_dir, "model.safetensors")):
        sys.exit(f"MapAnything weights not found in {weights_dir}")
    torch.set_num_threads(os.cpu_count() or 4)
    # MapAnything builds its image encoder from DINOv2's code via torch.hub. If that code is already in the hub cache
    # (git clone facebookresearch/dinov2 -> ~/.cache/torch/hub/facebookresearch_dinov2_main), skip the online check.
    if os.path.isdir(os.path.join(torch.hub.get_dir(), "facebookresearch_dinov2_main")):
        torch.hub._parse_repo_info = lambda github: (*github.split(":")[0].split("/"), "main")
        torch.hub._validate_not_a_forked_repo = lambda *a, **k: None
    model = MapAnything.from_pretrained(weights_dir).eval()
    views = load_images(list(paths))
    with torch.no_grad():
        preds = model.infer(views, memory_efficient_inference=True, use_amp=False, apply_mask=True, mask_edges=True)
    pts, conf, cols, extr, depth = [], [], [], [], []
    for p in preds:
        pts.append(p["pts3d"][0].numpy())
        m = p["mask"][0, ..., 0].numpy().astype(bool)
        c = p["conf"][0].numpy().copy()
        c[~m] = 0
        conf.append(c)
        cols.append(p["img_no_norm"][0].numpy())
        c2w = p["camera_poses"][0].numpy()
        R = c2w[:3, :3].T
        extr.append(np.concatenate([R, (-R @ c2w[:3, 3])[:, None]], axis=1))
        depth.append(p["depth_z"][0, ..., 0].numpy())
    return np.stack(pts), np.stack(conf), np.stack(cols), np.stack(extr), np.stack(depth)


def depth_scale(d_shape, c_shape, d_metric, c_metric):
    """Metres per VGGT unit: median ratio of the two depth maps over pixels both are confident about."""
    ratios = []
    for i in range(len(d_shape)):
        a = d_shape[i]
        b = cv2.resize(d_metric[i], (a.shape[1], a.shape[0]), interpolation=cv2.INTER_NEAREST)
        cb = cv2.resize(c_metric[i], (a.shape[1], a.shape[0]), interpolation=cv2.INTER_NEAREST)
        ok = (a > 1e-6) & (b > 1e-6) & (c_shape[i] > np.quantile(c_shape[i], 0.5)) & (cb > np.quantile(cb[cb > 0], 0.5) if (cb > 0).any() else False)
        if ok.sum() > 100:
            ratios.append(b[ok] / a[ok])
    if not ratios:
        return None
    r = np.concatenate(ratios)
    return float(np.median(r)), float(np.quantile(r, 0.75) / np.quantile(r, 0.25))


# ---------------------------------------------------------------- geometry

def unit(v):
    return v / (np.linalg.norm(v) + 1e-12)


def room_from_points(pts, conf, cols, extr, keep_frac=0.55, seed=0):
    import open3d as o3d

    o3d.utility.random.seed(seed)  # repeatable plane fitting (RANSAC)
    S = pts.shape[0]
    # keep the more confident half of the pixels
    thr = np.quantile(conf, 1 - keep_frac)
    m = conf > thr
    P = pts[m].reshape(-1, 3).astype(np.float64)
    C = cols[m].reshape(-1, 3).astype(np.float64)
    R = extr[:, :, :3]
    t = extr[:, :, 3]
    cams = np.stack([-R[i].T @ t[i] for i in range(S)])
    cam_c = cams.mean(0)
    scale = float(np.median(np.linalg.norm(P - cam_c, axis=1)))
    # phone upright: image "down" is gravity down
    up = -unit(np.mean([R[i].T @ np.array([0.0, 1.0, 0.0]) for i in range(S)], axis=0))

    pcd = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(P))
    pcd.colors = o3d.utility.Vector3dVector(C)
    pcd = pcd.voxel_down_sample(0.004 * scale)
    pcd, _ = pcd.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.5)
    Q = np.asarray(pcd.points)
    n_all = len(Q)

    # horizontal planes (floor, ceiling, bed tops, ...)
    planes = []
    rest = pcd
    for _ in range(10):
        if len(rest.points) < 0.03 * n_all:
            break
        model, inl = rest.segment_plane(distance_threshold=0.008 * scale, ransac_n=3, num_iterations=3000)
        n = unit(np.array(model[:3]))
        pts_in = np.asarray(rest.points)[inl]
        if abs(n @ up) > 0.95 and len(inl) > 0.015 * n_all:
            if n @ up < 0:
                n = -n
            planes.append({"normal": n, "height": float(np.median(pts_in @ n)), "count": len(inl)})
        rest = rest.select_by_index(inl, invert=True)
    cam_h = float(np.median(cams @ up))
    below = [p for p in planes if p["height"] < cam_h - 0.05 * scale]
    above = [p for p in planes if p["height"] > cam_h + 0.05 * scale]
    floor = min(below, key=lambda p: p["height"]) if below else None
    ceiling = max(above, key=lambda p: p["count"]) if above else None
    ref = floor or ceiling
    if ref is not None:
        up = ref["normal"]
        cam_h = float(np.median(cams @ up))
    hf = floor["height"] if floor else None
    hc = ceiling["height"] if ceiling else None

    # horizontal axes
    e1 = unit(np.cross(up, [1.0, 0, 0] if abs(up[0]) < 0.9 else [0, 1.0, 0]))
    e2 = np.cross(up, e1)
    h = Q @ up
    if hc is not None:
        H = (hc - hf) if hf is not None else (hc - cam_h) * 2
        band = (h > hc - 0.30 * H) & (h < hc - 0.025 * H)
    elif hf is not None:
        # no ceiling in view: use wall above most furniture (beds, desks, dressers), up to a bit over the phone
        ch = cam_h - hf
        band = (h > hf + 0.75 * ch) & (h < hf + 1.6 * ch)
    else:
        band = (h > cam_h - 0.1 * scale) & (h < cam_h + 0.4 * scale)
    wall = pcd.select_by_index(np.where(band)[0])
    wall.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=0.03 * scale, max_nn=30))
    N = np.asarray(wall.normals)
    W = np.asarray(wall.points)
    vert = np.abs(N @ up) < 0.25
    W, N = W[vert], N[vert]
    if len(W) < 200:
        return {"error": "too few wall points near the ceiling", "planes": planes}, None
    u, v = W @ e1, W @ e2
    nu, nv = N @ e1, N @ e2
    a = np.arctan2(nv, nu)
    wgt = np.hypot(nu, nv)
    phi = math.atan2(np.sum(wgt * np.sin(4 * a)), np.sum(wgt * np.cos(4 * a))) / 4
    cphi, sphi = math.cos(phi), math.sin(phi)
    x = cphi * u + sphi * v
    y = -sphi * u + cphi * v
    nx = cphi * nu + sphi * nv
    ny = -sphi * nu + cphi * nv
    cx = cphi * (cam_c @ e1) + sphi * (cam_c @ e2)
    cy = -sphi * (cam_c @ e1) + cphi * (cam_c @ e2)

    def peaks(coord, mask, centre):
        vals = coord[mask]
        if len(vals) < 50:
            return None, None, []
        b = 0.01 * scale
        lo, hi = vals.min() - 3 * b, vals.max() + 3 * b
        hist, edges = np.histogram(vals, bins=max(10, int((hi - lo) / b)), range=(lo, hi))
        hist = np.convolve(hist, [1, 2, 3, 2, 1], mode="same") / 9.0
        mids = (edges[:-1] + edges[1:]) / 2
        pk = [i for i in range(1, len(hist) - 1) if hist[i] >= hist[i - 1] and hist[i] > hist[i + 1] and hist[i] > 0]
        left = [i for i in pk if mids[i] < centre]
        right = [i for i in pk if mids[i] > centre]
        L = max(left, key=lambda i: hist[i]) if left else None
        Rr = max(right, key=lambda i: hist[i]) if right else None
        top = max([hist[i] for i in pk] or [1])
        others = [float(mids[i]) for i in pk if hist[i] > 0.2 * top and i not in (L, Rr)]
        return (float(mids[L]) if L is not None else None), (float(mids[Rr]) if Rr is not None else None), others

    xl, xr, xo = peaks(x, np.abs(nx) > 0.85, cx)
    yb, yt, yo = peaks(y, np.abs(ny) > 0.85, cy)
    res = {
        "frames": int(S), "points": int(n_all), "scale_units": scale,
        "floor_found": floor is not None, "ceiling_found": ceiling is not None,
        "ceiling_minus_floor": (hc - hf) if (hc is not None and hf is not None) else None,
        "camera_above_floor": (cam_h - hf) if hf is not None else None,
        "horizontal_planes_rel_camera": [round(p["height"] - cam_h, 4) for p in planes],
        "walls_x": [xl, xr], "walls_y": [yb, yt], "other_wall_lines_x": xo, "other_wall_lines_y": yo,
        "length_x": (xr - xl) if (xl is not None and xr is not None) else None,
        "length_y": (yt - yb) if (yb is not None and yt is not None) else None,
    }
    draw = {"P": Q, "up": up, "e1": e1, "e2": e2, "phi": phi, "x": x, "y": y, "cams": cams, "res": res}
    return res, draw


def scaled(res, tape_m, metres_per_unit=None, note=""):
    out = {}
    lx, ly = res.get("length_x"), res.get("length_y")
    H = res.get("ceiling_minus_floor")
    cam = res.get("camera_above_floor")

    def block(k, basis):
        b = {"basis": basis}
        if lx: b["wall_a"] = fmt_ft(lx * k)
        if ly: b["wall_b"] = fmt_ft(ly * k)
        if H: b["ceiling_height"] = fmt_ft(H * k)
        if cam: b["phone_height"] = fmt_ft(cam * k)
        return b

    if tape_m and lx and ly:
        out["from_tape"] = block(tape_m / max(lx, ly), f"longest wall = your tape {fmt_ft(tape_m)}")
    if H:
        out["from_8ft_ceiling"] = block(8 * FT / H, "ceiling assumed 8' 0\"")
    if metres_per_unit:
        out["from_mapanything"] = block(metres_per_unit, "real-world size from MapAnything" + note)
    return out


def draw_plan(draw, path, k=None):
    """Top-down view: grey = everything, dark = wall points near the ceiling, cyan = fitted walls, orange = phone path."""
    res = draw["res"]
    up, e1, e2, phi = draw["up"], draw["e1"], draw["e2"], draw["phi"]
    P = draw["P"][:: max(1, len(draw["P"]) // 60000)]
    cphi, sphi = math.cos(phi), math.sin(phi)

    def rot(Z):
        u, v = Z @ e1, Z @ e2
        return cphi * u + sphi * v, -sphi * u + cphi * v

    ax, ay = rot(P)
    wx, wy = draw["x"], draw["y"]
    cxs, cys = rot(draw["cams"])
    xs = np.concatenate([wx, cxs]); ys = np.concatenate([wy, cys])
    lo_x, hi_x = np.quantile(xs, 0.01), np.quantile(xs, 0.99)
    lo_y, hi_y = np.quantile(ys, 0.01), np.quantile(ys, 0.99)
    span = max(hi_x - lo_x, hi_y - lo_y) * 1.25
    mx, my = (lo_x + hi_x) / 2, (lo_y + hi_y) / 2
    S = 900
    img = np.full((S, S, 3), 255, np.uint8)

    def px(x, y):
        return (int((x - mx) / span * S + S / 2), int(S / 2 - (y - my) / span * S))

    for x, y in zip(ax, ay):
        p = px(x, y)
        if 0 <= p[0] < S and 0 <= p[1] < S:
            img[p[1], p[0]] = (215, 215, 215)
    for x, y in zip(wx[:: max(1, len(wx) // 40000)], wy[:: max(1, len(wy) // 40000)]):
        p = px(x, y)
        if 0 <= p[0] < S and 0 <= p[1] < S:
            img[p[1], p[0]] = (60, 60, 60)
    xl, xr = res["walls_x"]; yb, yt = res["walls_y"]
    cyan = (230, 180, 0)
    if None not in (xl, xr, yb, yt):
        cv2.rectangle(img, px(xl, yt), px(xr, yb), cyan, 3)
        if k:
            cv2.putText(img, fmt_ft((xr - xl) * k), (px((xl + xr) / 2, yt)[0] - 50, px(0, yt)[1] - 12), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (120, 80, 0), 2)
            cv2.putText(img, fmt_ft((yt - yb) * k), (px(xr, 0)[0] + 10, px(0, (yt + yb) / 2)[1]), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (120, 80, 0), 2)
    for x, y in zip(cxs, cys):
        cv2.circle(img, px(x, y), 4, (0, 140, 255), -1)
    cv2.imwrite(path, img)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video", help="room video, or a folder of photos")
    ap.add_argument("--weights", default=os.environ.get("VGGT_WEIGHTS", os.path.expanduser("~/vggt-work/weights")),
                    help="folder with config.json + model.safetensors (VGGT-1B-Commercial); or set VGGT_WEIGHTS")
    ap.add_argument("--frames", type=int, default=20)
    ap.add_argument("--tape", help="tape length of the longest wall, e.g. \"15 0 1/8\"")
    ap.add_argument("--out", default=None)
    ap.add_argument("--mapanything", default=os.environ.get("MAPANYTHING_WEIGHTS"),
                    help="folder with MapAnything config.json + model.safetensors; adds real-world size and a MapAnything-only room")
    a = ap.parse_args()
    out = a.out or os.path.abspath(a.video).rstrip("/") + "-vggt"
    os.makedirs(out, exist_ok=True)
    t0 = time.time()
    if os.path.isdir(a.video):
        paths = sorted(os.path.join(a.video, f) for f in os.listdir(a.video) if f.lower().endswith((".jpg", ".jpeg", ".png")))[: a.frames]
        nframes = len(paths)
    else:
        paths, nframes = pick_frames(a.video, a.frames, os.path.join(out, "frames"))
    t1 = time.time()
    print(f"picked {len(paths)} of {nframes} frames in {t1 - t0:.0f}s", flush=True)
    pts, conf, cols, extr, intr, vdepth = run_vggt(paths, a.weights)
    t2 = time.time()
    print(f"VGGT on CPU: {t2 - t1:.0f}s", flush=True)
    res, draw = room_from_points(pts, conf, cols, extr)
    tape_m = parse_feet(a.tape) if a.tape else None
    seconds = {"frames": round(t1 - t0), "vggt": round(t2 - t1), "geometry": round(time.time() - t2)}
    mpu = None; ma_report = None; note = ""
    if a.mapanything:
        t3 = time.time()
        mpts, mconf, mcols, mextr, mdepth = run_mapanything(paths, a.mapanything)
        seconds["mapanything"] = round(time.time() - t3)
        print(f"MapAnything on CPU: {seconds['mapanything']}s", flush=True)
        ds = depth_scale(vdepth, conf, mdepth, mconf)
        if ds:
            mpu, spread = ds
            note = f" (depth ratio spread x{spread:.2f})"
        mres, mdraw = room_from_points(mpts, mconf, mcols, mextr)
        ma_report = {"metres": mres, "in_feet": scaled(mres, tape_m, 1.0, " (its own room, no VGGT)") if mdraw else {}}
        if mdraw:
            draw_plan(mdraw, os.path.join(out, "plan_mapanything.png"), 1.0)
    report = {"video": os.path.basename(a.video), "seconds": seconds,
              "model_units": res, "in_feet": scaled(res, tape_m, mpu, note) if draw else {},
              "mapanything_only": ma_report}
    with open(os.path.join(out, "report.json"), "w") as f:
        json.dump(report, f, indent=2, default=float)
    if draw:
        k = None
        if mpu:
            k = mpu
        elif tape_m and res.get("length_x") and res.get("length_y"):
            k = tape_m / max(res["length_x"], res["length_y"])
        elif res.get("ceiling_minus_floor"):
            k = 8 * FT / res["ceiling_minus_floor"]
        draw_plan(draw, os.path.join(out, "plan.png"), k)
    print(json.dumps(report["in_feet"], indent=2))
    if ma_report:
        print("MapAnything only:", json.dumps(ma_report["in_feet"], indent=2))
    print("written to", out)


if __name__ == "__main__":
    main()
