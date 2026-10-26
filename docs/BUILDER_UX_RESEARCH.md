# 2D Room Builder — UX Research and Redesign (2026-10-26)

Goal: the manual 2D builder should feel effortless for one person measuring **one room for sound**. Furniture and
whole-house planning are out of scope. Persona: a 37-year-old musician who records at home, has a tape measure and
two numbers — **11' 6 1/4"** and **15' 1/8"** — and taps "Build manually".

## 1. What people say they enjoy

Collected from app-store/review-site feedback and vendor help pages for magicplan, RoomSketcher, Floorplanner,
Planner 5D, Floor Plan Creator, Floorplans (iPad), Leica DISTO Plan and Measure. Exact "I love that I can…" quotes
were rare in searchable text; the items below are the recurring themes, with representative paraphrases.

1. **Start from a ready rectangle, then fix the numbers.** magicplan's "Square Room" drops a room in one tap and
   "dimensions can be adjusted after creation"; its help calls it "fast and simple to use".
2. **Tap the length and type the real number.** magicplan: "tap the length you want to change, enter the numerical
   value and tap OK". A RoomSketcher reviewer called drawing walls to precise measurements excellent compared with
   other platforms.
3. **"Ease of creating walls of the correct size"** (Floorplanner G2 review), placing windows and doors, and seeing
   measurements.
4. **Dragging a corner/junction reshapes both connected walls; dragging a wall resizes the room** (Floorplanner,
   TechRadar review). Measurements are colour-matched to the drawing.
5. **Snapping to 90° and 45° with visible guides, plus perpendicular/parallel/point snaps** (Measure, Planoplan,
   Leica DISTO Plan). Seeing the wall's length and angle *while* drawing.
6. **"Tap the corners to force a right angle"** after a rough sketch (magicplan user).
7. **Type in tape or laser measurements** instead of trusting the drawing (magicplan, Floor Plan Creator, BiggerPockets
   users).
8. **Lock a measured wall** so later edits cannot change it (Floor Plan Creator: "input and lock the length").
9. **Instant 3D from the plan** (magicplan reviewers).
10. **Multi-level undo/redo and automatic saving** (magicplan help; absence is the top complaint elsewhere).
11. **Drawing with a finger on a phone feels direct** (Floorplans iPad review, DrawPlan).

## 2. What people say they hate

1. **Snapping that fights you** — "it will snap to a point that messes up the room dimension" (Home Design 3D);
   "snapping (on or off) makes a wall jump to a certain point… I can't make sense of why" (Floorplanner board).
2. **No fine nudge** — "bring back the nudge" (DesignFiles feedback board).
3. **Editing one wall drags others unexpectedly** — "moving or modifying a wall would take other walls with it"
   (Planner 5D, Capterra).
4. **Walls go crooked or come apart** — "straight walls have a curve when they do not", "wall parts… dislodged"
   (Floorplanner, Trustpilot); corners that "wouldn't align" (Planner 5D).
5. **Near-miss angles** — dragging leaves "90.1124984° angled wall nonsense" (Chief Architect/Home Designer forum).
6. **Awkward measurement entry** — plan in metres but edits demanded in cm (Floorplanner); typed formats rejected.
7. **Doors and windows "skip around once added to a wall"** (Planner 5D).
8. **No undo** — users restart projects after one accidental touch (Planit2d reviews).
9. **Crashes and lost work** — "lost hundreds of hours" (Floorplans Pro reviews).
10. **Hidden features and learning curve** — "not immediately obvious" (RoomSketcher reviewers).
11. **Paywalls, subscriptions, ads, watermarks** (magicplan, Planner 5D, Floor Plan Creator reviews).
12. **Cramped phone editing** — controls covering the drawing, tiny targets, hard to move rooms on mobile
    (Floorplanner Trustpilot).

## 3. What the good builders have in common

- A **canvas that fills the screen** with a light grid; the room drawn with **thick walls and length labels** on
  every wall, plus **area**.
