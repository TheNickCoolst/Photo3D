package app.photo3d

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String) : Exception(message)

data class ScanJob(
    val id: String,
    val name: String,
    val created: Double,
    val state: String,
    val stage: String,
    val progress: Float,
    val eta: Double?,
    val images: Int,
    val videos: Int,
    val frames: Int,
    val warnings: List<String>,
    val error: String?,
    val hasGlb: Boolean,
    val hasUsdz: Boolean,
    val hasObj: Boolean,
    val hasThumb: Boolean,
    val glbSize: Long,
    val usdzSize: Long,
    val queuePosition: Int,
    val detail: String?,
    val finished: Double?,
) {
    val active get() = state == "queued" || state == "processing"
    val done get() = state == "done"
    val failed get() = state == "failed" || state == "cancelled"

    companion object {
        fun from(o: JSONObject): ScanJob {
            val out = o.optJSONObject("outputs") ?: JSONObject()
            val sizes = o.optJSONObject("sizes") ?: JSONObject()
            val warn = o.optJSONArray("warnings") ?: JSONArray()
            return ScanJob(
                id = o.getString("id"),
                name = o.optString("name"),
                created = o.optDouble("created", 0.0),
                state = o.optString("state"),
                stage = o.optString("stage"),
                progress = o.optDouble("progress", 0.0).toFloat(),
                eta = if (o.isNull("eta")) null else o.optDouble("eta"),
                images = o.optInt("images"),
                videos = o.optInt("videos"),
                frames = o.optInt("frames"),
                warnings = List(warn.length()) { warn.optString(it) },
                error = if (o.isNull("error")) null else o.optString("error"),
                hasGlb = out.optBoolean("glb"),
                hasUsdz = out.optBoolean("usdz"),
                hasObj = out.optBoolean("obj"),
                hasThumb = o.optBoolean("thumb"),
                glbSize = sizes.optLong("glb", -1),
                usdzSize = sizes.optLong("usdz", -1),
                queuePosition = o.optInt("queuePosition"),
                detail = o.optJSONObject("options")?.optString("detail")?.takeIf { it.isNotBlank() },
                finished = if (o.isNull("finished")) null else o.optDouble("finished"),
            )
        }
    }
}

data class RenderOptions(
    val detail: String = "medium",
    val masking: Boolean = true,
    val highSensitivity: Boolean = false,
    val framesPerVideo: Int = 120,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("detail", detail)
        .put("masking", masking)
        .put("highSensitivity", highSensitivity)
        .put("framesPerVideo", framesPerVideo)
}

/** Tiny HTTP client for the Photo3D server on the Mac. */
class ServerApi(baseUrl: String) {
    val base = baseUrl.trimEnd('/')

    fun fileUrl(jobId: String, file: String) = "$base/api/jobs/$jobId/$file"

    private fun conn(path: String, method: String = "GET", readTimeout: Int = 8000): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = readTimeout
        return c
    }

    private fun errorText(c: HttpURLConnection, code: Int): String {
        val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val msg = body?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
        return msg?.takeIf { it.isNotBlank() } ?: "HTTP $code"
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, readTimeout: Int = 8000): String {
        val c = conn(path, method, readTimeout)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code >= 400) throw ApiException(errorText(c, code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun info(): JSONObject = withContext(Dispatchers.IO) { JSONObject(request("/api/info", readTimeout = 3000)) }

    suspend fun jobs(): List<ScanJob> = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/api/jobs"))
        List(a.length()) { ScanJob.from(a.getJSONObject(it)) }
    }

    suspend fun createJob(name: String): ScanJob = withContext(Dispatchers.IO) {
        ScanJob.from(JSONObject(request("/api/jobs", "POST", JSONObject().put("name", name))))
    }

    suspend fun start(jobId: String, options: RenderOptions): ScanJob = withContext(Dispatchers.IO) {
        ScanJob.from(JSONObject(request("/api/jobs/$jobId/start", "POST", options.toJson())))
    }

    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        request("/api/jobs/$jobId/cancel", "POST", JSONObject())
    }

    suspend fun delete(jobId: String) = withContext(Dispatchers.IO) {
        request("/api/jobs/$jobId", "DELETE")
    }

    /** Streams one file to the server. [size] < 0 means unknown (chunked upload). */
    suspend fun upload(
        jobId: String, name: String, size: Long,
        openStream: () -> InputStream, onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val c = conn("/api/jobs/$jobId/files/${Uri.encode(name)}", "PUT", 120_000)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            if (size > 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(256 * 1024)
            openStream().use { input ->
                c.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    var last = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        if (sent - last >= 512 * 1024) {
                            onProgress(sent); last = sent
                        }
                    }
                    onProgress(sent)
                }
            }
            val code = c.responseCode
            if (code >= 400) throw ApiException(errorText(c, code))
        } finally {
            c.disconnect()
        }
    }

    suspend fun download(
        jobId: String, file: String, out: OutputStream, onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val c = conn("/api/jobs/$jobId/$file", readTimeout = 60_000)
        try {
            val code = c.responseCode
            if (code >= 400) throw ApiException(errorText(c, code))
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last >= 256 * 1024) {
                        onProgress(done, total); last = done
                    }
                }
                onProgress(done, total)
            }
        } finally {
            c.disconnect()
        }
    }

    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = conn(path, readTimeout = 5000)
            try {
                if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
