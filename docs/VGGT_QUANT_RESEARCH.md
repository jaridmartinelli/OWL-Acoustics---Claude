# Compressing VGGT: QuantVGGT vs QVGGT (read 2026-11-19)

Both papers were read in full from arXiv. No figures below are measured by us.

| | QuantVGGT (Feng et al., ICLR 2026, arXiv 2509.21302) | QVGGT (Pan, Wang, Wang, CVPR 2026, arXiv 2605.31124) |
|---|---|---|
| Format | Weights and activations both low-bit: W8A8, W6A6, W4A4 | Weights only 4-bit, activations 16-bit (W4A16), group size 128 |
| Core method | DSFQ: Hadamard rotation first, then per-channel smoothing (α = 0.5) computed in the rotated space; per-output-channel weight scales; dynamic per-token activation scales | Per-block sensitivity test; frame blocks 14, 17, 23 and global block 23 keep FP16 except the attention projection; all other blocks INT4 |
| Special tokens | Rotation spreads out the camera/register-token outliers | Camera/register tokens left out of calibration; a PCA token (K = 32) built from calibration camera tokens is added back to the camera head |
| Calibration | NFDS: score samples by deep-layer mean/variance, drop the noisiest (T = 0.2), K-means on first-frame-to-other-frame similarity (K = 8), 40 picked from 400; no ground truth needed; block-wise learned scales | 128 images from CO3Dv2 and ScanNet; scale grid search scored with ground-truth camera, depth and point losses plus a consistency loss (depth + pose vs point head) |
| Calibration cost | 2.67 h, 14.6 GB, RTX 4090 | about 2 h, RTX 4090 |
| Pose accuracy, CO3Dv2 AUC@30 (full precision 89.5) | W8A8 89.4; W4A4 86.9 (10 frames), 88.2 of 90.0 (20 frames) | W4A16 89.4; GPTQ 76.9, AWQ 54.6 at W4A16 |
| Indoor 3D accuracy | DTU only: Acc mean 1.185 FP vs 1.182 W8A8 vs 1.282 W4A4 | 7-Scenes Acc mean 0.030 FP16 vs 0.031; NRGBD 0.024 vs 0.029 |
| Speed / memory | W8A8: 2.17× faster, 1.93× smaller; W4A4: 2.49× faster, 3.65× smaller; hardware for latency not stated | 1.93× faster, 2.11× less memory at 2 frames; 4.9× (1 frame) to 3.7× (30 frames) less peak memory at 518×518; RTX 4090 |
| Phones | Not tested | Not tested (abstract names mobile AR as a goal) |
| Base checkpoint | VGGT-1B (`model_tracker_fixed_e20.pt`, original non-commercial release) | VGGT-1B |
| Code | Released, Apache-2.0 (github.com/wlfeng0509/QuantVGGT, based on VGGT and QuaRot) | No code link on the project page |
| Released weights | Hugging Face `wlfeng/QuantVGGT`, tagged MIT; W4A4 parameters for the non-commercial checkpoint | None |

## Notes

- QVGGT says QuantVGGT's released code uses simulated ("pseudo") quantization, so its speed figures are not reproducible from that code.
- QVGGT has two internal inconsistencies: depth/point loss weights are 1 in §3.4 but 0.1 in Appendix A; the no-compensation NRGBD figure is 0.029 in Table A2 but 0.054 in the text.
- Each paper reports results only on its own benchmark set. The one direct comparison (QVGGT Table 1) comes from the QVGGT authors.
- QVGGT memory trick usable anywhere: keep only the outputs of blocks 4, 11, 17 and 23 (the ones the heads read).

## What this means for OWL

- QuantVGGT's published parameters belong to the non-commercial VGGT. Our commercial weights need fresh calibration either way.
- QVGGT's scale search needs ground truth (CO3Dv2, ScanNet). Those datasets carry their own terms; to verify. QuantVGGT calibration needs no ground truth, so it can use our own room videos.
- Phone NPUs commonly run W8A8 and W4A16. W4A4 activation kernels are rare on phones (to verify for Snapdragon 8 Gen 2).
- Calibration needs a CUDA GPU (about 2–3 h on an RTX 4090). This container is CPU only.