- **Direct manipulation**: corner handles and wall drags, pinch-zoom/pan, and a "fit" control.
- **Selection → contextual panel** (bottom sheet on phones) showing the selected object's exact values.
- **Tap-to-edit dimensions** with a numeric keypad, as the precision path; drawing is the rough path.
- **Snap toggle (magnet)** with **visible guide lines** and on-screen length/angle while dragging.
- **Undo/redo always visible** in the top bar; **autosave**.
- **Starting templates** (rectangle, L) and a **rectangle-size shortcut**.
- **Doors/windows attach to a wall** and slide along it.

## 4. Persona walk-through of the previous OWL builder

1. Build manually → 12' × 10' planning rectangle. Good start (theme 1).
2. Tap the bottom length, type `15' 1/8"`. **The far corner moved alone, so the right wall tilted and the room became a
   trapezoid** (hate 3/4).
3. Type `11 6 1/4` for the side — **rejected: "Add units"** (hate 6).
4. A rough corner drag could leave corners at 89.6° (hate 5); the room could grow past the screen edge after typing,
   making a corner unreachable (hate 12).

## 5. What changed (implemented, tested)

| Change | Answers | Where |
|---|---|---|
| **Room size: Length × Width** card for rectangular rooms — the two tape numbers are the whole job; opposite walls follow. | Love 1, 2, 7 | `PlanEditor.RoomPanel` |
| **Keep corners square** (default): an exact wall length slides the next wall along, so every angle is preserved. Toggle to "far corner only". | Hate 3, 4 | `Edits.setWallLength(…, LengthMode)` |
| **Tape shorthand** `11 6 1/4` = 11' 6 1/4"; all earlier formats still work. | Hate 6 | `LengthParser` |
| **Live interpretation** while typing: `= 11' 6 1/4"  (3.512 m)` or the exact problem. Field opens with the old value selected so typing replaces it. | Hate 6, 10 | `LengthFormat.interpretation`, `InlineField`, `LengthField` |
| **Walk-around entry**: tap a length on the plan, type, **Next wall →** opens the following wall. List of all walls for non-rectangular rooms. | Love 2, 7 | `InlineField`, `RoomPanel` |
| **Predictable snapping**: in the room's own axes; a corner locks to an exact square corner when both walls are within 4°; otherwise one wall locks to 0/45/90° and its length steps by ½" (1 cm); otherwise corner alignment. Screen-space tolerance (same feel at any zoom). Typed values never snap. | Love 5, hate 1, 5 | `domain/edit/Snap.kt` |
| **Snap feedback**: guide lines extended past the corner, a **haptic tick only when a snap engages**, and a "square ✓ / on angle / aligned" note. | Love 5, hate 1 | `PlanEditor`, `PlanCanvas` |
| **Live readout above the canvas** (never under the finger): both attached wall lengths and the corner angle while dragging; distance moved while dragging a wall; left/width/right for openings. | Love 5, hate 12 | drag HUD |
| **Corner angle marks**: a square glyph at exact 90° corners, the angle in degrees elsewhere. | Love 6 | `drawCornerMarks` |
| **Make 90°** button on a selected corner. | Love 6 | `Edits.squareCorner` |
| **Nudge steps** 1/16", 1/4", 1", 1' (1 mm, 5 mm, 1 cm, 10 cm) for corners and walls. | Hate 2 | `stepPicker` |
| **View follows typed changes**: if a typed edit pushes a corner off-screen the view refits (never during a drag). | Hate 12 | `PlanEditor` |
| Compact toolbar (area · perimeter, Snap on/off, + − Fit Rotate) outside the canvas; shapes tucked behind "Start over from a shape…". | Hate 10, 12 | `PlanEditor` |

Kept from before: select-first walls, direct corner drags, one undo step per gesture, autosave with failure recovery,
openings bound to walls with clamped drags, no ads/paywall/sign-in.

## 6. Evidence

