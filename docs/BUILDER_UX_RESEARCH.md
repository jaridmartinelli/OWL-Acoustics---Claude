# 2D Room Builder: Mobile-Only UX Research and Redesign (rev. 2, 2026-10-26)

This replaces the first revision, which mixed desktop and web tools (Floorplanner web, Capterra/Trustpilot/G2
reviews, Home Design 3D on Steam, Chief Architect forums). This revision uses **only reviews posted on the
Apple App Store and Google Play for phone and tablet apps**.

## Method and limits

- About 30 web searches restricted to `apps.apple.com` and `play.google.com`, covering 30+ mobile apps.
- Direct page downloads from the App Store, Google Play and review aggregators are blocked by this environment's
  network policy. The review text below therefore comes from the search engine's extracts of those store pages.
  Quotes are as the search returned them. Store ratings are snapshots and change over time.
- Excluded: Capterra, G2, Trustpilot, Steam, vendor help pages, and Mac App Store (`mt=12`) listings.
- Apps covered: magicplan (iOS/Android), Floor Plan Creator (Android), Planner 5D (iOS/Android), RoomSketcher
  (iPad/Android tablets), 4Plan, DrawPlan, Floor Plan App (Laan Labs), Planit2d, Live Home 3D (iOS), Home Design 3D
  (iOS/Android), Home Planner AI, Homestyler, HomeByMe, Floor Plan 3D, RoomSketch3D, RoomPlot, RenoPlan, Madory,
  PlankPlanner, Rooms – Easy Layouts, MeasureSquare Mobile, Moasure, MeasureOn (Bosch), AR Plan 3D, CoreLogic Floor
  Plan, RoomScan Pro / Classic, 5DPlan, iMeasure, Smart Home Design, Floor Planner: Home Design.

## 1. What mobile users enjoy

1. **Typing exact wall lengths.** Live Home 3D (iOS): "the ability to precisely adjust wall lengths, by entering
   values makes things easier". Home Design 3D's App Store notes added "manually enter the exact size of your walls".
2. **Live dimensions while dragging.** 4Plan: "smooth to create precise and to-scale walls… inside and outside wall
   dimensions were displayed by the inch" while lengthening or shortening. Planit2d: "realtime dimension read-out".
3. **Starting a box from two numbers.** MeasureSquare Mobile's developer points users to "Room by Size", entering two
   dimensions. Rooms – Easy Layouts: "size a room… to precise measurements".
4. **Speed for a simple plan.** RenoPlan: a basic plan done "in a few minutes with very little practice". magicplan
   (iOS): a basic room "in under a minute". RoomScan Classic: "effortless… loved it".
5. **Laser meter input.** Floor Plan Creator and magicplan (Bosch, Leica Disto, Hilti, Stabila); RenoPlan: "laser
   and AR measuring made it easy".
6. **Locking measured walls.** Floor Plan Creator: "lock length and position of walls… to prevent unwanted changes".
   A Planner 5D (iOS) reviewer was frustrated without wall-locking.
7. **Fractional inches.** PlankPlanner added mixed fractions and 1/8" input after users asked to type inches.
8. **Simple, intuitive tools.** RoomPlot: "the first app… intuitive and easy to use"; Floor Planner (Android):
   "very easy to use"; Planner 5D: "about ten minutes to get the hang of".
9. **Measurements always visible.** Rooms – Easy Layouts reviewer: wished measurements were "visible without
   pressing on them" (praise for apps that do this).
10. **Undo arriving as a fix.** Planit2d reviewers credited the 2024 undo/redo update.

## 2. What mobile users hate

1. **Can't hit an exact length by dragging.** RoomSketcher (tablet): "instead of typing in '11 feet' the best I could
   get was 10' 10.8"". magicplan (Android): wanted an 8-ft wall but "could only measure 7 ft or 9 ft".
