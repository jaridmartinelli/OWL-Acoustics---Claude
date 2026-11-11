# OWL Acoustics — Progress and Readiness Dashboard

Last updated: 2026-11-10 (Capture Lab section) · main app sections as of 2026-10-26 · Branch `claude/new-session-7qrh7s` · Revision: see the commit that contains this file
(artifact identity below is for that revision's debug APK).

## Readiness

| Gate (brief 06) | State |
|---|---|
| Experimental pilot | **Available, labeled experimental.** The manual and assisted-import local workflow runs end to end in JVM-rendered tests. It has not yet been run on an emulator or a phone. Automatic video reconstruction, server analysis and purchases are **not** available. |
| Software-ready for final user/device checks | **No.** Required implementation is missing (on-device observation provider, server route, billing/admin) and runtime checks are owed. |
| Full production acceptance | **No.** |

Acceptance outcomes (70 coverage rows, [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)): **PASS 26 · FAIL 8 · UNVERIFIED 23 · BLOCKED 13**.

## Feature status

Status words follow brief 02: PLANNED, PARTIAL, IMPLEMENTED, BUILD-TESTED, RUNTIME-TESTED, DEVICE-VERIFIED,
BLOCKED, EXCLUDED-DEFERRED. "BUILD-TESTED" here means JVM unit tests and/or Robolectric JVM-rendered UI tests
passed; nothing is RUNTIME-TESTED (no emulator) or DEVICE-VERIFIED.

| Area | Status | Notes |
|---|---|---|
| Project, records, reproducible build | BUILD-TESTED | Gradle wrapper, pinned versions, lint 0 errors. |
| Exact units and numeric entry | BUILD-TESTED | Rational storage, ft-in fractions, metric, unknown ≠ 0. |
| Canonical model, validation, provenance | BUILD-TESTED | Original observations stored outside undo (`evidence.json`). |
| Manual creation + starting shapes | BUILD-TESTED | 12 × 10 ft planning rectangle, L, alcove, angled. |
| Canvas editor (select-first walls, corners, split, alcove, angle, inline values, snap, pan/zoom/rotate) | BUILD-TESTED | Redesigned 2026-10-26 from mobile-only App Store/Google Play review research (docs/BUILDER_UX_RESEARCH.md rev. 2); builder has no scrolling (step bar, fixed toolbar + overflow, wall pager, wrapping panels); Room size card, keep-corners-square lengths, tape shorthand, live interpretation, walk-around Next, snapping engine with guides/haptics, drag readout, corner marks, Make 90°, nudge steps, auto-refit. Device tuning owed. |
| Windows/doors (anchors, hybrid gestures, swing tap, vertical data) | BUILD-TESTED | |
| Durable history, autosave, failure recovery, room isolation, job generations | BUILD-TESTED | JVM fault injection; Android disk behaviour owed. |
| Room management (create/open/rename/confirmed delete) | BUILD-TESTED | |
| Ceilings (7 shapes, 3 details) + shared 3D + 3D→2D correction | BUILD-TESTED | Curved/sloped-transition surfaces excluded from reflections (documented). |
| Purpose/setup (10 profiles, arrangements, locks, keep-clear) | BUILD-TESTED | |
| Free placement optimisation | BUILD-TESTED | Geometric criteria only (C-17); subwoofer placement not optimised. |
| Reflections, SBIR, rectangular modes, capability table | BUILD-TESTED | |
| Recommendations (buy nothing, item limit, full, exclusions) | PARTIAL | Quantitative benefit and money budget BLOCKED (B-02, B-03). |
| Catalog geometry (incl. A/B diffuser from drawing) | IMPLEMENTED | No coefficients or prices (by design until supplied). |
| Video capture (silent, private, 60 s, delete) | IMPLEMENTED | Not run on hardware (BLOCKED: no device/emulator). |
| On-device observation provider / guidance overlay / targeted recapture | PLANNED | No component selected/validated yet (C-21). No stub is presented as a scanner. |
| Optional AR/depth | PLANNED | Not required by the main flow. |
| Server-assisted analysis | PARTIAL | Contract + conservative import tested; client transport and backend BLOCKED (B-07). |
| Floor-plan import (image/PDF, page, calibration, conflicts, recalibration, tracing, source deletion) | IMPLEMENTED | Domain BUILD-TESTED; screen not yet exercised in rendered tests. |
| Expert services / purchases / admin | PARTIAL | State machine and immutable terms tested with fake verification; live provider/admin BLOCKED (B-04, B-05). |
| Backup/transfer exclusions, permissions | IMPLEMENTED | Manifest verified in the APK; OS behaviour test owed. |
| Accessibility (semantics, list/step alternatives, non-colour cues) | IMPLEMENTED | TalkBack/large-text verification owed. |
| Final logo | BLOCKED | B-01: provisional text wordmark and "DEV" launcher glyph are labeled provisional. |

## Evidence for this revision

- Tests: **105 executed, 0 failed — 98 domain JVM tests + 7 Robolectric JVM-rendered UI tests (incl. persona builder test and a no-scroll check at 360×640 dp); UI suite passed 3 consecutive reruns.**
- Lint (`:app:lintDebug`): 0 errors, 1 warning (`ObsoleteSdkInt` on `mipmap-anydpi-v26`; renaming the folder made AAPT
  fail to find the adaptive icon, so the folder is kept — E-003), 1 hint (monochrome icon present).
- Clean-clone rebuild (F-04): **PASS — fresh clone of revision 7fc608b with an empty Gradle home ran tests, lint and assembleDebug successfully. APK bytes differ between builds (not configured for byte-identical output).**
- F-37 long-history workload (JVM host, not a device): 1,750 committed edits, 12 vertices, 6 openings; median commit
  1.29 ms, p95 5.99 ms, reopen 282 ms (latest run; earlier run 1.26 / 2.02 / 520 ms), ~2.8 MB on disk. No history cap.
- APK: `app/build/outputs/apk/debug/app-debug.apk` · package `com.owlacoustics.roomplanner.dev` · versionCode 1 ·
  versionName 0.1.0-dev · min/target SDK 26/36 · permissions: CAMERA only · `allowBackup=false` with
  data-extraction and legacy full-backup exclusions · signer: Android Debug (this build machine) ·
  signer certificate SHA-256 `a574fd584735a1669087c1a0f5541b91f512f6d3def2243313324d929ffc8fb3` · APK SHA-256 `0b97c6a91fb4ec4b2de4c83e2a0e3c65ad091d291bce0c55f0416fdf55e1f0df` (built from the committed tree of this revision; rebuilding gives different bytes).
- APK scan: no RECORD_AUDIO, no brief/records files, no API keys, no supplier identities.

## Incidents (consequential, logged once)

| ID | What happened | Cause | Fix / prevention | Verified by |
|---|---|---|---|---|
| I-01 | New edit after undo could reuse the discarded entry's sequence number, making the store treat a new branch as an append. | Sequence derived from kept entries only. | Sequence = max over all entries + 1. | PersistenceTest branch tests |
| I-02 | A failed head write after an append left an extra history line that later appends would misalign. | Store relied on line counts only. | Head records committed byte length; appends truncate stale bytes first; load repairs. | `failedHeadRenameThenSuccessfulCommitStaysConsistent`, `interruptedAppendBytesAreIgnoredAndRepaired` |
| I-03 | `3/4"` parsed as 3 then failed. | Regex alternation tried whole numbers before bare fractions. | Fraction alternative first. | UnitsTest |
| I-04 | Retrying an already submitted service request was rejected instead of being idempotent. | State check ran before the idempotency check. | Check existing request first. | ServicesCaptureImportTest |
| I-05 | Zoom/snap overlay covered the room's top-right corner, stealing corner drags. | Controls overlaid on the canvas. | Moved to a toolbar strip outside the canvas. | EditorUiTest corner drag |
| I-06 | A centred window's touch band overlapped the wall's length label. | Label offset 16 dp inside the 22 dp opening band; labels hit-tested first. | Labels at 32 dp; openings hit-tested before labels. | EditorUiTest window drag |
| I-08 | Listing rooms while another room session was committing could truncate the in-flight history line and corrupt the room. | `RoomStore.load()` "repaired" trailing bytes. | Loading is read-only; repair happens at the next append via the committed byte length; open rooms are listed from their live session; one retry if an epoch switch races a read. | `concurrentListingDuringCommitsNeverCorruptsHistory` |
| I-09 | For the few ms of a write, the state already showed the new edit with no pending flag, so the UI could say "Saved" before bytes were durable. | Attempted state published before the write without a marker. | `writing` flag; `saved` requires no failed and no in-progress write; Undo/Redo wait for it. | PersistenceTest, EditorUiTest |
| I-10 | Typing one real wall length tilted the next wall (rectangle → trapezoid); `11 6 1/4` was rejected; a typed edit could push corners off-screen. | Far-corner-only length rule; strict parser; fixed view. | E-006, E-008, E-009. | BuilderUxTest, persona UI test |
| I-07 | Text fields inside `AlertDialog` never reach idle under Robolectric. | Test-environment behaviour (dialogs without text fields work). | Room naming and rename are inline (no modal), which also suits the brief. | EditorUiTest |

Environment notes: Maven Central HTTP 429 through shared egress (E-001, mirror added); no `/dev/kvm`, so no
emulator (E-002).

## Capture Lab (separate test app, `:lab`)

Not part of the main app's acceptance rows. It exists so the owner can compare camera capture methods on a phone.

| Item | Status | Notes |
|---|---|---|
| Methods 1, 2, 4, 5, 6, 7 and the tape-measure scorecard | IMPLEMENTED | Maths BUILD-TESTED on the JVM; screens never run on a device. |
| Method 8 · Camera scan (2026-11-11, E-013; replaces the 0.2.0 tracing screen) | IMPLEMENTED | Live automatic floor/wall/ceiling detection, Add/Subtract brush, anchors, number pad, 3D model, widest lens. Geometry model BUILD-TESTED (JVM); the screen, detection, lens choice and brush have **no rendered tests and have not been verified on a phone**. Lab 0.3.0. |
| Method 8 · Walk mode (ARCore tracking) | PLANNED | Shown disabled in the test switches. Solver accepts tracked positions (unit-tested); the ARCore camera feed is not written. |
| Method 8 · door/window heights | BLOCKED | Owner decision U-06 not answered. |
| Method 8 · hand-off to the main app's 2D builder | PLANNED | The lab saves to its own scorecard only. |
| Method 3 · server video analysis | EXCLUDED-DEFERRED | Paused by the owner. |

Lab artifact (2026-11-11): `dist/OWL-Capture-Lab-0.3.0-lab-debug.apk` (not committed) · package
`com.owlacoustics.capturelab.dev` · versionCode 3 · versionName 0.3.0-lab · arm64-v8a · debug-signed (same key as before) ·
APK SHA-256 `8c1c754689055cf4ee88964763d9022b656a32fed41cb57667120aaeab1adcab` (rebuilding gives different bytes). Built from the revision that contains this file.
Checks at that revision: domain tests passing, lab lint 0 errors; the lab has no rendered tests.

Simulated figures for method 8 (not device measurements): with 0.3° of tracing error per point, the worst wall after
one typed wall was within 2.3 in in 95% of 400 simulated runs (largest 4.1 in). With only the assumed 8 ft ceiling the
result is off by the ratio of the real ceiling to 8 ft (a 9 ft ceiling reads every wall about 10% short). Ignoring the
distance between the phone and the turning point shrinks the room by about twice that distance.

## Blockers and exact missing inputs

| ID | Missing input | Blocks | Owner | Independent work continuing |
|---|---|---|---|---|
| B-01 | Final approved OWL Acoustics logo file; exact diffuser per-step profile if ever needed | Final branding | Owner | Provisional labeled wordmark |
| B-02 | Validated product-specific absorption/diffusion data with mounting context | Quantitative treatment benefit/quantity | Owner | Geometric locations, physical fit |
| B-03 | Retail prices and currency | Money budget, cost totals | Owner | Item-count budget |
| B-04 | Service package names, inclusions, terms, prices, fulfilment operation, publication approval | Expert-service offers | Owner | State machine, request binding |
| B-05 | Billing provider/route decision per current official distribution rules; sandbox credentials; admin owner | Checkout, verification, admin | Owner | Fake-verifier tests |
| B-06 | Definition of any additional personal consultation | That category | Owner | Kept unavailable |
| B-07 | AI service URL, user auth flow, backend credentials/model config, consent text, retention policy, cost/rate limits | Server-assisted capture analysis | Owner | Contract + conservative import |
| B-08 | Any justified quantitative purpose targets | Target-dependent scoring | Owner/evidence | Geometric criteria only |
| B-09 | Release namespace, owner signing key, distribution route | Publication | Owner | Dev APK |
| B-10 | Representative device and physical reference measurements; emulator host with KVM | Runtime/device/accuracy evidence | Owner / environment | JVM evidence |

Also needed for F-07…F-13: selection of a commercially compatible on-device tracking/segmentation component with
license, performance and representative-accuracy evidence (engineering task, not an owner decision).

## Next work (in order)

1. Run the APK on an emulator host with KVM (or an authorised device): smoke test, F-05/F-06/F-61 camera, F-32 process
   death, F-62 OnePlus Open folded/unfolded, F-63 TalkBack/large text.
2. Rendered tests for import (F-15) and storage-failure banner (F-35 UI).
3. Evaluate on-device observation components (license, accuracy on representative clips) and implement the provider,
   guidance overlay and targeted recapture (F-07…F-13, F-65).
4. With B-07: HTTPS client, consent UI, frame extraction, backend service; with B-04/B-05: provider integration and
   admin.
5. AGP 9 / newer library migration (tracked engineering item).
