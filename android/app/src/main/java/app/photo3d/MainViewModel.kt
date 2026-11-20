package app.photo3d

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.Job as CoJob

data class Media(val uri: Uri, val name: String, val size: Long, val mime: String, val isVideo: Boolean)
data class UploadProgress(val index: Int, val count: Int, val sent: Long, val total: Long)
data class ModelLoad(val jobId: String, val progress: Float = 0f, val file: File? = null, val error: String? = null)

enum class Screen { Home, NewScan, Job }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("photo3d", Context.MODE_PRIVATE)
    val discovery = Discovery(app)

    var serverUrl by mutableStateOf(prefs.getString("url", null)); private set
    var serverName by mutableStateOf(prefs.getString("name", "") ?: ""); private set
    var online by mutableStateOf(false); private set
    var jobs by mutableStateOf(emptyList<ScanJob>()); private set
    var refreshing by mutableStateOf(false); private set

    var screen by mutableStateOf(Screen.Home); private set
    var currentJobId by mutableStateOf<String?>(null); private set

    val selected = mutableStateListOf<Media>()
    var scanName by mutableStateOf("")
    var options by mutableStateOf(RenderOptions())
    var upload by mutableStateOf<UploadProgress?>(null); private set
    var model by mutableStateOf<ModelLoad?>(null); private set
    var message by mutableStateOf<String?>(null)

    var pendingCapture: Uri? = null
        private set

    private var poller: CoJob? = null
    private var uploader: CoJob? = null
    private var modelLoader: CoJob? = null

    val api: ServerApi? get() = serverUrl?.let { ServerApi(it) }
    val currentJob: ScanJob? get() = jobs.firstOrNull { it.id == currentJobId }

    init {
        viewModelScope.launch {
            discovery.servers.collect { list ->
                if (list.isEmpty()) return@collect
                val known = list.firstOrNull { it.url == serverUrl }
                if (known != null) {
                    if (!online) refresh()
                    return@collect
                }
                if (serverUrl == null || !online) {
                    val pick = list.firstOrNull { it.name == serverName } ?: list.first()
                    setServer(pick.url, pick.name)
                }
            }
        }
    }

    // ---------------------------------------------------------------- server

    fun setServer(url: String, name: String) {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!Regex("^https?://[^/]+:\\d+").containsMatchIn(u)) u = u.trimEnd('/') + ":8765"
        serverUrl = u
        serverName = name.ifBlank { u.removePrefix("http://") }
        prefs.edit { putString("url", serverUrl); putString("name", serverName) }
        online = false
        jobs = emptyList()
        viewModelScope.launch { refresh() }
    }

    fun onForeground(foreground: Boolean) {
        if (foreground) {
            discovery.start()
            if (poller == null) poller = viewModelScope.launch {
                while (isActive) {
                    refresh()
                    val fast = jobs.any { it.active } || screen == Screen.Job
                    delay(if (fast) 1500 else 5000)
                }
            }
        } else {
            discovery.stop()
            poller?.cancel()
            poller = null
        }
    }

    suspend fun refresh() {
        val a = api ?: run { online = false; return }
        try {
            jobs = a.jobs()
            online = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            online = false
        }
    }

    fun pullRefresh() {
        viewModelScope.launch {
            refreshing = true
            refresh()
            refreshing = false
        }
    }

    // ---------------------------------------------------------------- navigation

    fun openJob(id: String) {
        currentJobId = id
        screen = Screen.Job
    }

    fun newScan() {
        screen = Screen.NewScan
    }

    fun back() {
        if (screen == Screen.NewScan && upload != null) return
        screen = Screen.Home
    }

    // ---------------------------------------------------------------- media selection

    fun newCaptureUri(ext: String): Uri {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f).also { pendingCapture = it }
    }

    fun addMedia(uris: List<Uri>, mimeHint: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.distinct().filter { u -> selected.none { it.uri == u } }.mapNotNull { describe(it, mimeHint) }
            }
            selected.addAll(items)
        }
    }

    fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }
        if (uris.isNotEmpty()) {
            addMedia(uris, intent.type?.takeIf { !it.endsWith("/*") })
            screen = Screen.NewScan
        }
    }

    private fun describe(uri: Uri, mimeHint: String? = null): Media? {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment ?: "datei"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size == 0L) return null
        val mime = runCatching { cr.getType(uri) }.getOrNull() ?: mimeHint ?: when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> return null
        }
        val isVideo = mime.startsWith("video/")
        if (!isVideo && !mime.startsWith("image/")) return null
        return Media(uri, name, size, mime, isVideo)
    }

    fun removeMedia(m: Media) {
        selected.remove(m)
    }

    fun clearMedia() {
        selected.clear()
    }

    // ---------------------------------------------------------------- upload

    private fun uploadName(i: Int, m: Media): String {
        val base = m.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "file" }
        val ext = when (m.mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/x-adobe-dng" -> "dng"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            else -> m.name.substringAfterLast('.', if (m.isVideo) "mp4" else "jpg").lowercase()
        }
        return "%04d_%s.%s".format(i, base, ext)
    }

    fun startUpload() {
        val a = api ?: return
        val items = selected.toList()
        if (items.isEmpty() || uploader?.isActive == true) return
        uploader = viewModelScope.launch {
            val total = items.sumOf { maxOf(it.size, 0L) }
            upload = UploadProgress(0, items.size, 0, total)
            var jobId: String? = null
            try {
                val job = a.createJob(scanName.trim())
                jobId = job.id
                var done = 0L
                items.forEachIndexed { i, m ->
                    upload = UploadProgress(i, items.size, done, total)
                    a.upload(job.id, uploadName(i, m), m.size,
                        openStream = { ctx.contentResolver.openInputStream(m.uri) ?: throw ApiException("${m.name} nicht lesbar") },
                        onProgress = { sent -> upload = UploadProgress(i, items.size, done + sent, total) })
                    done += maxOf(m.size, 0L)
                }
                val started = a.start(job.id, options)
                selected.clear()
                scanName = ""
                File(ctx.cacheDir, "captures").deleteRecursively()
                upload = null
                refresh()
                openJob(started.id)
                message = "Hochgeladen – der PC rendert jetzt dein Modell"
            } catch (e: CancellationException) {
                upload = null
                jobId?.let { id -> viewModelScope.launch { runCatching { a.delete(id) } } }
                throw e
            } catch (e: Exception) {
                upload = null
                jobId?.let { id -> runCatching { a.delete(id) } }
                message = "Upload fehlgeschlagen: ${e.message}"
            }
        }
    }

    fun cancelUpload() {
        uploader?.cancel()
    }

    // ---------------------------------------------------------------- job actions

    fun cancelJob(job: ScanJob) = act { it.cancel(job.id) }

    fun restart(job: ScanJob, detail: String?) = act {
        val opts = if (detail != null) options.copy(detail = detail) else options
        it.start(job.id, opts)
        message = "Rendern neu gestartet"
    }

    fun deleteJob(job: ScanJob) = act {
        it.delete(job.id)
        modelDir().listFiles()?.filter { f -> f.name.startsWith(job.id) }?.forEach { f -> f.delete() }
        screen = Screen.Home
        message = "Gelöscht"
    }

    private fun act(block: suspend (ServerApi) -> Unit) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                block(a)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Fehler"
            }
        }
    }

    // ---------------------------------------------------------------- model files

    private fun modelDir() = File(ctx.filesDir, "models").apply { mkdirs() }

    fun loadModel(job: ScanJob) {
        val a = api ?: return
        if (!job.hasGlb) return
        val stamp = (job.finished ?: job.created).toLong()
        val f = File(modelDir(), "${job.id}_$stamp.glb")
        if (f.exists() && (job.glbSize <= 0 || f.length() == job.glbSize)) {
            model = ModelLoad(job.id, 1f, f)
            return
        }
        val cur = model
        if (cur != null && cur.jobId == job.id && cur.file == null && cur.error == null && modelLoader?.isActive == true) return
        modelLoader?.cancel()
        model = ModelLoad(job.id)
        modelLoader = viewModelScope.launch {
            val tmp = File(modelDir(), "${job.id}.part")
            try {
                withContext(Dispatchers.IO) {
                    tmp.outputStream().use { out ->
                        a.download(job.id, "model.glb", out) { d, t ->
                            if (t > 0) model = ModelLoad(job.id, d.toFloat() / t)
                        }
                    }
                    modelDir().listFiles()?.filter { it.name.startsWith(job.id + "_") }?.forEach { it.delete() }
                    tmp.renameTo(f)
                }
                model = ModelLoad(job.id, 1f, f)
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Exception) {
                tmp.delete()
                model = ModelLoad(job.id, error = e.message ?: "Download fehlgeschlagen")
            }
        }
    }

    fun saveAs(job: ScanJob, file: String, target: Uri) {
        val a = api ?: return
        viewModelScope.launch {
            message = "Speichere…"
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(target)?.use { out ->
                        a.download(job.id, file, out) { _, _ -> }
                    } ?: throw ApiException("Ziel nicht beschreibbar")
                }
                message = "Gespeichert"
            } catch (e: Exception) {
                message = "Speichern fehlgeschlagen: ${e.message}"
            }
        }
    }

    fun localModelUri(job: ScanJob): Uri? {
        val f = model?.takeIf { it.jobId == job.id }?.file ?: return null
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, safeFileName(job.name) + ".glb")
        f.copyTo(out, overwrite = true)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
    }

    override fun onCleared() {
        discovery.stop()
    }
}

fun safeFileName(name: String) = name.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _-]"), "_").trim().ifBlank { "modell" }
