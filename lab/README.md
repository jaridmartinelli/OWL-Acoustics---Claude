# OWL Capture Lab (test app)

Separate test app (`com.owlacoustics.capturelab.dev`) for comparing camera room-capture methods. It never reads or
changes the main OWL app's data. 64-bit ARM phones only. Untested on a device: it was built and its maths unit-tested
on a JVM host.

| # | Method | Scale source | Needs |
|---|---|---|---|
| 1 | ARCore tracing — aim at each floor corner | ARCore (metric) | ARCore |
| 2 | Spin: floor-line — turn on the spot, floor–wall line per view | phone height | — |
| 4 | Aim + tilt — stand still, aim at each floor corner | phone height | — |
| 5 | ARCore auto walls — vertical planes → outline | ARCore (metric) | ARCore |
| 6 | Spin: AI depth — Depth Anything V2 Small (Apache-2.0), scaled by the floor | phone height | — |
| 7 | Door scale — sets phone height from a door of known height | door height | — |
| 8 | Camera scan — automatic floor/wall/ceiling with a correction brush | 8 ft ceiling until a size is typed | — |

Method 2 here is model-free (no learned layout network). Method 3 (server) is paused.

## Test procedure

1. Tape-measure one room. Enter Length × Width (or all walls in order) as the truth.
2. Set phone height with method 7 (door), or type it. Hold the phone the same way while scanning.
3. Run each method in that room (methods 2 and 6 run together in one spin).
4. Open "Compare results": average and worst wall error in inches, corner squareness, area and perimeter error,
   time and taps, best first.

Use the phone folded (cover screen) and upright in portrait; the tilt maths assumes an upright phone.

## Method 8 · Camera scan

0.3.0 replaces the 0.2.0 tracing screen. Not verified on a phone.

1. Stand in one spot, phone upright. The widest back lens is used. Nothing to start: the app looks for the floor,
   walls and ceiling as you turn slowly, and meshes them in one-foot squares (floor cyan, walls green, ceiling yellow).
2. Where the boundary is wrong, brush. **+** adds: brush on the floor (or ceiling) and it reaches out to the next
   edge. **−** takes away: brush where it went too far. What you take away stays taken away.
3. **Floor / Wall / Ceiling** choose what the brush and the size labels work on. Wall shows heights.
4. Tap **+ size** on the longest wall and type its length on the number pad. Everything recalibrates; typed sizes
   carry a padlock. Until then an 8 ft ceiling is assumed and sizes are hidden.
5. Hold a corner point for its actions. **3D** opens a model you can turn and zoom. **Save** adds the outline to the
   scorecard.

Two fingers dragged sideways slide the whole boundary back onto the room if it has drifted. Test switches are under ⋮,
including the distance between the phone and the point you turn around.

Limits: stand and turn only (walking is not built); lens distortion is not corrected; doors and windows are not
captured; automatic detection depends on the floor being visible at the bottom of the picture.

## Build

`./gradlew :lab:assembleDebug` downloads the depth model from Hugging Face at build time (SHA-256 checked) into
`lab/build/generated/model-assets`; the model is not committed.
