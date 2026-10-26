package com.owlacoustics.domain.model

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.units.EnteredValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SCHEMA_VERSION = 1

/** How a value is known. Never upgraded merely because an object was created or edited. */
@Serializable
enum class Provenance(val label: String) {
    OBSERVED("observed"),
    MEASURED("measured"),
    CALCULATED("calculated"),
    ESTIMATED("estimated"),
    INFERRED("inferred"),
    ASSUMED("assumed"),
    USER_ENTERED("entered by you"),
    CORRECTED("corrected by you"),
    PLANNING("planning drawing — not measured"),
}

/** A known length/height with provenance. An unknown value is represented by `null`, never 0. */
@Serializable
data class Measure(val meters: Double, val provenance: Provenance, val entered: EnteredValue? = null) {
    init { require(meters.isFinite()) { "non-finite measure" } }
}

@Serializable
data class Vertex(
    val id: String,
    val x: Double,
    val y: Double,
    /** ID of the wall that starts at this vertex and runs to the next vertex. */
    val wallId: String,
    val provenance: Provenance = Provenance.PLANNING,
    val locked: Boolean = false,
    val observationIds: List<String> = emptyList(),
) {
    val pos get() = Vec2(x, y)
}

@Serializable
enum class OpeningKind { WINDOW, DOOR }

/** Single-leaf door diagram choice (D-07). A drawing choice recorded as user input, not detected evidence. */
@Serializable
enum class DoorHanding(val label: String) {
    UNKNOWN("Swing not set"),
    HINGE_START_IN("Hinge left · opens in"),
    HINGE_START_OUT("Hinge left · opens out"),
    HINGE_END_OUT("Hinge right · opens out"),
    HINGE_END_IN("Hinge right · opens in");

    fun next(): DoorHanding = when (this) {
        UNKNOWN -> HINGE_START_IN
        HINGE_START_IN -> HINGE_START_OUT
        HINGE_START_OUT -> HINGE_END_OUT
        HINGE_END_OUT -> HINGE_END_IN
        HINGE_END_IN -> HINGE_START_IN
    }
}

@Serializable
data class Opening(
    val id: String,
    val wallId: String,
    val kind: OpeningKind,
    /** Distance from the parent wall's start vertex to the opening's start jamb, metres. */
    val offset: Double,
    val width: Double,
    val height: Measure? = null,
    /** Sill (window) or bottom (door) elevation above the floor datum. Unknown is null; 0 is a real value. */
    val bottom: Measure? = null,
    val handing: DoorHanding = DoorHanding.UNKNOWN,
    val provenance: Provenance = Provenance.PLANNING,
)

@Serializable
enum class ScaleKind(val label: String) {
    PLANNING("Planning drawing — not measured"),
    USER_AUTHORED("Dimensions entered by you"),
    IMPORT_CALIBRATED("Calibrated from a known length on the imported plan"),
    CAPTURE_ANCHOR("Metric anchor from capture evidence"),
    UNKNOWN("Scale unknown"),
}

@Serializable
data class ScaleBasis(val kind: ScaleKind, val note: String = "", val referenceObservationId: String? = null)

// ---------------- Ceiling ----------------

@Serializable
enum class ArchCurve(val label: String) { CIRCULAR_SEGMENT("Circular arc"), HALF_ELLIPSE("Elliptical"), SAMPLED("Measured profile points") }

@Serializable
data class ProfileSample(val fraction: Double, val height: Double)

@Serializable
enum class TrayTransition { VERTICAL, SLOPED }

