package com.owlacoustics.roomplanner.ui.importplan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.importing.Calibration
import com.owlacoustics.domain.model.ImportCalibration
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.persistence.MediaKind
import com.owlacoustics.domain.persistence.MediaRecord
import com.owlacoustics.domain.persistence.MediaStore
import com.owlacoustics.roomplanner.ui.RoomViewModel
import com.owlacoustics.roomplanner.ui.common.LengthField
import com.owlacoustics.roomplanner.ui.common.Note
import com.owlacoustics.roomplanner.ui.common.SectionTitle
import com.owlacoustics.roomplanner.ui.plan.ViewTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val MAX_PX = 2048

/** Renders a stored plan page into a bounded bitmap. Returns null with no throw for unreadable sources. */
fun renderSource(media: MediaStore, rec: MediaRecord, page: Int): Pair<Bitmap?, Int> = runCatching {
    val f = media.fileFor(rec).toFile()
    if (rec.kind == MediaKind.PDF) {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                val n = r.pageCount
                r.openPage(page.coerceIn(0, n - 1)).use { p ->
                    val k = MAX_PX.toFloat() / maxOf(p.width, p.height)
                    val bmp = createBitmap((p.width * k).toInt().coerceAtLeast(1), (p.height * k).toInt().coerceAtLeast(1))
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp to n
                }
            }
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > MAX_PX) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) to 1
    }
}.getOrElse { null to 0 }

private fun copyIn(ctx: Context, media: MediaStore, uri: Uri, roomId: String): MediaRecord? = runCatching {
    val type = ctx.contentResolver.getType(uri) ?: ""
    val kind = when { type == "application/pdf" -> MediaKind.PDF; type.startsWith("image/") -> MediaKind.IMAGE; else -> return null }
    val id = "plan-" + UUID.randomUUID().toString().take(8)
    val ext = if (kind == MediaKind.PDF) "pdf" else "img"
    val target = media.newFile(id, ext)
    ctx.contentResolver.openInputStream(uri)?.use { input -> java.nio.file.Files.copy(input, target) } ?: return null
    MediaRecord(id, kind, target.fileName.toString(), listOf(roomId), "Imported floor plan (kept on this phone)", System.currentTimeMillis()).also { media.register(it) }
}.getOrNull()

/** Draws the calibrated source under the plan using the stored pixel → room transform. */
fun backdropFor(img: ImageBitmap, cal: ImportCalibration): (DrawScope.(ViewTransform) -> Unit)? {
    val mpp = cal.metersPerPixel ?: return null
    return { t ->
        val k = (mpp * t.scale).toFloat()
        translate(t.offset.x, t.offset.y) {
            rotateRad(t.rotation, pivot = Offset.Zero) {
                translate(0f, -img.height * k) {
                    scale(k, k, pivot = Offset.Zero) { drawImage(img, alpha = 0.45f) }
                }
            }
        }
    }
}

private enum class Mode { VIEW, CALIBRATE, TRACE }