2. **No way to type a length at all.** 4Plan: "could not type in how long a wall is… the software sometimes changed
   their numbers". DrawPlan: "no way to set accurate scaled measurements on walls". Planit2d: asks to type
   dimensions, "sometimes you have to literally move a room out of the house to fix the size". Floor Plan App: camera
   is "the only way to input info".
3. **Rounding that lies.** Floor Plan App: "a 6'7" wall displayed as 7'".
4. **One edit moves other walls.** Planner 5D (iOS): "changing length input of the walls scales the whole thing from
   both ends, and affects all other measure"; without locks "every small edit changed other dimensions". Floor Plan 3D:
   moving a door made walls "freak out and extend". magicplan: after zooming out, "walls and dimensions of adjacent
   rooms were all wrong".
5. **Snapping that fights you.** Floor Plan Creator: walls "do not snap together without the details and layout
   changing… very frustrating with the 'snap to' default". Live Home 3D: "snapping is a little finicky", walls snap and
   disappear. Home Planner AI: walls move and snap in frustrating ways.
6. **Fine moves impossible with a finger.** Live Home 3D: "when trying to move an item a tiny bit, it moves A LOT";
   iPad users ask for a nudge control. Moasure: "editing points and lines on a 6 in screen with big fingers is very
   troublesome". Planner 5D: small items expand instead of moving when dragged.
7. **Accidental edits with no undo.** Floor Plan 3D: no undo, "had to restart"; another lost two hours. AR Plan 3D:
   "click in the wrong spot, and it moves walls". Planit2d: accidental moves took 5–10 minutes to fix. CoreLogic: undo
   only for the last action.
8. **Can't tell whether corners are square.** PlankPlanner: "no clear way to verify… corners are exactly 90 degrees";
   the grid is "hard to see".
9. **Lost work and crashes.** Floor Plan App: crashes, no auto-save. magicplan (Android): non-rectangular rooms
   "freeze… and lose my progress". HomeByMe: retry wiped hours of work.
10. **Paywalls on basics.** Home Planner AI: setting exact room measurements requires payment. DrawPlan: saved plans
    locked behind a subscription. iMeasure: Pro needed before measuring. Floor Plan Creator: export moved behind a
    paywall. Units change paywalled in another app.
11. **Unclear controls and tap problems.** Smart Home Design: "you don't know what each button represents til you
    click on it"; sizing buttons "would also move the map". iMeasure: unresponsive corner buttons.
12. **Navigation fights editing.** 5DPlan: pinch-zoom "VERY sensitive". Home Planner AI: moving around "seems to zoom
    in". Homestyler: auto-zoom cuts off corners.
13. **Phones not supported or cramped.** RoomSketcher: phones unsupported ("only tablets"). Live Home 3D: "a lot to get
    basic features to function… the limitation of using a phone screen".

## 3. What the mobile builders have in common

- A full-screen canvas with a grid; walls drawn thick, with length labels on walls.
- Tap to select; drag corners or walls; pinch to zoom, one finger to pan.
- A bottom bar or panel for the selected item's properties; the best ones allow typed values.
- A snap or magnet setting; the better ones expose snap distance or wall locks.
- Templates or a "room by size" box as the fastest start.
- Doors and windows attached to walls.
- Undo/redo at the top (often added late, after complaints).
- Frequent pain points: phone-sized screens, fine adjustment, and scrolling property panels.

## 4. How OWL's builder maps to these findings

