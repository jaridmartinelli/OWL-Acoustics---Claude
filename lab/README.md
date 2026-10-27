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

Method 2 here is model-free (no learned layout network). Method 3 (server) is paused.

## Test procedure

1. Tape-measure one room. Enter Length × Width (or all walls in order) as the truth.
2. Set phone height with method 7 (door), or type it. Hold the phone the same way while scanning.
3. Run each method in that room (methods 2 and 6 run together in one spin).
4. Open "Compare results": average and worst wall error in inches, corner squareness, area and perimeter error,
   time and taps, best first.

Use the phone folded (cover screen) and upright in portrait; the tilt maths assumes an upright phone.

## Build

`./gradlew :lab:assembleDebug` downloads the depth model from Hugging Face at build time (SHA-256 checked) into
`lab/build/generated/model-assets`; the model is not committed.
