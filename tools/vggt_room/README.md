# VGGT room test (feasibility, not part of the app)

Checks whether VGGT can recover a room's walls from an ordinary phone video, before anything is built into the
app or a server. Owner decision 2026-11-16/17: try the VGGT + Open3D pipeline (see `docs/DECISIONS.md`).

```
video -> sharpest frame in each of N time slices -> VGGT (cameras + depth) -> 3D points
      -> Open3D: floor and ceiling planes -> wall points in a band just below the ceiling (clutter rarely reaches it)
      -> walls at right angles in the room's own axes -> sizes in feet (from your tape, or an assumed 8 ft ceiling)
```

## Licence and access

- Weights: `facebook/VGGT-1B-Commercial` (Meta, "VGGT License" v1, 2025-07-29: commercial use permitted under its
  terms and Acceptable Use Policy). The repository is gated; access was granted to the owner's Hugging Face
  account. The token is stored as a network secret on the cloud environment, never in the repo.
- The original `facebook/VGGT-1B` checkpoint is non-commercial and is **not** used.
- Code: facebookresearch/vggt (same licence). Open3D: MIT. OpenCV: Apache-2.0. PyTorch: BSD-3.

## Setup (what was run here)

```
mkdir -p ~/vggt-work && cd ~/vggt-work
git clone --depth 1 https://github.com/facebookresearch/vggt.git src
uv venv -p python3.13 .venv && . .venv/bin/activate
uv pip install torch torchvision numpy pillow huggingface_hub einops safetensors opencv-python-headless open3d
uv pip install --no-deps -e src      # vggt pins numpy<2 / torch 2.3, which have no Python 3.13 builds
# weights: config.json + model.safetensors from facebook/VGGT-1B-Commercial into ~/vggt-work/weights
```

## Optional: MapAnything for real-world size

```
git clone --depth 1 https://github.com/facebookresearch/map-anything.git ~/vggt-work/mapanything
uv pip install --no-deps -e ~/vggt-work/mapanything
uv pip install hydra-core natsort orjson pillow-heif plyfile python-box trimesh uniception==0.1.7
# weights: facebook/map-anything (CC BY-NC 4.0, most accurate) or facebook/map-anything-apache (Apache 2.0)
python tools/vggt_room/vggt_room.py ROOM.mp4 --mapanything ~/vggt-work/weights-mapanything
```

Adds `from_mapanything` (VGGT's shape sized by MapAnything's depth) and `mapanything_only` (MapAnything's own room) to
the report, plus `plan_mapanything.png`.

### Comparing the two MapAnything checkpoints (licence vs accuracy)

| Checkpoint | Licence (model card, read 2026-11-19) | Training data (paper, arXiv 2509.13414) |
|---|---|---|
| `facebook/map-anything` | CC BY-NC 4.0: non-commercial, test only (U-09) | 13 datasets |
| `facebook/map-anything-apache` | Apache 2.0: commercial use allowed | 6 of those 13 |
| `facebook/map-anything-apache-v1` | Apache 2.0, older (Sept 2025) | not used here |

Meta's paper says the Apache model shows "a decrease in performance" but publishes no side-by-side numbers in its text,
so the gap has to be measured. Put each checkpoint in its own folder and give `--mapanything` once per checkpoint, with a
label; both run on the same frames:

```
python tools/vggt_room/vggt_room.py ROOM.mp4 --tape "15 0 1/8" \
    --mapanything nc=~/vggt-work/weights-mapanything \
    --mapanything apache=~/vggt-work/weights-mapanything-apache
```

The report then has `from_mapanything_<label>` blocks, `mapanything_only.<label>`, a `plan_mapanything_<label>.png` per
checkpoint, and `checkpoint_comparison`: for each checkpoint, the room in metres two ways (VGGT's shape sized by that
checkpoint, and that checkpoint's own room), the percentage difference from the first checkpoint, and with `--tape` the
longest-wall error against the tape. Only the tape rows measure accuracy; the difference between checkpoints does not.
One room is one sample. With one `--mapanything` the report is laid out exactly as before.

Offline check of this comparison (no weights; a synthetic 15' x 12' room and two fake checkpoints, one 3% too large):

```
python tools/vggt_room/test_compare_checkpoints.py
```

## Run

```
python tools/vggt_room/vggt_room.py ROOM.mp4 --tape "15 0 1/8"
```

Writes `report.json`, `plan.png` (top view: grey everything, dark wall points, cyan fitted walls, orange phone
positions) and the chosen frames next to the video. Runs on CPU here (minutes); a real service would need a GPU.

## Recording a test video

Normal camera app at 1×, phone upright at chest height, one slow full turn (30–40 s) from near the middle of the
room, keeping the line where the walls meet the ceiling in view. Tape-measure the longest wall.

## Limits

Assumes the phone is held upright (image "down" = gravity) and that the room's walls meet at right angles; reports
one rectangle plus any other wall lines it saw (possible alcoves). No doors or windows yet.
