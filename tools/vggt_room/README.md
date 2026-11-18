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
