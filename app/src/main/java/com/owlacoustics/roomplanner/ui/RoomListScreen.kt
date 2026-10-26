package com.owlacoustics.roomplanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.edit.StartShape
import com.owlacoustics.roomplanner.data.AppContainer
import com.owlacoustics.roomplanner.data.RoomListEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Minimal room selector (D-18): create, open, rename (inside the room), confirmed delete. Enters directly into room work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(c: AppContainer, onOpen: (String, Step) -> Unit) {
    var rooms by remember { mutableStateOf<List<RoomListEntry>?>(null) }
    var deleting by remember { mutableStateOf<RoomListEntry?>(null) }
    var creating by remember { mutableStateOf<Step?>(null) }
    var newName by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(StartShape.RECTANGLE) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() = scope.launch { rooms = withContext(Dispatchers.IO) { c.listRooms() } }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                // PROVISIONAL text wordmark until the approved logo file is supplied (B-01)
                Text("OWL Acoustics", style = MaterialTheme.typography.titleLarge)
                Text("Development build · provisional branding", style = MaterialTheme.typography.labelSmall)
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).fillMaxSize()) {
            Text("Start a room", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button({ creating = Step.CAPTURE }, modifier = Modifier.testTag("new-capture")) { Text("Capture room") }
                OutlinedButton({ creating = Step.IMPORT }, modifier = Modifier.testTag("new-import")) { Text("Import plan") }
                OutlinedButton({ creating = Step.PLAN }, modifier = Modifier.testTag("new-manual")) { Text("Build manually") }
            }
            creating?.let { step ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(when (step) { Step.CAPTURE -> "New room from video"; Step.IMPORT -> "New room from a floor plan"; else -> "New room drawn by hand" },
                            style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(newName, { newName = it }, label = { Text("Room name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new-room-name"))
                        if (step == Step.PLAN) {
                            Text("Starting shape (a working drawing you will edit)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (s in StartShape.values()) androidx.compose.material3.FilterChip(s == shape, { shape = s }, { Text(s.label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { c.createRoom(newName, shape) }
                                    creating = null; newName = ""
                                    r.onSuccess { onOpen(it, step) }.onFailure { error = it.message }
                                }
                            }, modifier = Modifier.testTag("create-room")) { Text("Create") }
                            TextButton({ creating = null }) { Text("Cancel") }
                        }
                    }
                }
            }
            Text("Rooms are saved on this phone only. They are not backed up or transferred, so uninstalling the app or losing the phone removes them.",
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Saved rooms", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            val list = rooms
            if (list == null) Text("Loading…") else if (list.isEmpty()) Text("No rooms yet.")
            LazyColumn(Modifier.fillMaxSize()) {
                items(list ?: emptyList(), key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = r.problem == null) { onOpen(r.id, Step.PLAN) }.testTag("room-${r.name}")) {
                        Row(Modifier.padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall)
                                Text(r.problem ?: ((if (r.finalized) "Finalized · " else "") + "Updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.updatedMs))),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (r.problem == null) TextButton({ deleting = r }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { r ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${r.name}”?") },
            text = { Text("This permanently removes this room's geometry, its full edit history and the videos or plans only this room uses. Other rooms are not affected. Any purchase records are kept separately. This cannot be undone.") },
            confirmButton = {
                TextButton({
                    scope.launch {
                        withContext(Dispatchers.IO) { c.deleteRoom(r.id) }.onFailure { error = "Could not delete: ${it.message}" }
                        deleting = null; refresh()
                    }
                }, modifier = Modifier.testTag("confirm-delete")) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Keep") } })
    }
}
