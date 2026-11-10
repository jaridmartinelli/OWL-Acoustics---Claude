# Camera Trace ("magnetic lasso" room capture): touch-and-feel research (2026-11-10)

Status: research and predictions. The module was built from them on 2026-11-10 as method 8 in the Capture Lab
(`docs/DECISIONS.md` E-012; stills in `docs/capture-trace/`). It has not been run on a phone; every prediction below
is still to be confirmed there.

The idea: on the live camera view the user traces the floor boundary (blue) and the ceiling boundary (yellow) with a
finger. The line clings to the edge like Photoshop's magnetic lasso, stays pinned to the room when the phone moves,
and the floor, walls (green) and ceiling fill with a diagonal mesh in real time.

## Method and limits

- 16 web searches on interaction research, platform guidance and comparable apps.
- Two page fetches failed: this environment's network policy blocks `developers.google.com`, `hal.science` and
  `iihm.imag.fr`. Claims from the ARCore pages and from the Vincent/Nigay/Kurata paper therefore rest on the search
  engine's extracts and the paper's abstract. Their per-condition numbers were not read.
- No study was found that tests live-wire (magnetic lasso) tracing with a finger on a phone. The lasso predictions
  combine desktop lasso behaviour with touch research; they are the least certain part.
- Every size in dp or mm is a starting value to tune on the owner's phone.

## Predictions and the evidence behind them

| # | Feel decision | Evidence | Confidence |
|---|---|---|---|
| 1 | **Freeze the picture while the finger is down** (default on; "live" stays a switch). On lift the view goes live and the line stays pinned. | Handheld-AR pointing has two error sources: touch imprecision and viewpoint shake from hand jitter. A touch technique combined with video freeze was more accurate than both direct touch on live video and a screen-centre crosshair (Vincent, Nigay, Kurata, INTERACT 2013). | High for accuracy; numbers unread |
| 2 | **Loupe above the finger** showing the covered area with a pointer on the snapped point. It appears only while tracing and moves aside near the screen top. | "Shift" (Vogel & Baudisch, CHI 2007): a callout copying the occluded area gave much lower error on small targets than plain touch, and was faster than an offset cursor on larger targets because it shows only when needed. | High |
| 3 | **Search band about one thumb-pad wide** (≈10 mm, ≈64 dp) centred on the finger. This is the lasso's "Width". | Photoshop: Width is how far from the cursor the tool looks for an edge. One-handed thumb targets need ≈9.2–9.6 mm (Parhi, Karlson, Bederson, MobileHCI 2006). | Medium |
| 4 | **Never fight the finger.** With no strong edge in the band the line follows the finger and draws dashed ("not snapped"); solid means snapped. This is the lasso's "Contrast", set automatically, with no settings screen. | Photoshop's Contrast decides how strong an edge must be. Snap-and-go (Baudisch et al., CHI 2005): snapping should not block placing a point near, but not on, a snap target. | Medium |
| 5 | **Anchors drop at corners, not every few millimetres.** Tap forces an anchor; an Undo button removes the last one. | Photoshop drops fastening points automatically, a click forces one, Backspace removes the last. Rooms are straight walls, so anchors belong where direction changes. | Medium |
| 6 | **Corner lock is obvious and generous:** the cursor ring fills solid within one thumb-pad of a corner. | Apple Measure: the dot grows bolder and gives a haptic tap on a corner or wall end; a 2018 AppleVis review complained that the snap tolerance was small. | Medium |
| 7 | **Haptics: three crisp events only.** A light tick when the line first grabs an edge, a click when an anchor drops, a distinct confirm when the loop closes. No continuous buzz. | Android haptics guidance: prefer clear over buzzy, use predefined constants, repeated cues must be subtle. | High |
| 8 | **In walk (ARCore) mode, haptics are reduced to the anchor click, with a switch to turn them off.** | ARCore runtime guidance (search extract; page not opened) advises against haptics because vibration can disturb pose tracking. | Low until the page is read |
| 9 | **Show what is captured three ways:** lines pinned on the real edges, the mesh fill, and a small live top-down plan with wall lengths in feet. One line of coaching text ("Trace the ceiling edge", "Turn slower"). | Apple RoomPlan's capture view: animated outlines on detected structure, a small live 3D model for progress, and text coaching. ARCore guidance: ask for slow camera movement, fast motion hurts tracking. | High |
| 10 | **Controls in a bottom band, at least 9.6 mm (≈60 dp) tall,** nothing important in the top corners. Floor/Ceiling toggle bottom centre; Undo beside it. | Hoober (2013, 1,333 observed): 49% one-handed, 36% cradled, 15% two-handed, and people shift grip by task. Tracing means one hand holds and the other draws. Corners are the least accurate thumb zones (Alibaba study, as cited by Smashing Magazine). | Medium |
| 11 | **Freeze also fights fatigue:** aim at the wall, touch to freeze, lower the phone, trace in comfort. Each wall is saved as it is finished. | Raised-arm interaction tires the shoulder (Consumed Endurance, CHI 2014). ARCore guidance: users move less as they tire; mind session length. No phone-specific endurance figure was found. | Medium |
| 12 | **Dark halo on every line and label; surfaces also differ by mesh direction, not colour alone.** Blue floor, yellow ceiling, green walls as the owner specified. | Text and lines over video need an outline; colour should not carry meaning alone (Penn State accessibility guidance; caption legibility guides). Green and yellow can look alike with some colour-vision differences (general knowledge, not from these searches). | Medium |
| 13 | **Ceiling-first is offered, tap-to-force covers hidden floor corners.** | Room-scan guides list furniture blocking wall boundaries, and apps catching furniture edges instead of walls, as common failures (vendor and blog sources). | Low (weak sources) |

