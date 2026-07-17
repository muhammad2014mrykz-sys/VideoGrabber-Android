package com.videograbber.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.LinkResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "محمّل الفيديوهات",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "حمّل من أي منصة — يوتيوب، تيك توك، تويتر، فيسبوك، إنستجرام وأكثر",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // URL input
        OutlinedTextField(
            value = ui.url,
            onValueChange = vm::onUrlChange,
            placeholder = { Text("الصق رابط الفيديو هنا…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.let {
                        vm.onUrlChange(LinkResolver.clean(it.text) ?: it.text.trim())
                    }
                },
            ) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("لصق")
            }
            Button(
                onClick = vm::fetch,
                enabled = !ui.fetching && ui.url.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (ui.fetching) "جارٍ الجلب…" else "جلب المعلومات")
            }
        }

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        // Info card
        if (ui.hasInfo) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ui.thumbnail.isNullOrBlank()) {
                        AsyncImage(
                            model = ui.thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    }
                    Text(
                        ui.title ?: "—",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "المنصة: ${ui.platform}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Quality + audio options (hidden for single-stream sources like Kwai)
            if (!ui.directStream) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (!ui.audioOnly) expanded = it },
                ) {
                    OutlinedTextField(
                        value = ui.qualities.getOrNull(ui.selectedQuality)?.label ?: "الأعلى تلقائياً",
                        onValueChange = {},
                        readOnly = true,
                        enabled = !ui.audioOnly,
                        label = { Text("الجودة") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ui.qualities.forEachIndexed { i, q ->
                            DropdownMenuItem(
                                text = { Text(q.label) },
                                onClick = { vm.selectQuality(i); expanded = false },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("صوت فقط (MP3)", color = MaterialTheme.colorScheme.onBackground)
                    Switch(checked = ui.audioOnly, onCheckedChange = vm::setAudioOnly)
                }
            }

            DownloadSection(dl = dl, vm = vm, context = context)
        }
    }
}

@Composable
private fun DownloadSection(
    dl: DownloadBus.State,
    vm: MainViewModel,
    context: Context,
) {
    val running = dl is DownloadBus.State.Running || dl is DownloadBus.State.Preparing

    if (running) {
        val percent = (dl as? DownloadBus.State.Running)?.percent ?: 0f
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
        Text(
            if (dl is DownloadBus.State.Preparing) "جارٍ التحضير…"
            else "جارٍ التحميل… ${percent.toInt()}%",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
            Text("إلغاء")
        }
    } else {
        Button(
            onClick = vm::startDownload,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.size(8.dp))
            Text("تحميل", fontSize = 16.sp)
        }
    }

    when (dl) {
        is DownloadBus.State.Success -> {
            Text(
                "تم الحفظ ✓  ${dl.savedPath}",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
            )
        }
        is DownloadBus.State.Error -> {
            Text(dl.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        else -> {}
    }
}