@Composable
fun ImportScreen(vm: RoomViewModel, m: RoomModel, media: MediaStore, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cal = m.importCalibration
    val rec = cal?.let { c -> media.all().firstOrNull { it.id == c.mediaId } }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pages by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(Mode.VIEW) }
    var refPts by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var trace by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val r = copyIn(ctx, media, uri, m.id)
        if (r == null) { error = "That file type is not supported or could not be read. Choose an image or a PDF, or draw the room manually."; return@rememberLauncherForActivityResult }
        error = null
        vm.edit { Calibration.startImport(it, r.id, 0) }
    }
    LaunchedEffect(cal?.mediaId, cal?.page, rec?.deleted) {
        bitmap = null
        if (rec != null && !rec.deleted) {
            val (b, n) = withContext(Dispatchers.IO) { renderSource(media, rec, cal.page) }
            bitmap = b; pages = n
            if (b == null) error = "This plan could not be opened. It may be damaged or protected. Import another file, or draw the room manually."
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Import a floor plan", style = MaterialTheme.typography.titleMedium)
        Note("Assisted import: you mark a known length to set the scale and trace the room's corners over the plan. Printed symbols are not treated as field measurements.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.testTag("pick-plan")) { Text(if (cal == null) "Choose image or PDF" else "Choose another file") }
            OutlinedButton(onDone) { Text("Back to plan") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (rec?.deleted == true) Text("The imported source was deleted; its scale and traced outline remain.", color = MaterialTheme.colorScheme.tertiary)
        if (pages > 1 && cal != null) {
            Row {
                Text("Page ${cal.page + 1} of $pages", Modifier.padding(top = 12.dp).weight(1f))
                TextButton({ vm.edit { it.copy(importCalibration = cal.copy(page = cal.page - 1)).let { n -> com.owlacoustics.domain.edit.Edits.finish(it, n, "Choose plan page") } } }, enabled = cal.page > 0) { Text("Previous") }
                TextButton({ vm.edit { it.copy(importCalibration = cal.copy(page = cal.page + 1)).let { n -> com.owlacoustics.domain.edit.Edits.finish(it, n, "Choose plan page") } } }, enabled = cal.page < pages - 1) { Text("Next") }
            }
        }
        val b = bitmap
        if (b != null && cal != null) {
            val img = remember(b) { b.asImageBitmap() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(mode == Mode.CALIBRATE, { mode = Mode.CALIBRATE; refPts = emptyList() }, { Text("Mark known length") })
                FilterChip(mode == Mode.TRACE, { mode = Mode.TRACE }, { Text("Trace corners") }, enabled = cal.metersPerPixel != null)
            }
            Note(when (mode) {
                Mode.CALIBRATE -> "Tap both ends of a dimension you know (for example a printed wall length), then type its length."
                Mode.TRACE -> "Tap each corner of this one room in order. ${trace.size} corner(s) so far."
                Mode.VIEW -> if (cal.metersPerPixel == null) "Start by marking a known length." else "Scale set. Trace the room, or add another known length to check the scale."
            })
            Canvas(Modifier.fillMaxWidth().height(380.dp).testTag("plan-source")
                .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.2f, 20f); pan += p } }
                .pointerInput(mode) {
                    detectTapGestures { tap ->
                        val fit = minOf(size.width / img.width.toFloat(), size.height / img.height.toFloat())
                        val s = fit * zoom
                        val px = (tap - pan) / s
                        when (mode) {
                            Mode.CALIBRATE -> refPts = (refPts + px).takeLast(2)
                            Mode.TRACE -> trace = trace + px
                            Mode.VIEW -> {}
                        }
                    }
                }) {
                val fit = minOf(size.width / img.width.toFloat(), size.height / img.height.toFloat())
                val s = fit * zoom
                translate(pan.x, pan.y) {
                    scale(s, s, pivot = Offset.Zero) {
                        drawImage(img)
                        for (r in cal.references) drawLine(if (r.id == cal.activeReferenceId) Color(0xFF1F7A72) else Color(0xFFB3261E), Offset(r.pixelA.x.toFloat(), r.pixelA.y.toFloat()), Offset(r.pixelB.x.toFloat(), r.pixelB.y.toFloat()), 4f / s)
                        refPts.forEach { drawCircle(Color(0xFFE0A100), 8f / s, it) }
                        if (refPts.size == 2) drawLine(Color(0xFFE0A100), refPts[0], refPts[1], 4f / s)
                        for (i in trace.indices) { drawCircle(Color(0xFF5B3FA0), 7f / s, trace[i]); if (i > 0) drawLine(Color(0xFF5B3FA0), trace[i - 1], trace[i], 3f / s) }
                    }
                }
            }
            if (mode == Mode.CALIBRATE && refPts.size == 2) {
                LengthField("Known length between the two points", null, null, m.units, allowUnknown = false) { e ->
                    e?.let { v -> vm.edit { Calibration.addReference(it, Vec2(refPts[0].x.toDouble(), refPts[0].y.toDouble()), Vec2(refPts[1].x.toDouble(), refPts[1].y.toDouble()), v) } }
                    refPts = emptyList(); mode = Mode.VIEW
                }
            }
            if (mode == Mode.TRACE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ trace = trace.dropLast(1) }, enabled = trace.isNotEmpty()) { Text("Undo corner") }
                Button({
                    vm.edit { Calibration.traceOutline(it, trace.map { p -> Vec2(p.x.toDouble(), p.y.toDouble()) }, img.height.toDouble()) }
                    trace = emptyList(); mode = Mode.VIEW
                }, enabled = trace.size >= 3) { Text("Use this outline") }
            }
            SectionTitle("Scale references")
            for (r in cal.references) {
                val mpp = Calibration.metersPerPixel(r)
                Row {
                    Text((if (r.id == cal.activeReferenceId) "✓ " else "") + "${r.known.text} over %.0f px".format(r.pixelA.distanceTo(r.pixelB)), Modifier.weight(1f))
                    if (r.id != cal.activeReferenceId && mpp != null) TextButton({ vm.edit { Calibration.recalibrate(it, r.id) } }) { Text("Use this one") }
                }
            }
            for (c in Calibration.conflicts(cal)) Text("Scale conflict: two references disagree by %.1f%%. The room was not resized; choose which reference is right.".format(c.ratio * 100), color = MaterialTheme.colorScheme.error)
            Note("Only this one room is traced. Other rooms on the plan are not modeled.")
        }
    }
}
