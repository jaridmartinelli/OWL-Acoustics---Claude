# What to pair with VGGT (research, 2026-11-17)

Goal: VGGT gives the room's 3D shape (no units, walls only needed). Partners must supply real-world scale, clean
walls/corners, a simple mesh, and later doors/windows, and be usable in a commercial app. Licences below come from
search summaries and model cards; each must be re-checked on the official page before shipping. Nothing here is a
measured accuracy figure for our use.

## Top 10

| # | Program | Job | Pros | Cons | Licence |
|---|---|---|---|---|---|
| 1 | ARCore | Real feet + phone positions, live | On the phone, free, gives scale and positions VGGT/MapAnything can use, finds the floor | 1× lens only; can drift; Android only | Google SDK (free) |
| 2 | MapAnything (Apache checkpoint) | 3D with real-world scale | Metric output; can take phone positions and lens data as hints | Server GPU; Meta says the non-commercial checkpoint is the more accurate one | Apache 2.0 (`facebook/map-anything-apache`) |
| 3 | Open3D | Planes, clean-up, mesh | Proven, fast, Python, built into our test | Plane finding needs tuning; random sampling can miss small walls | MIT |
| 4 | COLMAP / pycolmap bundle adjustment | Sharpen VGGT's camera positions | Built into VGGT's repo (`demo_colmap.py --use_ba`); straighter, more consistent walls | Slower; weak on blank walls | BSD (to verify) |
| 5 | SAM 3 | Label wall / floor / ceiling / door / window in frames by text, then onto 3D | One model, text prompts, strong masks | Custom SAM Licence (commercial allowed per summaries, with restrictions; Meta can change terms); server | SAM Licence (2025-11-19) |
| 6 | Depth Anything 3 Metric-Large | Second opinion on scale per frame | Apache; 0.35B (lighter); metric depth | Single-image depth is less consistent than multi-view; other DA3 variants are non-commercial | Apache 2.0 (Metric-Large, Base, Small) |
| 7 | MoGe-2 | Metric geometry + surface normals per frame | Normals help separate walls from clutter; MIT | Per image; one source flags a research-use caveat on weights | MIT (to verify for weights) |
| 8 | OWLv2 / Florence-2 / Grounding DINO | Find doors and windows by text | OWLv2 Apache, Florence-2 MIT; feed boxes to SAM | Boxes only; door/window accuracy untested; Grounding DINO weights licence unclear | Apache / MIT / unclear |
| 9 | VGGT-Long / VGGT-SLAM | Long walk-through videos | Many frames, whole homes | Built for kilometre-scale outdoor runs; extra complexity; a single room usually fits VGGT directly | Follows VGGT (to verify) |
| 10 | CGAL shape detection / PolyFit | Higher-quality planes, watertight simple models | Region growing found 100% of shapes vs 78% for efficient RANSAC in CGAL's example | GPL: commercial use needs a paid licence; C++ | GPL / commercial |

## Ruled out for a commercial app

SpatialLM (weights CC BY-NC), SceneScript (research only), Pi3 weights (CC BY-NC), MapAnything default checkpoint
(CC BY-NC), original VGGT-1B (non-commercial), Depth Anything 3 Large/Giant any-view (CC BY-NC). DUSt3R/MASt3R,
HorizonNet, LGT-Net and RoomFormer: licences not confirmed; trained on datasets with research terms; not used until
checked.

## Suggested test order (owner's room video + one tape measurement)

A. VGGT + Open3D (built) · B. MapAnything-Apache + Open3D · C. VGGT + bundle adjustment + Open3D ·
D. add Depth Anything 3 Metric as a scale check. Then SAM 3 (or OWLv2 + SAM 2) for doors and windows.

## Sources

[MapAnything paper](https://arxiv.org/pdf/2509.13414) · [map-anything-apache](https://huggingface.co/facebook/map-anything-apache/blob/main/README.md) ·
[SAM 3 repo](https://github.com/facebookresearch/sam3) · [SAM 3 licence summary](https://roboflow.com/model-licenses/segment-anything-3) ·
[Depth Anything 3 on PyPI](https://pypi.org/project/depth-anything-3/) · [DA3-BASE card](https://huggingface.co/depth-anything/DA3-BASE/blob/main/README.md) ·
[MoGe repo](https://github.com/microsoft/moge) · [MoGe-2 paper](https://arxiv.org/abs/2507.02546v1) ·
[VGGT-Long](https://arxiv.org/abs/2507.16443v1) · [VGGT-SLAM](https://arxiv.org/abs/2505.12549v2) ·
[Grounding DINO licence summary](https://roboflow.com/model-licenses/grounding-dino) · [Florence-2 vs Grounding DINO](https://playground.roboflow.com/models/compare/florence-2-vs-grounding-dino) ·
[CGAL shape detection](https://doc.cgal.org/6.1/Shape_detection/index.html) · [SpatialLM](https://github.com/manycore-research/SpatialLM) ·
[Pi3](https://github.com/yyfz/Pi3) · [SceneScript](https://www.projectaria.com/scenescript/) · [LGT-Net](https://arxiv.org/abs/2203.01824v1)
