package com.owlacoustics.capturelab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.units.LengthFormat
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult

fun ft(m: Double) = LengthFormat.formatFeetInches(m)
fun inches(m: Double) = "%+.1f\"".format(m / 0.0254)

/** Feet-and-inches entry with live interpretation; returns metres or null. */
@Composable
fun FeetField(label: String, initial: String, onValue: (Double?, String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = LengthParser.parse(text, LengthUnit.FOOT)
    Column {
        OutlinedTextField(text, {
            text = it
            val p = LengthParser.parse(it, LengthUnit.FOOT)
            onValue((p as? ParseResult.Ok)?.value?.meters, it)
        }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(when (parsed) { is ParseResult.Ok -> "= " + ft(parsed.value.meters); is ParseResult.Invalid -> parsed.reason; else -> "e.g. 5 2 or 5' 2\"" },
            style = MaterialTheme.typography.labelSmall)
    }
}

/** Centre crosshair: the camera's optical axis. */
@Composable
fun Crosshair(modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color.White, 22f, c, style = Stroke(4f)); drawCircle(Color(0xFFF2C14E), 22f, c, style = Stroke(2f))
        drawLine(Color(0xFFF2C14E), c - Offset(40f, 0f), c + Offset(40f, 0f), 3f)
        drawLine(Color(0xFFF2C14E), c - Offset(0f, 40f), c + Offset(0f, 40f), 3f)
    }
}

/** Plan drawing of an outline with wall lengths; optional truth rectangle in grey (aligned at the first corner). */
@Composable
fun OutlineDrawing(corners: List<Vec2>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.padding(8.dp)) {
        if (corners.size < 2) return@Canvas
        val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }; val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
        val s = minOf((size.width - 80) / (maxX - minX).coerceAtLeast(0.1), (size.height - 80) / (maxY - minY).coerceAtLeast(0.1)).toFloat()
        fun map(p: Vec2) = Offset(40 + ((p.x - minX) * s).toFloat(), size.height - 40 - ((p.y - minY) * s).toFloat())
        val path = Path().apply { corners.forEachIndexed { i, p -> val o = map(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
        drawPath(path, Color(0x221F7A72)); drawPath(path, Color(0xFF1F7A72), style = Stroke(5f))
        for (i in corners.indices) {
            val a = corners[i]; val b = corners[(i + 1) % corners.size]
            val layout = tm.measure(ft(a.distanceTo(b)), TextStyle(fontSize = 11.sp, color = Color.Black, background = Color(0xDDFFFFFF)))
            val m = (map(a) + map(b)) / 2f
            drawText(layout, topLeft = m - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
    }
}
