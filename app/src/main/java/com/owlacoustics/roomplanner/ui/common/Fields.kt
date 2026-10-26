package com.owlacoustics.roomplanner.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.model.Measure
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.units.EnteredValue
import com.owlacoustics.domain.units.LengthFormat
import com.owlacoustics.domain.units.LengthParser
import com.owlacoustics.domain.units.LengthUnit
import com.owlacoustics.domain.units.ParseResult

fun defaultUnit(u: DisplayUnits) = if (u == DisplayUnits.IMPERIAL) LengthUnit.FOOT else LengthUnit.METER

fun fmt(m: Double, u: DisplayUnits) = LengthFormat.format(m, u)

/** Shows the exact entered text when it still describes the value, otherwise a formatted value. */
fun display(meters: Double?, entered: EnteredValue?, u: DisplayUnits): String = when {
    meters == null -> ""
    entered != null && kotlin.math.abs(entered.meters - meters) < 1e-9 -> entered.text
    else -> fmt(meters, u)
}

/**
 * Exact length entry. Blank means unknown (never zero). Invalid text is rejected with the prior committed value kept.
 * [onCommit] receives null for unknown.
 */
@Composable
fun LengthField(
    label: String,
    meters: Double?,
    entered: EnteredValue?,
    units: DisplayUnits,
    modifier: Modifier = Modifier,
    allowUnknown: Boolean = true,
    onCommit: (EnteredValue?) -> Unit,
) {
    val shown = display(meters, entered, units)
    var text by remember(shown) { mutableStateOf(shown) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!focused) text = shown }
    fun commit() {
        if (text == shown) { error = null; return }
        when (val r = LengthParser.parse(text, defaultUnit(units))) {
            is ParseResult.Ok -> { error = null; onCommit(r.value) }
            is ParseResult.Unknown -> if (allowUnknown) { error = null; onCommit(null) } else { error = "A value is required"; text = shown }
            is ParseResult.Invalid -> { error = r.reason; text = shown }
        }
    }
    Column(modifier) {
        val hint = if (focused && text != shown) LengthFormat.interpretation(text, units) else null
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = { Text(if (allowUnknown) "unknown" else if (units == DisplayUnits.IMPERIAL) "e.g. 11 6 1/4" else "e.g. 3.51 m") },
            supportingText = hint?.let { h -> { Text(h) } },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            }.semantics { contentDescription = "$label, ${if (shown.isEmpty()) "unknown" else shown}" },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Measure field for heights etc. Records the value as entered by the person. */
@Composable
fun MeasureField(label: String, value: Measure?, units: DisplayUnits, modifier: Modifier = Modifier, onChange: (Measure?) -> Unit) {
    Column(modifier) {
        LengthField(label, value?.meters, value?.entered, units) { e ->
            onChange(e?.let { Measure(it.meters, Provenance.USER_ENTERED, it) })
        }
        value?.let {
            if (it.provenance != Provenance.USER_ENTERED) Text(it.provenance.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DegreesField(label: String, value: Double?, modifier: Modifier = Modifier, onChange: (Double?) -> Unit) {
    val shown = value?.let { "%.1f".format(it) } ?: ""
    var text by remember(shown) { mutableStateOf(shown) }
    var error by remember { mutableStateOf<String?>(null) }
    fun commit() {
        val t = text.trim().removeSuffix("°")
        if (t.isEmpty()) { onChange(null); error = null; return }
        val d = t.toDoubleOrNull()
        if (d == null || !d.isFinite()) { error = "Enter degrees, e.g. 90"; text = shown } else { error = null; onChange(((d % 360) + 360) % 360) }
    }
    Column(modifier) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != shown) commit() })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 2.dp))
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}
