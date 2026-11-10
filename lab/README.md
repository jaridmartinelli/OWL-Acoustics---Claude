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
| 8 | Camera trace — freeze a view, trace the floor and ceiling lines with a finger | 8 ft ceiling until a size is typed | — |

Method 2 here is model-free (no learned layout network). Method 3 (server) is paused.

## Test procedure

1. Tape-measure one room. Enter Length × Width (or all walls in order) as the truth.
2. Set phone height with method 7 (door), or type it. Hold the phone the same way while scanning.
3. Run each method in that room (methods 2 and 6 run together in one spin).
4. Open "Compare results": average and worst wall error in inches, corner squareness, area and perimeter error,
   time and taps, best first.

Use the phone folded (cover screen) and upright in portrait; the tilt maths assumes an upright phone.

## Method 8 · Camera trace

Added in 0.2.0. Stills from the rendered test are in `docs/capture-trace/`. Not run on a phone yet.

1. Stand in one spot. Hold the phone upright and keep it at the same place and height while you turn.
2. **Floor** is selected. Aim at where the floor meets a wall and drag a finger along the line. The picture freezes,
   the line clings to the edge (dashed where it finds none) and a loupe shows what your finger covers. Corners are
   placed where two straight lines cross.
3. Tap **Resume camera**, turn until the end of the line is in view, and drag on from the last point. Finish on the
   first point to close the loop (or tap it).
4. Switch to **Ceiling** and trace at least one ceiling line. Floor and ceiling lines of the same wall give its
   distance, so no phone height is needed. Until you type a size the app assumes an 8 ft ceiling and hides sizes.
5. Tap the longest wall's **+ size** and type what your tape says (`15 0 1/8` works). Every size then shows; typed
   ones carry a padlock. The tag at the top shows the ceiling height that follows; it turns amber if that is unusual.
6. **Save** puts the outline on the scorecard next to the other methods.

Other gestures: tap empty space to place a point by hand; tap a line to add a marker point; drag a point to move it
(tap the end point first to select it, otherwise a drag continues the line); double-tap a point, or tap it and then
Delete, to remove it; pinch a frozen picture to zoom; on the live view drag with two fingers to slide the lines
sideways if they have drifted off the room.

Test switches (⋮): how the picture freezes, whether the mesh grows while drawing, ceiling outline when the floor is
hidden, squaring corners, lowest/highest edge preference, estimates before a size is typed, haptics, and the distance
between the phone and the point you turn around (default 12 in). **If traced lines slide off their edges as you
turn, change that distance until they stay.**

Limits: the phone's position is not tracked (Walk/ARCore mode is not built), so stepping sideways or raising the
phone moves the lines off the room; lens distortion is not corrected; doors and windows are not captured beyond marker
points; accuracy on a real phone is unknown until scored against a tape measure.

## Build

`./gradlew :lab:assembleDebug` downloads the depth model from Hugging Face at build time (SHA-256 checked) into
`lab/build/generated/model-assets`; the model is not committed.