## How the build differs from the table

- Row 12: the mesh is a square one-foot grid on every surface, as in the owner's mockup, with FLOOR / WALL / CEILING
  tags; it does not vary direction per surface.
- Row 1: the picture freezes when the finger starts to draw and, by default, stays frozen until Resume (switches:
  only while touching, never).
- Row 8: Walk (ARCore) mode is not built, so the reduced-haptics rule is not exercised.

## Sources

- Magnetic lasso: [Adobe help](https://helpx.adobe.com/sk/photoshop/desktop/make-selections/freehand-selections/snap-to-image-edges-using-magnetic-lasso-tool.html), [O'Reilly, Photoshop CS5](https://oreilly.com/library/view/photoshop-cs5-the/9781449381691/ch04s04.html), [PHLEARN](https://phlearn.com/tutorial/photoshop-basics-the-magnetic-lasso-tool/)
- Live-wire origin: [Mortensen & Barrett, Intelligent Scissors (summary)](https://www.bohrium.com/paper-details/interactive-segmentation-with-intelligent-scissors/812457652662566913-12585); touch limits: [Garcia et al., Graphics Interface 2015](https://graphicsinterface.org/wp-content/uploads/gi2015-11.pdf)
- Handheld AR pointing: [Vincent, Nigay, Kurata 2013 (HAL)](https://hal.archives-ouvertes.fr/hal-00953357), [lab page](https://iihm.imag.fr/en/publication/VNK13a/), [follow-up on registration jitter](https://iihm.imag.fr/en/publication/VNK13c/)
- Finger occlusion: [Vogel & Baudisch, Shift, CHI 2007](https://projects.csail.mit.edu/hci-reading/papers/chi2007-vogel.pdf)
- Snapping: [Baudisch et al., Snap-and-go, CHI 2005](https://www.lri.fr/~mbl/Teach/SituatedComputing/2017/papers/Alignment/Baudisch-CHI05-SnapAndGo.pdf)
- Touch target size: [Parhi, Karlson, Bederson 2006](https://www.microsoft.com/en-us/research/?p=155150), [NN/g summary](https://www.nngroup.com/articles/touch-target-size/)
- Grip: [Hoober, How Do Users Really Hold Mobile Devices?](https://www.uxmatters.com/mt/archives/2013/02/how-do-users-really-hold-mobile-devices.php), [Smashing Magazine on one-hand use](https://shop.smashingmagazine.com/2020/02/design-mobile-apps-one-hand-usage/)
- Haptics: [Android haptics UX design](https://source.android.com/docs/core/interaction/haptics/haptics-ux-design), [Android haptics principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- ARCore: [runtime considerations](https://developers.google.com/ar/develop/runtime), [movement guidance](https://developers.google.com/ar/design/user/movement), [The Next Web on Google's AR guidelines](https://thenextweb.com/news/google-ar-design-guidelines-bad-good-2d-3d)
- Capture feedback: [Apple RoomPlan](https://developer.apple.com/augmented-reality/roomplan), [WWDC22 session 10127](https://developer.apple.com/wwdc22/10127)
- Apple Measure: [Macworld](https://www.macworld.com/article/3304290/iphone-ipad/how-to-use-the-augmented-reality-measure-app-in-ios-12.html), [MacRumors](https://macrumors.com/how-to/use-the-augmented-reality-measure-app-ios-12), [AppleVis](https://www.applevis.com/blog/ios-apps-iphone/measuring-ios-12-measure-app)
- Fatigue: [Consumed Endurance, CHI 2014](https://hci.cs.umanitoba.ca/Publications/details/consumed-endurance-a-metric-to-quantify-arm-fatigue-of-mid-air-interactions)
- Legibility over video: [Penn State accessibility: contrast](https://accessibility.psu.edu/legibility/contrast), [caption outline vs shadow](https://blitzcutai.com/blog/caption-background-vs-outline-vs-shadow)
- Scan failures: [Coohom guide](https://www.coohom.com/article/how-to-fix-room-scanning-and-measurement-errors-in-furniture-layout-apps), [magicplan AR scan](https://magicplan.app/blog/ar-room-scan)
