# OWL Acoustics — Master Specification (requirements and acceptance map)

The authoritative requirement text is the supplied brief, preserved verbatim in [`brief/`](../brief/)
(checksums match `brief/Package_Manifest.json`). This file organises it into stable requirement groups,
records the data/capability contracts chosen during requirements closure, and maps each group to acceptance
cases in [ACCEPTANCE.md](ACCEPTANCE.md). It inherits the brief and cannot change scope. Closure decisions:
[DECISIONS.md §3](DECISIONS.md). Status: [PROGRESS.md](../PROGRESS.md).

## 1. Requirement groups

| ID | Requirement (summary — see brief section) | Brief | Acceptance |
|---|---|---|---|
| R-01 | Purpose: defensible model → purpose → placement → supported predictions → proportionate treatment; buying nothing is valid; no sales-driven recommendations | 03 §1, §10 | F-51, F-53 |
| R-02 | Connected journey without welcome/tutorial screens; distinct states (draft, plan reviewed, ceiling supported, setup ready, analysis current, recommendations current); plan confirmation without ceiling height | 03 §2 | F-39, F-49, F-64 |
| R-03 | Video-first capture (silent, app-private, 60 s server-route clips), correlated observations, evidence-sufficiency cues, targeted recapture, optional AR/depth, free-first modular vision, configured server-assisted OpenAI route | 03 §3.1 | F-05–F-14, F-16, F-61, F-65 |
| R-04 | Floor-plan image/PDF import: page/room selection, known-length calibration with units, preserved pixel→room transform, scale-conflict surfacing, undoable recalibration, assisted/manual labeling | 03 §3.2 | F-15, F-65 |
| R-05 | Manual creation: 12 × 10 ft planning rectangle, imperial default, starting-shape drawer, no form-first wizard | 03 §3.3 | F-17, F-18 |
| R-06 | Canonical model, provenance states, original observations kept separately, exact entered values, metres canonical, validation, unknown ≠ 0 | 03 §4 | F-11, F-17, F-21, F-22, F-30 |
| R-07 | Canvas-first editor: select-first wall actions, corners, split/alcove/angled walls, live dimensions and area, pan/zoom/rotate, inline measurement editing, snap toggle | 03 §5.1–5.2 | F-18–F-26 |
| R-08 | Openings: left + width + right, anchors, overlap/out-of-wall rejection, hybrid window gestures, door movement/rotation/width, vertical data | 03 §5.3–5.5 | F-27–F-30 |
| R-09 | Durable history: undo/redo, autosave, persistent cursor/redo across finalize/exit/restart, coalesced gestures, atomic model+history+invalidation, serialized writes, save-failure recovery, schema v1, room isolation, job generations | 03 §6 | F-31–F-38, F-49 |
| R-10 | Room management: create/open/rename/confirmed delete with defined effects | 03 §6, 08 | F-70 |
| R-11 | Guided ceilings (7 primary shapes + 3 details) with minimum input contracts, faithful shared 3D, partial labeling, 3D→2D correction | 03 §7, 08 §4 | F-39–F-44 |
| R-12 | Ten purpose profiles, relevant setup roles/cardinality, empty-room proposals, immovable constraints, no invented targets | 03 §8 | F-45, F-46 |
| R-13 | Free placement before treatment; deterministic first-order reflections; SBIR with stated assumptions; rectangular modes only for compatible model; capability table; invalidation and recompute | 03 §9 | F-46–F-49, F-53 |
| R-14 | Recommendations and product data: buy-nothing, Budget/Full re-optimisation, catalog geometry, no appearance-only credit, confidentiality | 03 §10 | F-50–F-53 |
| R-15 | Expert services and purchases: configured packages, trusted verification, idempotency, immutable terms, request bound to room revision, unpublished when unconfigured | 03 §11 | F-54–F-58 |
| R-16 | Android quality: OnePlus Open folded/unfolded, accessibility, lifecycle, privacy, backup/transfer exclusions, permissions, brand assets | 03 §12 | F-59–F-63, F-66 |
| R-17 | Build, identity, records and delivery | 02, 04 | F-01–F-04, F-67–F-69 |

## 2. Data contracts (schema version 1)

Canonical units: metres, degrees counter-clockwise from +x. Coordinates are finite `Double`; exact entries are
kept separately (C-03).

- **RoomModel** (`domain/model/Model.kt`): `schema`, `id`, display `units`, ordered `vertices` (each with stable
  `id`, `wallId` of the wall starting there, `provenance`, `locked`, `observationIds`), `openings` (wall ID,
  offset from wall start, width, optional `height`/`bottom` measures, door `handing`, provenance), `ceiling`
  (profile + details + confirmed flag), `setup` (purpose, other-use text, arrangement, items with role/kind/
  position/height/facing/lock/group/layout angle, keep-clear areas), `scale` basis, `importCalibration`
  (media ID, page, metres-per-pixel, references), `entries` (exact entered values by field key), `planConfirmed`,
  `finalized`, `treatment` preferences, `nextId`.
- **Measure**: metres + provenance + optional exact `EnteredValue` (text, unit, rational metres). Unknown = absent.
- **History**: `HistoryEntry(seq, label, model, timeMs)`; cursor; redo branch = entries after cursor.
- **Room files** (`filesDir/rooms/<id>/`): `head.json` (schema, name, timestamps, cursor, history file name,
  committed entry count and byte length, generation, current results), `history-<epoch>.jsonl`, `evidence.json`
  (observations outside undo). `filesDir/media/index.json` + media bytes with owner lists and tombstones.
  Purchase records (when configured) live outside room folders.
- **Results**: `StoredResult(kind, generation, inputHash, payload)`; current only when both match.

## 3. Capability contracts

Operation-level capability (computed live, `Capabilities.table`):

| Operation | Supported when | Otherwise |
|---|---|---|
| Editable geometry | valid closed simple outline | partial with explanation |
| 3D surfaces/apertures | ceiling profile fully defined; openings with height + bottom are cut | partial view listing missing inputs; Special = draft |
| Room modes | 4 walls, right angles ±0.01°, flat known ceiling, no details | unavailable with reason |
| First-order reflections | placed source + receiver; heights known; planar ceiling | wall paths in plan only / no ceiling paths |
| SBIR | placed source + receiver with heights | unavailable |
| Quantitative treatment estimate | validated product data configured | unavailable (B-02) |

Placement optimisation contract: DECISIONS C-17. Ceiling input contracts: DECISIONS C-14.

## 4. Workflow traces (summary)

Each acquisition path ends in the same `RoomModel` and editor: manual (template), import (calibrate → trace →
`replaceOutline`), capture (clips stored as evidence; geometry entered/corrected in the editor because no
observation provider is integrated). Every durable change: command → validation → `RoomSession.edit` → one
history entry + generation increment + result invalidation in a single head write. Failure: attempted state
shown as unsaved with Retry/Discard; durable bytes unchanged; further edits blocked until resolved. Background
jobs: ticket (room, generation, input hash) → accepted only if all still match. Deletion: session closed
(rejects late results) → directory removed via DELETING marker → media ownership released.
