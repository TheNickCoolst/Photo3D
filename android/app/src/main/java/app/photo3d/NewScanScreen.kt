package app.photo3d

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewScanScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val uploading = vm.upload != null

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addMedia(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addMedia(it) }

    // Serienaufnahme: nach jedem Foto öffnet sich die Kamera erneut, bis man abbricht.
    var photoRequest by rememberSaveable { mutableIntStateOf(0) }
    var burstCount by rememberSaveable { mutableIntStateOf(0) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = vm.pendingCapture
        if (ok && uri != null) {
            vm.addMedia(listOf(uri))
            burstCount++
            photoRequest++
        } else if (burstCount > 0) {
            vm.message = "$burstCount Fotos aufgenommen"
            burstCount = 0
        }
    }
    LaunchedEffect(photoRequest) {
        if (photoRequest > 0) runCatching { takePhoto.launch(vm.newCaptureUri("jpg")) }
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = vm.pendingCapture
        if (ok && uri != null) vm.addMedia(listOf(uri))
    }

    // Bildschirm während des Uploads anlassen
    val view = LocalView.current
    DisposableEffect(uploading) {
        view.keepScreenOn = uploading
        onDispose { view.keepScreenOn = false }
    }

    val photos = vm.selected.count { !it.isVideo }
    val videos = vm.selected.count { it.isVideo }
    val totalSize = vm.selected.sumOf { maxOf(it.size, 0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Neuer Scan") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, enabled = !uploading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
            )
        },
        bottomBar = { UploadBar(vm, photos, videos) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(92.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = vm.scanName,
                    onValueChange = { vm.scanName = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceButton("Galerie", Icons.Outlined.PhotoLibrary, !uploading, Modifier.weight(1f)) {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        SourceButton("Dateien", Icons.Outlined.FolderOpen, !uploading, Modifier.weight(1f)) {
                            pickFiles.launch(arrayOf("image/*", "video/*"))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceButton("Fotografieren", Icons.Outlined.PhotoCamera, !uploading, Modifier.weight(1f)) {
                            burstCount = 0
                            photoRequest++
                        }
                        SourceButton("Video filmen", Icons.Outlined.Videocam, !uploading, Modifier.weight(1f)) {
                            runCatching { takeVideo.launch(vm.newCaptureUri("mp4")) }
                        }
                    }
                }
            }
            if (vm.selected.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { TipsCard() }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            buildList {
                                if (photos > 0) add(plural(photos, "Foto", "Fotos"))
                                if (videos > 0) add(plural(videos, "Video", "Videos"))
                                add(formatBytes(totalSize))
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.clearMedia() }, enabled = !uploading) { Text("Alle entfernen") }
                    }
                }
                items(vm.selected, key = { it.uri.toString() }) { m ->
                    Box(Modifier.aspectRatio(1f)) {
                        MediaThumb(m, Modifier.fillMaxSize())
                        if (m.isVideo) {
                            Icon(
                                Icons.Default.PlayArrow, null, tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(32.dp)
                                    .background(Color(0x88000000), CircleShape)
                                    .padding(4.dp),
                            )
                        }
                        if (!uploading) {
                            IconButton(
                                onClick = { vm.removeMedia(m) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close, "Entfernen", tint = Color.White,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(Color(0x99000000), CircleShape)
                                        .padding(2.dp),
                                )
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OptionsCard(vm, showVideo = videos > 0, enabled = !uploading)
            }
        }
    }
}

@Composable
private fun SourceButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TipsCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, null)
                Spacer(Modifier.width(8.dp))
                Text("Tipps für gute Modelle", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            listOf(
                "30–150 Fotos rundum, jedes Bild ~70 % Überlappung mit dem nächsten",
                "In 2–3 Höhen ums Objekt gehen (schräg oben, seitlich, unten)",
                "Gleichmäßiges, weiches Licht – keine harten Schatten, kein Blitz",
                "Objekt nicht bewegen, scharfe Bilder, Objekt füllt das Bild",
                "Video: langsam und ruhig ums Objekt gehen (30–90 s)",
                "Glänzende, durchsichtige oder einfarbige Flächen funktionieren schlecht",
            ).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSelector(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        detailLevels.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = value == key,
                onClick = { onChange(key) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, detailLevels.size),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
    Text(
        detailDescription(value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun OptionsCard(vm: MainViewModel, showVideo: Boolean, enabled: Boolean) {
    val o = vm.options
    Card(modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Qualität", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DetailSelector(o.detail, enabled) { vm.options = o.copy(detail = it) }
            Spacer(Modifier.height(12.dp))
            SwitchRow("Hintergrund entfernen", "Nur das Objekt modellieren", o.masking, enabled) {
                vm.options = o.copy(masking = it)
            }
            SwitchRow("Hohe Empfindlichkeit", "Für Objekte mit wenig Struktur (langsamer)", o.highSensitivity, enabled) {
                vm.options = o.copy(highSensitivity = it)
            }
            if (showVideo) {
                Spacer(Modifier.height(8.dp))
                Text("Bilder pro Video: ${o.framesPerVideo}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = o.framesPerVideo.toFloat(),
                    onValueChange = { vm.options = o.copy(framesPerVideo = (it / 10).toInt() * 10) },
                    valueRange = 30f..300f,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun UploadBar(vm: MainViewModel, photos: Int, videos: Int) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            val up = vm.upload
            if (up != null) {
                val p = if (up.total > 0) (up.sent.toFloat() / up.total).coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lade hoch… Datei ${up.index + 1} von ${up.count}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatBytes(up.sent)} von ${formatBytes(up.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.cancelUpload() }) { Text("Abbrechen") }
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)))
            } else {
                val tooFew = videos == 0 && photos in 1..9
                if (tooFew) {
                    Text(
                        "Mindestens 10 Fotos nötig (empfohlen 30+)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else if (!vm.online) {
                    Text(
                        "Render-PC nicht erreichbar",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = { vm.startUpload() },
                    enabled = vm.online && vm.selected.isNotEmpty() && !tooFew,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(10.dp))
                    Text("An PC senden & 3D-Modell erstellen")
                }
            }
        }
    }
}
