package com.owlacoustics.roomplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owlacoustics.roomplanner.ui.RoomListScreen
import com.owlacoustics.roomplanner.ui.RoomScreen
import com.owlacoustics.roomplanner.ui.RoomViewModel
import com.owlacoustics.roomplanner.ui.Step
import com.owlacoustics.roomplanner.ui.theme.OwlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = (application as OwlApplication).container
        setContent {
            OwlTheme {
                // Navigation survives rotation/recreation; room data itself always comes from durable storage.
                var openRoom by rememberSaveable { mutableStateOf<String?>(null) }
                var openStep by rememberSaveable { mutableStateOf(Step.PLAN) }
                val id = openRoom
                if (id == null) {
                    RoomListScreen(c) { rid, step -> openStep = step; openRoom = rid }
                } else {
                    BackHandler { openRoom = null }
                    val vm: RoomViewModel = viewModel(key = "room-$id", factory = RoomViewModel.Factory(c, id))
                    RoomScreen(c, vm, openStep) { openRoom = null }
                }
            }
        }
    }
}
