package app.photo3d

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val job = vm.currentJob
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name ?: "Scan", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    if (job != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Löschen") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            when {
                job == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (vm.online) Text("Scan nicht gefunden") else CircularProgressIndicator()
                }
                job.done -> DoneView(vm, job)
                job.failed -> FailedView(vm, job)
                else -> ProgressView(vm, job)
            }
        }
    }
    if (confirmDelete && job != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Scan löschen?") },
            text = { Text("„${job.name}“ wird mit allen Fotos und Modellen vom PC gelöscht.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteJob(job) }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ProgressView(vm: MainViewModel, job: ScanJob) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        JobThumb(vm.api, job, Modifier.size(160.dp))
        Spacer(Modifier.height(28.dp))
        Text(stateLabel(job), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (job.state == "queued") {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        } else {
            LinearProgressIndicator(
                progress = { job.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("${(job.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            job.eta?.let { Text("noch ${formatEta(it)}", style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(24.dp))
        InfoLine(job)
        Spacer(Modifier.height(8.dp))
        Text(
            "Du kannst die App schließen – der PC rendert weiter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (job.state == "uploading") {
            Text(
                "Der Upload wurde nicht abgeschlossen.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.restart(job, null) }) { Text("Mit vorhandenen Dateien rendern") }
        } else {
            OutlinedButton(onClick = { vm.cancelJob(job) }) { Text("Rendern abbrechen") }
        }
        Warnings(job)
    }
}

@Composable
private fun InfoLine(job: ScanJob) {
    val parts = buildList {
        if (job.images > 0) add(plural(job.images, "Foto", "Fotos"))
        if (job.videos > 0) add(plural(job.videos, "Video", "Videos"))
        if (job.frames > 0) add("${job.frames} Video-Frames")
        job.detail?.let { d -> add("Qualität: " + detailLabel(d)) }
    }
    if (parts.isNotEmpty()) Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Warnings(job: ScanJob) {
    if (job.warnings.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Hinweise", style = MaterialTheme.typography.titleSmall)
            }
            job.warnings.takeLast(6).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun FailedView(vm: MainViewModel, job: ScanJob) {
    var detail by rememberSaveable(job.id) { mutableStateOf(job.detail ?: "medium") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(stateLabel(job), style = MaterialTheme.typography.titleLarge)
        job.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        InfoLine(job)
        if (job.state == "failed") {
            Spacer(Modifier.height(16.dp))
            Text(
                "Tipp: Mehr Fotos mit mehr Überlappung, besseres Licht oder „Hohe Empfindlichkeit“ probieren.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        DetailSelector(detail, true) { detail = it }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.restart(job, detail) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Replay, null)
            Spacer(Modifier.width(8.dp))
            Text("Erneut rendern")
        }
        Warnings(job)
    }
}

@Composable
private fun DoneView(vm: MainViewModel, job: ScanJob) {
    val ctx = LocalContext.current
    LaunchedEffect(job.id, job.finished, job.glbSize) { vm.loadModel(job) }
    val load = vm.model?.takeIf { it.jobId == job.id }

    var pendingSave by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingSave
        if (uri != null && f != null) vm.saveAs(job, f, uri)
        pendingSave = null
    }
    var saveMenu by remember { mutableStateOf(false) }
    var showRerender by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable(job.id) { mutableStateOf(job.detail ?: "medium") }

    Column(Modifier.fillMaxSize()) {
        // 3D-Ansicht (nicht in einem Scroll-Container, damit Gesten beim Modell landen)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                load?.file != null -> ModelViewer(load.file, Modifier.fillMaxSize())
                load?.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(load.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.loadModel(job) }) { Text("Erneut laden") }
                }
                !job.hasGlb -> Text(
                    "Kein GLB vorhanden (Blender fehlt auf dem PC?).\nUSDZ/OBJ können trotzdem gespeichert werden.",
                    textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Lade Modell… ${((load?.progress ?: 0f) * 100).toInt()} %")
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InfoLine(job)
                        if (job.glbSize > 0) Text(
                            "GLB ${formatBytes(job.glbSize)}" + if (job.usdzSize > 0) " · USDZ ${formatBytes(job.usdzSize)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { vm.localModelUri(job)?.let { shareModel(ctx, it) } },
                        enabled = load?.file != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Teilen")
                    }
                    Box(Modifier.weight(1f)) {
                        FilledTonalButton(onClick = { saveMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Speichern")
                        }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            val base = safeFileName(job.name)
                            if (job.hasGlb) DropdownMenuItem(
                                text = { Text("GLB (Android, Web, Blender)") },
                                onClick = { saveMenu = false; pendingSave = "model.glb"; saveLauncher.launch("$base.glb") },
                            )
                            if (job.hasUsdz) DropdownMenuItem(
                                text = { Text("USDZ (Apple AR)") },
                                onClick = { saveMenu = false; pendingSave = "model.usdz"; saveLauncher.launch("$base.usdz") },
                            )
                            if (job.hasObj) DropdownMenuItem(
                                text = { Text("OBJ + Texturen (ZIP)") },
                                onClick = { saveMenu = false; pendingSave = "model.zip"; saveLauncher.launch("${base}_obj.zip") },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.api?.let { openAr(ctx, it.fileUrl(job.id, "model.glb"), job.name) { vm.message = it } } },
                        enabled = job.hasGlb,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.ViewInAr, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("In AR")
                    }
                    OutlinedButton(
                        onClick = { vm.localModelUri(job)?.let { openWith(ctx, it) { m -> vm.message = m } } },
                        enabled = load?.file != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Öffnen mit")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (!showRerender) {
                    TextButton(onClick = { showRerender = true }) {
                        Icon(Icons.Outlined.Replay, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("In anderer Qualität neu rendern")
                    }
                } else {
                    DetailSelector(detail, true) { detail = it }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showRerender = false; vm.restart(job, detail) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Neu rendern")
                    }
                }
                Warnings(job)
            }
        }
    }
}

private fun shareModel(ctx: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("model/gltf-binary")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "3D-Modell teilen"))
}

private fun openWith(ctx: Context, uri: Uri, onError: (String) -> Unit) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "model/gltf-binary")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(view, "Öffnen mit"))
    } catch (e: ActivityNotFoundException) {
        onError("Keine App für GLB-Dateien installiert")
    }
}

/** Google Scene Viewer (AR) lädt das Modell direkt vom PC im WLAN. */
private fun openAr(ctx: Context, glbUrl: String, title: String, onError: (String) -> Unit) {
    val uri = Uri.parse("https://arvr.google.com/scene-viewer/1.0").buildUpon()
        .appendQueryParameter("file", glbUrl)
        .appendQueryParameter("mode", "ar_preferred")
        .appendQueryParameter("title", title)
        .build()
    for (pkg in listOf("com.google.android.googlequicksearchbox", "com.google.ar.core")) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    onError("AR benötigt die Google-App bzw. Google Play-Dienste für AR")
}
