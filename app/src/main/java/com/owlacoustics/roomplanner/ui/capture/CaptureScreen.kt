package com.owlacoustics.roomplanner.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.owlacoustics.domain.capture.MAX_CLIP_MS
import com.owlacoustics.domain.capture.RemoteAnalysisConfig
import com.owlacoustics.domain.persistence.EvidenceStore
import com.owlacoustics.domain.persistence.MediaKind
import com.owlacoustics.domain.persistence.MediaRecord
import com.owlacoustics.domain.persistence.MediaStore
import com.owlacoustics.domain.persistence.Observation
import com.owlacoustics.roomplanner.ui.common.Note
import com.owlacoustics.roomplanner.ui.common.SectionTitle
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Video-first capture (§3.1, D-16): silent video into app-private storage, at most 60 s per clip.
 * No microphone permission is requested. No processing is claimed: the on-device observation provider is not
 * implemented in this build and the server route is not configured.
 */
@Composable
fun CaptureScreen(roomId: String, media: MediaStore, evidence: EvidenceStore, remote: RemoteAnalysisConfig, onContinueToPlan: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    var clips by remember { mutableStateOf(media.forRoom(roomId).filter { it.kind == MediaKind.VIDEO }) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var status by remember { mutableStateOf<String?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var confirmDelete by remember { mutableStateOf<MediaRecord?>(null) }
    var bindGen by remember { mutableIntStateOf(0) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Capture room video", style = MaterialTheme.typography.titleMedium)
        Note("Walk slowly around the room, overlapping each view. Video is silent and kept privately on this phone. Clips are limited to 60 seconds.")
        if (!granted) {
            Text("Camera access is needed only to record room video.", style = MaterialTheme.typography.bodyMedium)
            if (denied) Text("Camera access was declined. You can allow it in system settings later, or continue with a floor plan or by drawing the room.", color = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.testTag("allow-camera")) { Text("Allow camera") }
                OutlinedButton(onContinueToPlan) { Text("Draw the room instead") }
            }
        } else {
            AndroidView(
                factory = { c -> PreviewView(c).also { it.scaleType = PreviewView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                update = { view ->
                    if (videoCapture == null) {
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            runCatching {
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                                val vc = VideoCapture.withOutput(recorder)
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
                                videoCapture = vc
                            }.onFailure { status = "Camera could not start: ${it.message}. You can still import a plan or draw the room." }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
            )
            DisposableEffect(bindGen) { onDispose { recording?.stop() } }
            val rec = recording
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (rec == null) {
                    Button({
                        val vc = videoCapture ?: run { status = "Camera is still starting."; return@Button }
                        val id = "clip-" + UUID.randomUUID().toString().take(8)
                        val file = media.newFile(id, "mp4").toFile()
                        val opts = FileOutputOptions.Builder(file).setDurationLimitMillis(MAX_CLIP_MS).build()
                        val started = System.currentTimeMillis()
                        // no withAudioEnabled(): geometry video is silent and needs no microphone permission
                        recording = vc.output.prepareRecording(ctx, opts).start(ContextCompat.getMainExecutor(ctx)) { ev ->
                            when (ev) {
                                is VideoRecordEvent.Start -> status = "Recording…"
                                is VideoRecordEvent.Finalize -> {
                                    recording = null
                                    val dur = ev.recordingStats.recordedDurationNanos / 1_000_000
                                    val usable = ev.error == VideoRecordEvent.Finalize.ERROR_NONE || ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                                        (ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE && file.length() > 0)
                                    if (usable && file.exists() && dur > 0) {
                                        media.register(MediaRecord(id, MediaKind.VIDEO, file.name, listOf(roomId), "Room geometry reference (kept on this phone)", started, dur,
                                            note = if (ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) "stopped at the 60 s limit" else ""))
                                        evidence.append(roomId, Observation("obs-$id", "capture-clip", id, "Silent clip ${dur / 1000} s; no geometry extracted on device", System.currentTimeMillis()))
                                        status = if (ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) "Saved. The clip stopped at the 60-second limit." else "Saved."
                                    } else {
                                        file.delete()
                                        status = "The recording could not be saved (code ${ev.error}). Nothing was kept; try again."
                                    }
                                    clips = media.forRoom(roomId).filter { it.kind == MediaKind.VIDEO }
                                }
                                else -> {}
                            }
                        }
                        elapsed = 0
                    }, modifier = Modifier.testTag("record")) { Text("Record") }
                } else {
                    Button({ rec.stop() }) { Text("Stop (${elapsed / 1000}s / 60s)") }
                }
                OutlinedButton(onContinueToPlan) { Text("Continue in plan editor") }
            }
            LaunchedEffect(rec) { while (recording != null) { delay(500); elapsed += 500 } }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        SectionTitle("What happens to clips")
        Note("Automatic room reconstruction from video is not available in this build: no on-device observation provider has been integrated and validated yet, so clips are not turned into walls automatically. Use them as your own reference while you draw or correct the plan.")
        Note(if (remote.available) "Server analysis is configured." else "Server analysis is not configured (missing: ${remote.missing.joinToString()}). No video leaves this phone.")
        SectionTitle("Clips for this room")
        if (clips.isEmpty()) Note("No clips yet.")
        for (c in clips) {
            Row {
                Text(if (c.deleted) "Deleted clip (no longer available)" else "Clip ${c.id.takeLast(4)} · ${(c.durationMs ?: 0) / 1000}s ${c.note}", Modifier.weight(1f))
                if (!c.deleted) TextButton({ confirmDelete = c }) { Text("Delete") }
            }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete this clip?") },
            text = { Text("The video file is removed from this phone. Measurements you made from it stay; undo will not bring the video back.") },
            confirmButton = { TextButton({ media.deleteMedia(c.id); clips = media.forRoom(roomId).filter { it.kind == MediaKind.VIDEO }; confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep") } })
    }
}
