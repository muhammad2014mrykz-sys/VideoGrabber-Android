package com.videograbber.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videograbber.app.core.LinkResolver
import com.videograbber.app.ui.LibraryScreen
import com.videograbber.app.ui.LibraryViewModel
import com.videograbber.app.ui.MainScreen
import com.videograbber.app.ui.MainViewModel
import com.videograbber.app.ui.theme.VideoGrabberTheme

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val sharedUrl = extractSharedUrl(intent)
        setContent {
            VideoGrabberTheme {
                AppRoot(sharedUrl)
            }
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return LinkResolver.clean(text) ?: text.trim()
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(sharedUrl: String?) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val mainVm: MainViewModel = viewModel()
    val libVm: LibraryViewModel = viewModel()

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) mainVm.onUrlChange(sharedUrl)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("VideoGrabber", fontWeight = FontWeight.Medium) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Download, null) },
                    label = { Text("Download") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1; libVm.refresh() },
                    icon = { Icon(Icons.Default.VideoLibrary, null) },
                    label = { Text("Library") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> MainScreen(mainVm)
                else -> LibraryScreen(libVm)
            }
        }
    }
}
