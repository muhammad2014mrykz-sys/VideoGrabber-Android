package com.videograbber.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videograbber.app.core.LinkResolver
import com.videograbber.app.ui.MainScreen
import com.videograbber.app.ui.MainViewModel
import com.videograbber.app.ui.theme.VideoGrabberTheme

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; downloads still work, just no notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val sharedUrl = extractSharedUrl(intent)

        setContent {
            VideoGrabberTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) {
                        val vm: MainViewModel = viewModel()
                        if (sharedUrl != null) vm.onUrlChange(sharedUrl)
                        MainScreen(vm)
                    }
                }
            }
        }
    }

    /** Support "Share link -> VideoGrabber" from other apps. */
    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return LinkResolver.clean(text) ?: text.trim()
        }
        return null
    }
}
