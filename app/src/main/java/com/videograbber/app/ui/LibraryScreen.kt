package com.videograbber.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videograbber.app.core.DownloadItem
import com.videograbber.app.core.DownloadsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(vm: LibraryViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        when {
            items.isEmpty() && loading ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))

            items.isEmpty() ->
                EmptyLibrary(Modifier.align(Alignment.Center))

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    DownloadRow(
                        item = item,
                        onPlay = { DownloadsRepository.open(context, item) },
                        onShare = { DownloadsRepository.share(context, item) },
                        onDelete = { vm.delete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.VideoLibrary, null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("No downloads yet", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("Videos you download will appear here.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = DownloadsRepository.thumbnail(context, item)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(88.dp, 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.Movie, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${humanSize(item.sizeBytes)}  •  ${humanDate(item.dateMillis)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowMenu(onPlay = onPlay, onShare = onShare, onDelete = onDelete)
        }
    }
}

@Composable
private fun RowMenu(onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                onClick = { open = false; onPlay() },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = { open = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

private fun humanDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(millis))
