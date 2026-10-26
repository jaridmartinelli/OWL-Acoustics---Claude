package com.owlacoustics.domain.edit

import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.ScaleBasis
import com.owlacoustics.domain.model.ScaleKind
import com.owlacoustics.domain.model.Vertex

private const val FT = 0.3048

/** Starting shapes are working drawings, never measurements (§3.3). */
enum class StartShape(val label: String) {
    RECTANGLE("Rectangle"), L_SHAPE("L-shape"), ALCOVE("Rectangle with alcove"), ANGLED("Angled corner")
}

object Templates {
    fun outline(shape: StartShape): List<Vec2> = when (shape) {
        StartShape.RECTANGLE -> listOf(Vec2(0.0, 0.0), Vec2(12 * FT, 0.0), Vec2(12 * FT, 10 * FT), Vec2(0.0, 10 * FT))
        StartShape.L_SHAPE -> listOf(
            Vec2(0.0, 0.0), Vec2(14 * FT, 0.0), Vec2(14 * FT, 6 * FT), Vec2(8 * FT, 6 * FT), Vec2(8 * FT, 12 * FT), Vec2(0.0, 12 * FT),
        )
        StartShape.ALCOVE -> listOf(
            Vec2(0.0, 0.0), Vec2(12 * FT, 0.0), Vec2(12 * FT, 10 * FT), Vec2(8 * FT, 10 * FT), Vec2(8 * FT, 13 * FT),
            Vec2(4 * FT, 13 * FT), Vec2(4 * FT, 10 * FT), Vec2(0.0, 10 * FT),
        )
        StartShape.ANGLED -> listOf(
            Vec2(0.0, 0.0), Vec2(12 * FT, 0.0), Vec2(12 * FT, 7 * FT), Vec2(9 * FT, 10 * FT), Vec2(0.0, 10 * FT),
        )
    }

    /** D-02: a new room is a clearly labeled 12 × 10 ft planning rectangle. */
    fun newRoom(id: String, shape: StartShape = StartShape.RECTANGLE): RoomModel {
        var next = 1L
        val verts = outline(shape).map { p ->
            val v = Vertex("v$next", p.x, p.y, "w${next + 1}", Provenance.PLANNING)
            next += 2
            v
        }
        return RoomModel(id = id, vertices = verts, scale = ScaleBasis(ScaleKind.PLANNING), nextId = next)
    }
}
