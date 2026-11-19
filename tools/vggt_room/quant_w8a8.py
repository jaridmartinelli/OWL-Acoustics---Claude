#!/usr/bin/env python3
"""Option A (U-11): 8-bit VGGT (W8A8) with QuantVGGT's rotation + smoothing, on CPU.

What it does
  1. Runs full-precision VGGT-1B-Commercial on each evaluation folder and keeps the outputs.
  2. Replaces every linear layer in the aggregator's frame and global blocks with QuantVGGT's
     SmoothRotQuantizedLinear (Hadamard rotation, then per-channel smoothing, 8-bit per-output-channel
     weights, 8-bit dynamic per-token activations). DINOv2 patch embedding and all heads stay full
     precision, as in QuantVGGT.
  3. Collects smoothing statistics on the calibration folders (no ground truth, no training).
  4. Re-runs the evaluation folders and compares quantised against full precision.

Not done here (needs a CUDA GPU): QuantVGGT's learned clipping (LWC/LAC) and NFDS sample selection.
Quantisation is simulated in float ("fake quant"); this measures accuracy, not phone speed.

Deviations from the released code, both recorded in the report:
  - one random Hadamard matrix per input size (seeded), shared by all layers, instead of one per layer;
  - smoothing scale uses the rotated weight, max|W H|, as in the paper (Eq. 6); the released code uses
    the unrotated weight. Use --code-smooth for the released behaviour.

Usage
  python quant_w8a8.py --quantvggt ~/vggt-work/quantvggt --weights ~/vggt-work/weights \
      --calib kitchen/images llff_fern/images --eval room/images --out runs/w8a8
"""
import argparse
import glob
import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def frame_sets(folder, n, max_sets):
    files = sorted(glob.glob(os.path.join(folder, "*")))
    files = [f for f in files if f.lower().endswith((".jpg", ".jpeg", ".png"))]
    if len(files) <= n:
        return [files]
    # interleaved subsets so each set spans the whole capture
    k = min(max_sets, len(files) // n)
    return [files[i::k][:n] for i in range(k)]


def load_images(paths, long_side=518):
    import torch
    from PIL import Image
    w0, h0 = Image.open(paths[0]).size
    if w0 >= h0:
        W, H = long_side, max(14, round(long_side * h0 / w0 / 14) * 14)
    else:
        H, W = long_side, max(14, round(long_side * w0 / h0 / 14) * 14)
    imgs = []
    for p in paths:
        im = Image.open(p).convert("RGB").resize((W, H), Image.BICUBIC)
        imgs.append(torch.from_numpy(np.array(im)).permute(2, 0, 1).float() / 255.0)
    return torch.stack(imgs)[None]


def infer(model, images):
    import torch
    from vggt.utils.geometry import unproject_depth_map_to_point_map
    from vggt.utils.pose_enc import pose_encoding_to_extri_intri
    with torch.no_grad():
        tokens, ps_idx = model.aggregator(images)
        pose_enc = model.camera_head(tokens)[-1]
        extr, intr = pose_encoding_to_extri_intri(pose_enc, images.shape[-2:])
        depth, conf = model.depth_head(tokens, images, ps_idx)
    extr = extr.squeeze(0).numpy()
    intr = intr.squeeze(0).numpy()
    depth = depth.squeeze(0).numpy()
    conf = conf.squeeze(0).numpy()
    pts = unproject_depth_map_to_point_map(depth, extr, intr)
    cols = images.squeeze(0).permute(0, 2, 3, 1).numpy()
    return dict(pts=pts, conf=conf, cols=cols, extr=extr, intr=intr, depth=depth[..., 0])


def rot_deg(Ra, Rb):
    c = (np.trace(Ra @ Rb.T) - 1) / 2
    return float(np.degrees(np.arccos(np.clip(c, -1, 1))))


def compare(a, b):
    """Quantised (b) against full precision (a), same frames."""
    S = a["extr"].shape[0]
    # camera: relative pose to frame 0, so both share the same reference
    rerr, terr = [], []
    for i in range(1, S):
        Ra, ta = a["extr"][i, :, :3], a["extr"][i, :, 3]
        Rb, tb = b["extr"][i, :, :3], b["extr"][i, :, 3]
        rerr.append(rot_deg(Ra, Rb))
        terr.append(float(np.linalg.norm(ta - tb) / (np.linalg.norm(ta) + 1e-9)))
    mask = a["conf"] > np.quantile(a["conf"], 0.45)
    drel = np.abs(b["depth"] - a["depth"])[mask] / np.maximum(a["depth"][mask], 1e-6)
    scene = float(np.median(np.linalg.norm(a["pts"][mask] - np.median(a["pts"][mask], 0), axis=-1)))
    pdist = np.linalg.norm(b["pts"] - a["pts"], axis=-1)[mask] / scene
    return {
        "camera_rotation_deg_median": float(np.median(rerr)) if rerr else 0.0,
        "camera_rotation_deg_max": float(np.max(rerr)) if rerr else 0.0,
        "camera_translation_rel_median": float(np.median(terr)) if terr else 0.0,
        "depth_rel_diff_median": float(np.median(drel)),
        "depth_rel_diff_p90": float(np.quantile(drel, 0.9)),
        "points_diff_rel_scene_median": float(np.median(pdist)),
        "points_diff_rel_scene_p90": float(np.quantile(pdist, 0.9)),
    }


def room_dims(o):
    from vggt_room import room_from_points
    try:
        res, _ = room_from_points(o["pts"], o["conf"], o["cols"], o["extr"])
    except Exception as e:  # geometry can fail on non-room scenes
        return {"error": str(e)}
    keep = {k: res.get(k) for k in ("length_x", "length_y", "ceiling_minus_floor", "camera_above_floor", "error")}
    return {k: v for k, v in keep.items() if v is not None}


def patch_quarot(quantvggt_dir, seed):
    import torch
    sys.path.insert(0, os.path.join(quantvggt_dir, "evaluation"))
    if "tkinter" not in sys.modules:  # released code has an unused `from tkinter import NO`
        import types
        sys.modules["tkinter"] = types.SimpleNamespace(NO="no")
    import quarot.quarot_linear as ql
    from quarot.quarot_utils import random_hadamard_matrix
    cache = {}

    def shared_hadamard(size, device):
        if size not in cache:
            g = torch.random.get_rng_state()
            torch.manual_seed(seed + size)
            cache[size] = random_hadamard_matrix(size, "cpu").to(torch.float32)
            torch.random.set_rng_state(g)
        return cache[size]

    ql.random_hadamard_matrix = shared_hadamard
    return ql


def quantise_blocks(model, ql, wbits, abits, rotate):
    import torch.nn as nn

    class Cfg:  # the fields SmoothRotQuantizedLinear reads
        pass
    cfg = Cfg()
    cfg.w_bits, cfg.a_bits = wbits, abits
    cfg.w_asym = cfg.a_asym = False
    cfg.a_groupsize = -1
    cfg.not_rot, cfg.not_smooth = (not rotate), False
    cfg.lwc = cfg.lac = cfg.rv = False

    layers = []
    for blocks in (model.aggregator.frame_blocks, model.aggregator.global_blocks):
        for blk in blocks:
            for parent in (blk.attn, blk.mlp):
                for name, child in list(parent.named_children()):
                    if isinstance(child, nn.Linear):
                        q = ql.SmoothRotQuantizedLinear(cfg, child)
                        setattr(parent, name, q)
                        layers.append(q)
    return layers


def finish_smoothing(layers, code_smooth):
    import torch
    import torch.nn as nn
    for m in layers:
        m.smooth_quant_running_stat = False
        a = m.act_quantizer.act_scale
        w = m.linear.weight.data.float()
        if not m.not_rot and not code_smooth:
            w = w @ m.rotation_matrix
        s = a.pow(m.smooth_quant_alpha) / w.abs().max(dim=0)[0].pow(1 - m.smooth_quant_alpha)
        m.channel_wise_scale = nn.Parameter(torch.clamp(s, min=1e-5).reshape(1, -1))
        m.reparameterize()  # quantise weights once, switch to eval (quantised) mode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quantvggt", required=True)
    ap.add_argument("--weights", default=os.environ.get("VGGT_WEIGHTS", ""))
    ap.add_argument("--calib", nargs="+", required=True, help="image folders used for statistics")
    ap.add_argument("--eval", nargs="+", required=True, help="image folders to compare on (held out)")
    ap.add_argument("--frames", type=int, default=8)
    ap.add_argument("--calib-sets", type=int, default=3, help="max frame sets taken from each calib folder")
    ap.add_argument("--wbits", type=int, default=8)
    ap.add_argument("--abits", type=int, default=8)
    ap.add_argument("--no-rotation", action="store_true", help="smoothing only (no Hadamard)")
    ap.add_argument("--code-smooth", action="store_true", help="released-code smoothing (unrotated weight)")
    ap.add_argument("--long-side", type=int, default=518)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    import torch
    from vggt.models.vggt import VGGT
    torch.set_num_threads(os.cpu_count() or 4)
    torch.manual_seed(a.seed)
    os.makedirs(a.out, exist_ok=True)
    t0 = time.time()
    log = lambda *x: print(f"[{time.time() - t0:7.0f}s]", *x, flush=True)

    ql = patch_quarot(a.quantvggt, a.seed)
    model = VGGT.from_pretrained(a.weights).eval()
    model.track_head = None  # OWL does not use point tracking

    evals = []
    for folder in a.eval:
        for paths in frame_sets(folder, a.frames, 1):
            evals.append((folder, paths, load_images(paths, a.long_side)))
    full = []
    for folder, paths, img in evals:
        full.append(infer(model, img))
        log("full precision:", folder, len(paths), "frames")

    layers = quantise_blocks(model, ql, a.wbits, a.abits, rotate=not a.no_rotation)
    log(f"wrapped {len(layers)} linear layers (W{a.wbits}A{a.abits}, rotation={'off' if a.no_rotation else 'on'})")

    calib_used = []
    with torch.no_grad():
        for folder in a.calib:
            for paths in frame_sets(folder, a.frames, a.calib_sets):
                model.aggregator(load_images(paths, a.long_side))
                calib_used.append({"folder": folder, "frames": [os.path.basename(p) for p in paths]})
                log("calibration statistics:", folder, len(paths), "frames")
    finish_smoothing(layers, a.code_smooth)
    log("weights quantised")

    results = []
    for (folder, paths, img), f in zip(evals, full):
        q = infer(model, img)
        r = {"folder": folder, "frames": len(paths), "vs_full_precision": compare(f, q),
             "room_full": room_dims(f), "room_quantised": room_dims(q)}
        rf, rq = r["room_full"], r["room_quantised"]
        r["room_change_pct"] = {k: 100 * (rq[k] - rf[k]) / rf[k] for k in ("length_x", "length_y", "ceiling_minus_floor")
                                if isinstance(rf.get(k), float) and isinstance(rq.get(k), float) and rf[k]}
        results.append(r)
        log("quantised:", folder, json.dumps(r["vs_full_precision"]), json.dumps(r["room_change_pct"]))

    report = {
        "setting": f"W{a.wbits}A{a.abits} simulated, per-output-channel weights, dynamic per-token activations",
        "rotation": not a.no_rotation, "smoothing": "released code (unrotated W)" if a.code_smooth else "paper Eq. 6 (rotated W)",
        "hadamard": "one seeded matrix per input size, shared", "learned_clipping": False,
        "quantised": "aggregator frame + global blocks (all linear layers); DINOv2 embed, attention matmuls and heads full precision",
        "weights": a.weights, "calibration": calib_used, "results": results,
        "seconds": round(time.time() - t0),
        "note": "Comparison is against full-precision VGGT on the same frames, not against tape measurements.",
    }
    with open(os.path.join(a.out, "report.json"), "w") as fh:
        json.dump(report, fh, indent=2)
    log("wrote", os.path.join(a.out, "report.json"))


if __name__ == "__main__":
    main()
