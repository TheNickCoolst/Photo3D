package app.photo3d

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.File

/** Interactive 3D view of a local .glb (Google <model-viewer> bundled in assets, works offline). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModelViewer(file: File, modifier: Modifier = Modifier) {
    key(file.path) {
        AndroidView(
            factory = { c ->
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                    .addPathHandler("/models/", WebViewAssetLoader.InternalStoragePathHandler(c, File(c.filesDir, "models")))
                    .build()
                WebView(c).apply {
                    // MATCH_PARENT nötig, sonst hat der WebView-Viewport die Höhe 0
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(0)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d("Photo3DViewer", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)
                    }
                    loadUrl("https://appassets.androidplatform.net/assets/viewer.html?src=/models/${file.name}")
                }
            },
            onRelease = { it.destroy() },
            modifier = modifier,
        )
    }
}