@Serializable
sealed interface CeilingProfile {
    @Serializable @SerialName("unset") data object Unset : CeilingProfile
    @Serializable @SerialName("flat") data class Flat(val height: Measure? = null) : CeilingProfile
    /** Rises along [bearingDeg]; [low] at the room's lowest projection along that direction, [high] at the highest. */
    @Serializable @SerialName("slope") data class SingleSlope(
        val bearingDeg: Double? = null, val low: Measure? = null, val high: Measure? = null,
    ) : CeilingProfile
    /** Ridge runs along [ridgeBearingDeg]; [ridgeOffset] measured across the room from its side-A extreme. */
    @Serializable @SerialName("peaked") data class Peaked(
        val ridgeBearingDeg: Double? = null, val ridgeOffset: Measure? = null, val ridgeHeight: Measure? = null,
        val eaveA: Measure? = null, val eaveB: Measure? = null,
    ) : CeilingProfile
    @Serializable @SerialName("flat_center_vault") data class FlatCenterVault(
        val ridgeBearingDeg: Double? = null, val plateauOffset: Measure? = null, val plateauWidth: Measure? = null,
        val plateauHeight: Measure? = null, val eaveA: Measure? = null, val eaveB: Measure? = null,
    ) : CeilingProfile
    @Serializable @SerialName("arched") data class Arched(
        val axisBearingDeg: Double? = null, val springHeight: Measure? = null, val crownHeight: Measure? = null,
        val curve: ArchCurve? = null, val samples: List<ProfileSample> = emptyList(),
    ) : CeilingProfile
    @Serializable @SerialName("tray") data class Tray(
        val baseHeight: Measure? = null, val raisedHeight: Measure? = null, val inset: Measure? = null,
        val transition: TrayTransition = TrayTransition.VERTICAL, val slopedRun: Measure? = null,
    ) : CeilingProfile
    @Serializable @SerialName("special") data class Special(val notes: String = "") : CeilingProfile
}

@Serializable
enum class CeilingDetailKind(val label: String) { DROPPED("Dropped / suspended area"), BEAM("Exposed beam"), COFFER("Coffered recess") }

@Serializable
data class CeilingDetail(
    val id: String,
    val kind: CeilingDetailKind,
    val center: Vec2,
    val sizeAlong: Double,
    val sizeAcross: Double,
    val bearingDeg: Double = 0.0,
    /** Dropped: underside height above floor. Beam: depth below ceiling. Coffer: recess depth above ceiling. */
    val vertical: Measure? = null,
)

@Serializable
data class Ceiling(
    val profile: CeilingProfile = CeilingProfile.Unset,
    val details: List<CeilingDetail> = emptyList(),
    /** "My ceiling dimensions are confirmed" — distinct from plan confirmation. */
    val confirmed: Boolean = false,
)

// ---------------- Purpose & setup ----------------

@Serializable
enum class Purpose(val label: String, val playback: Boolean) {
    MIXING("Mixing / Music Production", true),
    PODCAST("Podcast", false),
    AUDIOBOOK("Audiobook / Narration", false),
    VOICE_ACTING("Voice Acting", false),
    ASMR("ASMR", false),
    HOME_THEATER("Home Theater", true),
    HIFI("Hi-Fi / General Listening", true),
    STREAMING("Streaming / Video", false),
    INSTRUMENT("Instrument Recording", false),
    OTHER("Other", false),
}

@Serializable
enum class Arrangement(val label: String) {
    STEREO("Stereo pair + listener"),
    MONO("Single speaker + listener"),
    LCR("Left / centre / right + listener"),
    SURROUND_5_1("5.1 surround + listener"),
    SURROUND_7_1("7.1 surround + listener"),
    TALKER_MIC("Voice + microphone"),
    PODCAST("Talkers with their own microphones"),
    INSTRUMENT_MIC("Instrument + microphone"),
    CUSTOM("Custom sources and receivers"),
}

@Serializable
enum class Role { SOURCE, RECEIVER }

@Serializable
enum class ItemKind(val label: String, val role: Role) {
    SPEAKER("Speaker", Role.SOURCE),
    SUBWOOFER("Subwoofer", Role.SOURCE),
    TALKER("Voice", Role.SOURCE),
    INSTRUMENT("Instrument", Role.SOURCE),
    LISTENER("Listening position", Role.RECEIVER),
    MICROPHONE("Microphone", Role.RECEIVER),
}