- Domain (JVM): `BuilderUxTest` — persona dimensions give an exact rectangle (both opposite walls, area); L-room angles
  preserved; far-corner mode; locks; Make 90°; near-miss drag lands on the exact corner; rotated-room snapping;
  length stepping; shorthand parsing and interpretation text.
- Rendered (Robolectric JVM): `personaTypesTwoTapeMeasurementsAndRoomStaysSquare` — types `15' 1/8"` and `11 6 1/4`
  into Room size, checks walls 3 and 4 read `15' 0 1/8"` / `11' 6 1/4"`, a near-miss corner drag leaves the room
  exactly square with no extra history step, and Next wall → walks to wall 2.
- Not yet verified on a phone: haptic feel, snap tolerance tuning (14 dp, 4°) and finger comfort need the OnePlus Open.

## Sources

- magicplan: [Capterra reviews](https://www.capterra.com/p/210335/magicplan/reviews/), [Clockshark review](https://www.clockshark.com/Blog/magicplan-app-review), [Define corners help](https://help.magicplan.app/create-a-room-with-the-define-corners-feature), [Creating rooms](https://help.magicplan.app/creating-rooms-in-magicplan), [Android floor plans](https://help.magicplan.app/creating-floor-plans-in-magicplan-on-your-android-device), [Undo help](https://help.magicplan.app/undo), [Gigazine test](https://www.gigazine.net/gsc_news/en/20190115-magicplan), [G2 review](https://www.g2.com/products/magicplan/reviews/magicplan-review-468038)
- RoomSketcher: [Trustpilot](https://au.trustpilot.com/review/roomsketcher.com?page=9), [First steps](https://help.roomsketcher.com/hc/en-us/articles/360000202509), [G2](https://g2.com/products/roomsketcher/reviews?page=3)
- Floorplanner: [Trustpilot](https://at.trustpilot.com/review/floorplanner.com), [TechRadar](https://www.techradar.com/reviews/floorplanner), [G2](https://www.g2.com/products/floorplanner/reviews), [Capterra UK](https://www.capterra.co.uk/reviews/164017/floorplanner)
- Planner 5D: [Capterra p.5](https://www.capterra.com/p/164022/Planner-5D/reviews/?page=5), [Capterra p.2](https://www.capterra.com/p/164022/Planner-5D/reviews/?page=2), [TechRadar](https://www.techradar.com/reviews/planner-5d)
- Snapping complaints: [Home Design 3D (Steam)](https://steamcommunity.com/app/420000/discussions/0/1630790506927080867), [DesignFiles "bring back the nudge"](https://designfiles.featurebase.app/p/new-snapping-feature-on-floor-plan-bring-back-the-nudge), [Home Designer crooked walls](https://homedesignersoftware.com/support/article/KB-00311/correcting-crooked-walls-or-lines.html), [Chief Architect forum](https://hometalk.chiefarchitect.com/topic/2194-free-rotating-walls/)
- Snapping patterns: [Measure right-angle rooms](https://support.measureflooring.com/hc/en-us/articles/1500006479061), [Planoplan snaplines](https://planoplan.com/en/help-center/documentation/project-creation/snapline), [Leica DISTO Plan sketch](https://shop.leica-geosystems.com/sites/default/files/2024-04/02_DISTO%20Plan%20App%20-%20Sketch%20Plan_EN.pdf)
- Other: [Floor Plan Creator (Softpedia)](https://mobile.softpedia.com/android/Floor-Plan-Creator-Android-Review-854.html), [BiggerPockets](https://biggerpockets.com/posts/user/myersb1), [Planit2d reviews](https://worldsapps.com/reviews-planit2d), [Floorplans Pro reviews](https://justuseapp.com/en/app/445305220/floorplans-pro/reviews), [Floorplans iPad (iMore)](https://www.imore.com/floorplans-ipad-review), [Moasure docs](https://docs.moasure.com/en/moasure-lx1/your-moasure-app/viewing-your-floor-plan), [Best Android floor plan apps](https://bestforandroid.com/floor-plan-creator-apps.md)