| Mobile finding | OWL builder now |
|---|---|
| Can't type exact lengths / drag can't hit 11' | Every wall length, opening left/width/right and height can be typed; drags are only the rough path |
| Rounding lies (6'7" → 7') | Display to 1/16"; the exact typed text is shown back unchanged |
| One edit moves other walls | "Keep corners square" slides only the next wall; option to move only the far corner; lockable corners |
| Snap fights you | Predictable room-axis snapping, exact corners, visible guides, haptic tick on engage, one-tap Snap off; typed values never snap |
| Fine moves impossible | Nudge pad with step menu (1/16" to 1'); fine-gain window gestures; zoom |
| Big fingers on 6" screen | 22 dp touch radius, readout shown above the canvas (not under the finger), select-first walls |
| Accidental edits, no undo | Visible Undo/Redo, one step per gesture, persistent history, autosave |
| Can't tell if corners are square | Square glyph at exact 90° corners, angle labels elsewhere, "Make 90°" |
| Grid hard to see | Clearer grid with a stronger line every 5 ft (1 m) |
| Two-number room / room by size | Room size: Length × Width |
| Fractions | `11 6 1/4`, `11' 6 1/4"`, decimals, metric; live "= 11' 6 1/4" (3.512 m)" preview |
| Paywalls | None; no sign-in, no ads |
| Unclear buttons | Text-labelled actions |
| Scrolling/cramped phone panels | **No scrolling anywhere in the builder** (this revision; see below) |

## 5. Changes in this revision: a builder with no scrolling

- **Step bar replaces the scrolling tab strip.** It shows "3/7 · Plan ▾" (a menu of all steps) plus a one-tap
  "Ceiling & 3D →".
- **Fixed toolbar:** area and perimeter, scale basis, Snap, Fit, and a ⋮ menu. The menu holds zoom in/out, rotate
  view, units, "Start over" shapes, and Finalize.
- **The canvas takes all remaining height.** The contextual panel sizes to its content and never scrolls. Rows wrap
  instead of scrolling sideways.
- **Room panel:** Room size (Length × Width) for rectangles. A **wall pager** ("◀ Wall 3: 15' 0 1/8" ▶ Edit") shows
  one wall at a time, so any number of walls fits, and it doubles as list-free accessible selection. Plan status sits
  on one line with Confirm, and the review notes open in a Details dialog.
- **Wall panel:** length field and Square switch in one row. Actions (Drag wall, Add corner, Alcove, + Window,
  + Door, this wall's openings) wrap in one block. A step menu with Move in / Move out sits below.
- **Corner panel:** angle and Make 90° on one row; a nudge pad with step menu; Lock and Remove.
- **Opening panel:** Swing and Remove in the title row, then Left / Width / Right, then Height / Sill (or Bottom).
- **Clearer grid** with major lines.

Evidence: `planScreenHasNoScrollingAndControlsFitSmallPhone` renders the builder at **360 × 640 dp**. It asserts
there is no scrollable node in the room, wall and opening panels, and that every control is displayed. The full suite
is 105 tests (98 domain + 7 rendered UI), all passing; the UI suite passed three consecutive reruns. These are
JVM-rendered (Robolectric) checks, not a phone.

## 6. Not done: needs your decision

- **Bluetooth laser-meter input** is loved on mobile (Floor Plan Creator, magicplan, RenoPlan) but is not in the
  approved brief. It would be new scope.
- **Inside vs outside wall dimensions** (4Plan, Planit2d). OWL models the interior room boundary only, which is
  what acoustics needs. Wall thickness is not modelled.

## Sources (App Store / Google Play only)

magicplan [App Store reviews](https://apps.apple.com/us/app/magicplan/id427424432?see-all=reviews), [Google Play](https://play.google.com/store/apps/details?id=com.sensopia.magicplan&hl=en_US) ·
Floor Plan Creator [Google Play](https://play.google.com/store/apps/details?id=pl.planmieszkania.android&hl=en_US) ·
Planner 5D [App Store reviews](https://apps.apple.com/au/app/planner-5d-home-design-app/id606173978?see-all=reviews&platform=iphone), [Google Play](https://play.google.com/store/apps/details?id=com.planner5d.planner5d&hl=en_GB) ·
RoomSketcher [App Store](https://apps.apple.com/us/app/roomsketcher/id1082324789?see-all=reviews), [Google Play](https://play.google.com/store/apps/details?id=com.roomsketcher.homedesigner&hl=en_US) ·
[4Plan](https://apps.apple.com/us/app/4plan-home-interior-planner/id1570446253) ·
[DrawPlan](https://apps.apple.com/us/app/drawplan/id1451755778) ·
[Floor Plan App](https://apps.apple.com/us/app/floor-plan-app/id1350168237?see-all=reviews&platform=iphone) ·
[Planit2d](https://apps.apple.com/us/app/planit2d/id615328054) ·
[Live Home 3D](https://apps.apple.com/us/app/live-home-3d-house-design/id1066784070?see-all=reviews&platform=ipad) ·
Home Design 3D [App Store](https://apps.apple.com/us/app/home-design-3d-draw-plan/id463768717), [Google Play](https://play.google.com/store/apps/details?id=fr.anuman.HomeDesign3D&hl=en) ·
Home Planner AI [App Store](https://apps.apple.com/us/app/home-planner-ai-room-design-3d/id1076159017?see-all=reviews), [Google Play](https://play.google.com/store/apps/details?id=com.icandesignapp.all&hl=en) ·
Homestyler [App Store](https://apps.apple.com/us/app/homestyler-ai-room-planner/id601137449?see-all=reviews&platform=ipad), [Google Play](https://play.google.com/store/apps/details?id=com.autodesk.homestyler&hl=en) ·
[HomeByMe](https://apps.apple.com/us/app/homebyme-house-planner-3d/id1465715066?see-all=reviews&platform=ipad) ·
[Floor Plan 3D](https://apps.apple.com/us/app/floor-plan-3d-smart3dplanner/id1513694926?see-all=reviews&platform=iphone) ·
[RoomSketch3D](https://apps.apple.com/us/app/roomsketch3d-floor-plan-maker/id6759799284?see-all=reviews&platform=iphone) ·
[RoomPlot](https://apps.apple.com/us/app/floor-plan-creator-roomplot/id6744899616) ·
[RenoPlan](https://apps.apple.com/us/app/renoplan-floor-plan-creator/id1470036992?see-all=reviews&platform=ipad) ·
[Madory](https://apps.apple.com/in/app/house-design-planner-madory/id1626956309) ·
[PlankPlanner](https://apps.apple.com/us/app/plankplanner/id6760696463) ·
[Rooms – Easy Layouts](https://apps.apple.com/us/app/rooms-easy-layouts/id948103171?see-all=reviews&platform=iphone) ·
MeasureSquare Mobile [App Store](https://apps.apple.com/au/app/measuresquare-mobile/id845027391), [Google Play](https://play.google.com/store/apps/details?id=com.measuresquare.m2droid&hl=en&gl=US) ·
Moasure [App Store](https://apps.apple.com/us/app/moasure/id1561726410?see-all=reviews&platform=ipad), [Google Play](https://play.google.com/store/apps/details?id=com.moasure.moasureapp&hl=en_US) ·
[MeasureOn](https://play.google.com/store/apps/details?id=com.bosch.ptmt.measrOn&hl=en_US) ·
AR Plan 3D [App Store](https://apps.apple.com/us/app/ar-plan-3d-camera-to-plan/id1459846158), [Google Play](https://play.google.com/store/apps/details?id=com.grymala.arplan&hl=en_US) ·
[CoreLogic Floor Plan](https://apps.apple.com/us/app/corelogic-floor-plan/id1371346094) ·
[RoomScan Pro](https://apps.apple.com/us/app/roomscan-pro-lidar-floor-plans/id1504050801?see-all=reviews&platform=iphone), [RoomScan Classic](https://apps.apple.com/us/app/roomscan-pro-the-app-that-draws-floor-plans-by-itself/id673673795) ·
[5DPlan](https://apps.apple.com/us/app/room-scanner-5dplan/id6474450135) ·
[iMeasure](https://apps.apple.com/us/app/imeasure-floor-plan/id1281387513) ·
[Smart Home Design](https://play.google.com/store/apps/details?id=com.SoftwareentwicklungKemper.SmartHomeDesign&hl=en_US) ·
[Floor Planner: Home Design](https://play.google.com/store/apps/details?id=com.floorplanner.homedesign&hl=en_US)
