package app.photo3d

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val thumbCache = LruCache<String, ImageBitmap>(120)

@Composable
private fun ThumbBox(bitmap: ImageBitmap?, fallback: ImageVector, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(fallback, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thumbnail of a job, loaded from the server. */
@Composable
fun JobThumb(api: ServerApi?, job: ScanJob, modifier: Modifier) {
    val key = if (api != null && job.hasThumb) "${api.base}/${job.id}" else null
    val bmp by produceState(key?.let { thumbCache.get(it) }, key) {
        if (key == null || value != null || api == null) return@produceState
        val bytes = api.bytes("/api/jobs/${job.id}/thumb.jpg") ?: return@produceState
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }?.also { thumbCache.put(key, it) }
    }
    ThumbBox(bmp, Icons.Outlined.ViewInAr, modifier)
}

/** Thumbnail of a locally selected photo/video. */
@Composable
fun MediaThumb(media: Media, modifier: Modifier) {
    val ctx = LocalContext.current
    val key = media.uri.toString()
    val bmp by produceState(thumbCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(media.uri, Size(300, 300), null) }.getOrNull()
                ?: if (media.isVideo) runCatching { videoFrame(ctx, media.uri) }.getOrNull() else null
        }?.asImageBitmap()?.also { thumbCache.put(key, it) }
    }
    ThumbBox(bmp, Icons.Outlined.Image, modifier)
}

private fun videoFrame(ctx: android.content.Context, uri: Uri) = MediaMetadataRetriever().run {
    try {
        setDataSource(ctx, uri)
        getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 300, 300)
    } finally {
        release()
    }
}

fun formatBytes(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 * 1024 -> String.format(Locale.GERMANY, "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f MB", b / 1024.0 / 1024)
    else -> String.format(Locale.GERMANY, "%.2f GB", b / 1024.0 / 1024 / 1024)
}

fun formatEta(sec: Double): String {
    val s = sec.toLong()
    return when {
        s < 60 -> "< 1 Min."
        s < 3600 -> "ca. ${(s + 30) / 60} Min."
        else -> "ca. ${s / 3600} Std. ${(s % 3600) / 60} Min."
    }
}

val detailLevels = listOf(
    "preview" to "Schnell",
    "medium" to "Mittel",
    "full" to "Hoch",
    "raw" to "Maximal",
)

fun detailLabel(d: String) = when (d) {
    "reduced" -> "Reduziert"
    else -> detailLevels.firstOrNull { it.first == d }?.second ?: d
}

fun detailDescription(d: String) = when (d) {
    "preview" -> "Grobe Vorschau, wenige Minuten. Gut zum Testen der Aufnahmen."
    "reduced" -> "Reduziert – kleine Datei, fürs Web."
    "medium" -> "Ausgewogen – gute Qualität für Handy & Web."
    "full" -> "Hohe Detailtreue, große Texturen. Dauert länger."
    "raw" -> "Maximale Rohdaten, sehr große Dateien. Für Nachbearbeitung."
    else -> ""
}

fun stateLabel(job: ScanJob) = when (job.state) {
    "uploading" -> "Upload unvollständig"
    "queued" -> if (job.queuePosition > 1) "Warteschlange (Platz ${job.queuePosition})" else "Startet gleich"
    "processing" -> job.stage
    "done" -> "Fertig"
    "failed" -> "Fehlgeschlagen"
    "cancelled" -> "Abgebrochen"
    else -> job.stage
}

fun plural(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many