@Serializable
data class SetupItem(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val position: Vec2? = null,
    val height: Measure? = null,
    val facingDeg: Double? = null,
    /** Explicit user immovable constraint (never inferred from an observed location). */
    val locked: Boolean = false,
    /** Groups a source with its receiver (e.g. a podcast talker and their microphone). */
    val group: Int = 0,
    /** Layout angle relative to the listener's facing direction, degrees (playback layouts). */
    val layoutAngleDeg: Double? = null,
)

@Serializable
data class KeepOut(val id: String, val center: Vec2, val sizeX: Double, val sizeY: Double, val label: String = "Keep clear")

@Serializable
data class Setup(
    val purpose: Purpose? = null,
    val otherUse: String = "",
    val arrangement: Arrangement? = null,
    val items: List<SetupItem> = emptyList(),
    val keepOuts: List<KeepOut> = emptyList(),
)

// ---------------- Treatment preferences ----------------

@Serializable
enum class TreatmentMode(val label: String) {
    BUY_NOTHING("Buy nothing — free changes only"),
    ITEM_LIMIT("Budget — limit number of items"),
    FULL("Full package"),
}

@Serializable
data class ExistingTreatment(val id: String, val description: String, val center: Vec2?, val surface: String = "")

@Serializable
data class TreatmentPrefs(
    val mode: TreatmentMode = TreatmentMode.BUY_NOTHING,
    val maxItems: Int = 4,
    val excludedProductIds: List<String> = emptyList(),
    val excludedLocations: List<String> = emptyList(),
    /** Recorded as present/unknown; never credited acoustically from appearance. */
    val existing: List<ExistingTreatment> = emptyList(),
)

// ---------------- Import calibration ----------------

@Serializable
data class CalibrationRef(
    val id: String,
    val pixelA: Vec2,
    val pixelB: Vec2,
    val known: EnteredValue,
)

@Serializable
data class ImportCalibration(
    val mediaId: String,
    val page: Int = 0,
    /** Metres per source pixel (similarity transform, origin at source pixel (0,0), y flipped). */
    val metersPerPixel: Double? = null,
    val references: List<CalibrationRef> = emptyList(),
    val activeReferenceId: String? = null,
)

// ---------------- Room ----------------

@Serializable
data class RoomModel(
    val schema: Int = SCHEMA_VERSION,
    val id: String,
    val units: DisplayUnits = DisplayUnits.IMPERIAL,
    val vertices: List<Vertex>,
    val openings: List<Opening> = emptyList(),
    val ceiling: Ceiling = Ceiling(),
    val setup: Setup = Setup(),
    val scale: ScaleBasis = ScaleBasis(ScaleKind.PLANNING),
    val importCalibration: ImportCalibration? = null,
    /** Exact entered values keyed by field, e.g. "wall:<id>:length". */
    val entries: Map<String, EnteredValue> = emptyMap(),
    val planConfirmed: Boolean = false,
    val reviewedIssues: List<String> = emptyList(),
    val finalized: Boolean = false,
    val treatment: TreatmentPrefs = TreatmentPrefs(),
    /** ID counter for new object identities in this room. */
    val nextId: Long = 1,
) {
    val outline: List<Vec2> get() = vertices.map { it.pos }

    fun wallIndex(wallId: String) = vertices.indexOfFirst { it.wallId == wallId }

    fun wall(wallId: String): Wall? {
        val i = wallIndex(wallId)
        return if (i < 0) null else wallAt(i)
    }

    fun wallAt(i: Int): Wall {
        val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
        return Wall(a.wallId, i, a, b)
    }

    val walls: List<Wall> get() = vertices.indices.map { wallAt(it) }

    fun openingsOn(wallId: String) = openings.filter { it.wallId == wallId }.sortedBy { it.offset }
}

data class Wall(val id: String, val index: Int, val start: Vertex, val end: Vertex) {
    val a get() = start.pos
    val b get() = end.pos
    val vector get() = b - a
    val length get() = vector.length
    val dir get() = vector.normalized()
    /** Outward normal for a CCW outline. */
    val outward get() = Vec2(dir.y, -dir.x)
    fun pointAt(offset: Double) = a + dir * offset
}
