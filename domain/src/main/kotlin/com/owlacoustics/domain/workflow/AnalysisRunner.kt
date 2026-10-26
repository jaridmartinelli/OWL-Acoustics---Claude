package com.owlacoustics.domain.workflow

import com.owlacoustics.domain.acoustics.Capabilities
import com.owlacoustics.domain.acoustics.Capability
import com.owlacoustics.domain.acoustics.Modes
import com.owlacoustics.domain.acoustics.ReflectionReport
import com.owlacoustics.domain.acoustics.Reflections
import com.owlacoustics.domain.acoustics.RoomModeResult
import com.owlacoustics.domain.catalog.Catalog
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.persistence.OwlJson
import com.owlacoustics.domain.placement.PlacementOptimizer
import com.owlacoustics.domain.recommend.RecommendationSet
import com.owlacoustics.domain.recommend.Recommender
import kotlinx.serialization.Serializable

const val RESULT_ANALYSIS = "analysis"
const val RESULT_PLACEMENT = "placement"
const val RESULT_RECOMMENDATIONS = "recommendations"

@Serializable
data class AnalysisBundle(
    val capabilities: List<Capability>,
    val modes: List<RoomModeResult>?,
    val modesNote: String,
    val dims: List<Double>?,
    val reflections: ReflectionReport,
)

/** Runs the supported local methods for the current model. Each method is gated by its own inputs. */
object AnalysisRunner {
    fun analyze(m: RoomModel): AnalysisBundle {
        val caps = Capabilities.table(m, Catalog.hasValidatedPerformance)
        val e = Modes.eligibility(m)
        val (modes, note, dims) = when (e) {
            is Modes.Eligibility.Rectangular -> Triple(Modes.rectangular(e.lx, e.ly, e.lz),
                "Rectangular rigid-wall model: L %.3f m × W %.3f m × H %.3f m, c = 343 m/s. Openings and wall construction are not modeled.".format(e.lx, e.ly, e.lz),
                listOf(e.lx, e.ly, e.lz))
            is Modes.Eligibility.Unavailable -> Triple(null, e.reason, null)
        }
        return AnalysisBundle(caps, modes, note, dims, Reflections.analyze(m))
    }

    fun encode(b: AnalysisBundle) = OwlJson.encodeToString(AnalysisBundle.serializer(), b)
    fun decodeAnalysis(s: String) = OwlJson.decodeFromString(AnalysisBundle.serializer(), s)
    fun encode(r: PlacementOptimizer.Result) = OwlJson.encodeToString(PlacementOptimizer.Result.serializer(), r)
    fun decodePlacement(s: String) = OwlJson.decodeFromString(PlacementOptimizer.Result.serializer(), s)
    fun encode(r: RecommendationSet) = OwlJson.encodeToString(RecommendationSet.serializer(), r)
    fun decodeRecommendations(s: String) = OwlJson.decodeFromString(RecommendationSet.serializer(), s)

    fun recommend(m: RoomModel, analysis: AnalysisBundle, placement: PlacementOptimizer.Result?) =
        Recommender.recommend(m, analysis.reflections, placement)
}
