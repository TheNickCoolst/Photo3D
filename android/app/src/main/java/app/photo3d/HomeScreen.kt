package app.photo3d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    var showServer by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo3D", fontWeight = FontWeight.SemiBold) },
                actions = {
                    AssistChip(
                        onClick = { showServer = true },
                        label = {
                            Text(
                                if (vm.serverUrl == null) "Kein PC" else vm.serverName,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp),
                            )
                        },
                        leadingIcon = {
                            Box {
                                Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp))
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(8.dp)
                                        .background(if (vm.online) Color(0xFF34A853) else Color(0xFFEA4335), CircleShape)
                                )
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            if (vm.online) {
                ExtendedFloatingActionButton(
                    onClick = { vm.newScan() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Neuer Scan") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.pullRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when {
                !vm.online -> ConnectHint(vm) { showServer = true }
                vm.jobs.isEmpty() -> EmptyHint()
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.jobs, key = { it.id }) { job -> JobCard(vm, job) }
                }
            }
        }
    }
    if (showServer) ServerDialog(vm) { showServer = false }
}

@Composable
private fun JobCard(vm: MainViewModel, job: ScanJob) {
    Card(onClick = { vm.openJob(job.id) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            JobThumb(vm.api, job, Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stateLabel(job) + if (job.active) " · ${(job.progress * 100).toInt()} %" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val counts = buildList {
                    if (job.images > 0) add(plural(job.images, "Foto", "Fotos"))
                    if (job.videos > 0) add(plural(job.videos, "Video", "Videos"))
                    add(SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date((job.created * 1000).toLong())))
                }.joinToString(" · ")
                Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (job.active) {
                    Spacer(Modifier.height(6.dp))
                    if (job.state == "queued") LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                job.done -> Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF34A853))
                job.failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConnectHint(vm: MainViewModel, onManual: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Suche Render-PC im WLAN…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (vm.serverUrl != null) "${vm.serverName} ist gerade nicht erreichbar." else
                "Starte auf dem PC den Photo3D-Server:\n./start.sh\nHandy und PC müssen im selben Netzwerk sein.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onManual) { Text("PC manuell auswählen") }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.ViewInAr, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Noch keine Modelle", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Fotografiere oder filme ein Objekt rundherum und tippe auf „Neuer Scan“. Der PC baut daraus ein 3D-Modell.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val found by vm.discovery.servers.collectAsState()
    var manual by remember { mutableStateOf(vm.serverUrl?.removePrefix("http://") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Render-PC") },
        text = {
            Column {
                Text("Im Netzwerk gefunden:", style = MaterialTheme.typography.labelLarge)
                if (found.isEmpty()) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Suche…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                found.forEach { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(s.url.removePrefix("http://")) },
                        leadingContent = { Icon(Icons.Outlined.Computer, null) },
                        trailingContent = {
                            if (s.url == vm.serverUrl) Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF34A853))
                        },
                        modifier = Modifier.clickable { vm.setServer(s.url, s.name); onDismiss() },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Oder Adresse eingeben:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    placeholder = { Text("192.168.178.20:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = manual.isNotBlank(),
                onClick = { vm.setServer(manual, ""); onDismiss() },
            ) { Text("Verbinden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
