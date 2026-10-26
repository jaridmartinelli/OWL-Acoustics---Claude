# OWL Acoustics — Acceptance Inventory (F-01 … F-70)

Stable case IDs from brief section 06. Outcomes: PASS / FAIL / UNVERIFIED / BLOCKED at the stated required level.
A JVM-rendered Robolectric test is *not* running-app evidence; cases needing a running app or device stay
UNVERIFIED or BLOCKED even when their JVM evidence passes. Coverage rows are not a count of executed tests
(executed tests: see PROGRESS.md).

| Case | Required level | Outcome | Evidence (this revision) | Next procedure / prerequisite |
|---|---|---|---|---|
| F-01 | Source/artifact inspection | **PASS** | New repo content: brief/, records, Gradle project, source, tests; no prior checkout used. | — |
| F-02 | Build logs | **PASS** | `./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeded (104 tests, lint 0 errors). | — |
| F-03 | Artifact inspection | **PASS** | aapt2 badging + apksigner: `com.owlacoustics.roomplanner.dev` 1 / 0.1.0-dev, debug certificate digest and APK SHA-256 in PROGRESS.md. | — |
| F-04 | Clean-environment rebuild | **PASS** | Fresh clone of 7fc608b + empty Gradle home: tests, lint and assembleDebug succeeded (4 m 49 s). Different APK bytes than the original build; byte identity is not claimed. | — |
| F-05 | Running app + device | **BLOCKED** | Permission request at point of use, denial text and manual/import fallback implemented (CaptureScreen). | Run on emulator/device: grant, deny, re-grant, cold start. Needs KVM emulator or authorised device. |
| F-06 | Running app + device | **BLOCKED** | Silent Recorder (no withAudioEnabled, no RECORD_AUDIO in merged manifest), 60 s limit via setDurationLimitMillis, app-private file, delete with tombstone, failure finalize handling. | Record/stop/limit/interrupt/delete/reopen on device; confirm no gallery copy. |
| F-07 | Representative video | **FAIL** | No real on-device observation provider is integrated (C-21). Clips are stored as evidence only; no stub is presented as a scanner. | Select a commercially compatible tracking/segmentation component, implement, validate on representative rooms. |
| F-08 | Algorithm + video | **FAIL** | Viewpoint-independence policy (dHash) exists and is unit-tested, but it is not connected to live boundary evidence. | Wire to the observation provider once it exists. |
| F-09 | Running app | **FAIL** | No camera-space overlay is drawn (so nothing decorative), but evidence-sufficiency guidance overlays are not implemented. | Implement with the provider; verify image-space correspondence on device. |
| F-10 | Video | **FAIL** | Partial topology from capture not implemented. (Manual/import paths represent L/angled/recess shapes — see F-18.) | Provider-dependent. |
| F-11 | Algorithm + video | **FAIL** | Scale basis is tracked (planning / user / import-calibrated / unknown) and planning templates are kept separate from measurements (tests). Capture metric anchors are not implemented. | Provider-dependent; physical reference tests. |
| F-12 | Video | **FAIL** | Import reference conflicts are surfaced and not applied (test); capture door/plate references are not implemented. | Provider-dependent. |
| F-13 | Video | **FAIL** | Targeted recapture/merge not implemented. | Provider-dependent. |
| F-14 | Source inspection + device | **UNVERIFIED** | No ARCore dependency anywhere; manual/import/video paths need no AR (source). Optional depth detection is not implemented (PLANNED). | Device check; implement optional depth detection if pursued. |
| F-15 | Algorithm + running app | **UNVERIFIED** | Calibration transform, conflict surfacing, undoable recalibration, unsupported-file recovery path, page selection, tracing implemented; transform/conflict/recalibration pass domain JVM tests. | Import a real image and multi-page PDF on emulator/device; unreadable file case. |
| F-16 | Configured service | **BLOCKED** | Request/response contract and conservative candidate import implemented and tested with mock JSON only (contract evidence). B-07 inputs missing. | Supply service URL, auth, backend credentials, consent text, retention and cost limits. |
| F-17 | Running app | **UNVERIFIED** | Manual and import paths produce the same RoomModel and editor (source); capture produces no geometry (F-07). | End-to-end per path on device. |
| F-18 | Algorithm | **PASS** | Rectangle, 6-corner L, alcove, angled outlines valid; crossed/folded/zero-length rejected (domain JVM tests: GeometryTest, EditsTest). | — |
| F-19 | Running app | **UNVERIFIED** | Drag on an unselected wall pans, never moves it; actions required (Robolectric JVM-rendered UI test (EditorUiTest)). | Repeat on emulator/device. |
| F-20 | Algorithm | **PASS** | Move/split/remove vertex, alcove, wall move update geometry and revalidate openings; straddling/shrinking rejected (domain JVM tests). | — |
| F-21 | Algorithm | **PASS** | Start corner anchored, locks respected, invalid entries rejected leaving the model unchanged (domain JVM tests). | — |
| F-22 | Algorithm | **PASS** | Exact rationals; mixed ft-in, fractions, zero denominators, non-finite, unknown vs 0, 1000× conversion without drift (domain JVM tests: UnitsTest). | — |
| F-23 | Algorithm | **PASS** | Shoelace area vs independent decompositions incl. rotated/irregular; live area label from the same coordinates. | — |
| F-24 | Running app | **UNVERIFIED** | Snap toggle, screen-space tolerance, alignment guides; typed values bypass snapping (source + domain tests). | Exercise across zoom levels on device. |
| F-25 | Running app | **UNVERIFIED** | Tap canvas label → inline field → exact value committed (Robolectric JVM-rendered UI test (EditorUiTest)). | Check keyboard/IME layout on device, folded/unfolded. |
| F-26 | Running app | **UNVERIFIED** | Pan/pinch/rotate/fit view-only; pinch cancels an active object preview first (source). | Device multi-touch. |
| F-27 | Running app | **UNVERIFIED** | Wall-local intent lock, fine gain, anchors and clamping pass domain JVM tests; centre move and fine symmetric widening pass Robolectric JVM-rendered UI test (EditorUiTest). | Angled-wall gestures and cancellation on device. |
| F-28 | Running app | **UNVERIFIED** | Handing cycle starts UNKNOWN, aperture unchanged (domain); tap on highlighted door cycles swing (Robolectric JVM-rendered UI test (EditorUiTest)). | Drag-vs-tap arbitration on device. |
| F-29 | Algorithm | **PASS** | left+width+right = wall length, overlap/out-of-wall rejection, touching allowed, no arbitrary gaps (domain JVM tests). | — |
| F-30 | Algorithm | **PASS** | Unknown sill/height stay null, 0 is a real value, missing heights only affect 3D apertures and some paths (domain JVM tests). | — |
| F-31 | Running app | **UNVERIFIED** | A multi-sample swipe produced exactly one history step (Robolectric JVM-rendered UI test (EditorUiTest)); domain coalescing by design. | Repeat on device. |
| F-32 | Android process restart | **UNVERIFIED** | Cursor + redo branch survive finalize/undo/new store load (domain JVM tests) and activity recreation (Robolectric JVM-rendered UI test (EditorUiTest)). | Kill process on device/emulator and reopen. |
| F-33 | Algorithm | **PASS** | New edit after undo replaces only that room's redo branch; other room untouched (domain JVM tests). | — |
| F-34 | Algorithm | **PASS** | Room-A tickets rejected by room B; late results rejected after edits, undo to same cursor, restart, close (domain JVM tests). | — |
| F-35 | Android storage | **UNVERIFIED** | Injected append/rename failures keep durable bytes, show unsaved state, block further edits, retry/discard, interrupted-append repair (domain JVM tests). | Full/unwritable storage on device; UI banner check. |
| F-36 | Algorithm | **PASS** | v1 round trips; corrupt and future-schema files reported and left byte-identical (domain JVM tests). | Add migration fixtures when the format changes. |
| F-37 | Device measurement | **UNVERIFIED** | JVM host: 1,750 edits on 12 vertices/6 openings, median commit 1.26 ms, p95 2.02 ms, reopen 520 ms, ~2.8 MB (engineering budget C-11). | Measure on target device incl. UI frame times. |
| F-38 | Running app | **UNVERIFIED** | Activity recreation keeps room/history (Robolectric JVM-rendered UI test (EditorUiTest)); finalized rooms open straight into the editor (source). | Back/background/process death on device. |
| F-39 | Running app | **UNVERIFIED** | Plan confirmed with no ceiling height, then ceiling asks for it (Robolectric JVM-rendered UI test (EditorUiTest) + domain). | Device. |
| F-40 | Algorithm | **PASS** | All 7 shapes + 3 details represented or explicit draft; no default height, symmetry or curve (domain JVM tests). | — |
| F-41 | Algorithm | **PASS** | Independent analytic checks of slope, asymmetric peak, plateau, circular/elliptical/sampled arch, tray inset on L-room, mesh areas (domain JVM tests). | — |
| F-42 | Algorithm | **PASS** | Apertures cut only with known vertical data; partial view otherwise; no fake solid without ceiling (domain JVM tests). | — |
| F-43 | Running app | **UNVERIFIED** | 3D mesh built from the same RoomModel; tap wall in 3D opens it in 2D (source). | Device check of correction loop. |
| F-44 | Running app | **UNVERIFIED** | Special ceiling saves as draft, editing free, undo works (Robolectric JVM-rendered UI test (EditorUiTest)). | Device save/exit/reopen. |
| F-45 | Running app | **UNVERIFIED** | 10 purposes, stereo default only for Mixing/Hi-Fi, podcast pairs, 5.1/7.1, custom roles (domain JVM tests). | UI on device. |
| F-46 | Algorithm | **PASS** | Locks never moved, keep-clear respected, partial locks reported, empty room asks for distance, reasons + before/after, apply = one edit (domain JVM tests; apply in Robolectric JVM-rendered UI test (EditorUiTest)). | — |
| F-47 | Algorithm | **PASS** | Rectangular modes vs analytic incl. feet; L-room, slope, unknown height unavailable; rotated rectangle eligible (domain JVM tests). | — |
| F-48 | Algorithm | **PASS** | Hand-computed wall/floor/ceiling paths and delays, door/window apertures, L-room occlusion, above-wall exclusion, SBIR first null (domain JVM tests). | — |
| F-49 | Algorithm + running app | **PASS** | Invalidation in the same commit and recompute reachable (domain JVM tests and Robolectric JVM-rendered UI test (EditorUiTest)). | Device recheck optional. |
| F-50 | Validated data | **BLOCKED** | No acoustic credit from appearance; catalog has no coefficients; existing treatment recorded without credit. | B-02 validated product data. |
| F-51 | Algorithm | **PASS** | Buy-nothing fixture: zero purchases, free placement first, no item because a catalog entry exists (domain JVM tests). | — |
| F-52 | Algorithm + config | **BLOCKED** | Item-limit and exclusions re-run from scratch (domain JVM tests); money budget unavailable without prices. | B-03 prices/currency. |
| F-53 | Source inspection | **PASS** | Labels: planning vs measured, 'benefit not quantified', SBIR/mode assumptions, no RT60/accuracy/severity (source + tests). | — |
| F-54 | Configured backend | **BLOCKED** | Package schema with publication flag; no admin/provider exists. | B-04/B-05. |
| F-55 | Configured provider | **BLOCKED** | Displayed-vs-verified amount/currency mismatch fails the order (domain). | B-05 sandbox. |
| F-56 | Configured sandbox | **BLOCKED** | Idempotent begin, duplicate callback, tampered amount, unknown/timeout, cancel, revoke pass with fake verifications (contract only). | Real provider sandbox + backend. |
| F-57 | Configured sandbox | **BLOCKED** | Immutable terms and request bound to room revision/hash pass in domain; no live flow. | B-04/B-05. |
| F-58 | Source + running app | **PASS** | No packages, prices or paid unlocks exist; expert-help dialog says not configured; special rooms stay editable. | — |
| F-59 | Artifact + device | **UNVERIFIED** | APK has no RECORD_AUDIO, no secrets, no supplier identities, no brief/records; allowBackup=false + extraction rules; media deletion tombstones. | OS backup/transfer behaviour on device (incl. manufacturer clone tools). |
| F-60 | Configured service | **BLOCKED** | Stale/malformed/wrong-room responses rejected in contract tests; no network client exists yet. | B-07. |
| F-61 | Device | **BLOCKED** | CameraX lifecycle binding implemented. | Emulator/device. |
| F-62 | Device | **BLOCKED** | Layout adapts by scrolling; JVM-rendered at 400×860 dp only. | OnePlus Open folded/unfolded. |
| F-63 | Running app + TalkBack | **UNVERIFIED** | Content descriptions on canvas/3D/undo/redo, list-based selection, step buttons, non-colour cues (ticks, ✓, text). | TalkBack, large text, contrast on device. |
| F-64 | Running app | **UNVERIFIED** | All stages exercised piecewise in Robolectric JVM-rendered UI test (EditorUiTest) (manual → edit → ceiling/3D → setup → placement → analysis → recommendations → recreate). | One continuous device run with restart. |
| F-65 | Video + import | **FAIL** | Capture does not produce geometry (F-07). Import enters the shared editor (source). | Provider + device import run. |
| F-66 | Authorised install | **BLOCKED** | Distinct dev application ID; installs alongside nothing else. | Owner authorisation for personal-device install. |
| F-67 | Records inspection | **PASS** | PROGRESS.md reconciles decisions, scope, source, evidence and artifact for this revision. | Repeat at every handoff. |
| F-68 | Artifact + records | **UNVERIFIED** | APK, source revision, build instructions, dashboard and test results delivered; usability on a device not yet observed. | Device smoke test. |
| F-69 | Records inspection | **PASS** | DECISIONS.md §3 closure table (C-01…C-33) before dependent code. | — |
| F-70 | Running app | **UNVERIFIED** | Create/open/rename/confirmed delete; deletion closes the session, removes only owned media, keeps purchase files (domain JVM tests + Robolectric JVM-rendered UI test (EditorUiTest)). | Device check. |

**Totals:** PASS 26 · FAIL 8 · UNVERIFIED 23 · BLOCKED 13 (of 70 coverage rows).
