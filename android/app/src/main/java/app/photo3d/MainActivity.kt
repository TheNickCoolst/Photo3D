package app.photo3d

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.handleShareIntent(intent)
        setContent {
            Photo3DTheme {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(vm.message) {
                    vm.message?.let {
                        vm.message = null
                        snackbar.showSnackbar(it)
                    }
                }
                BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }
                AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(vm, snackbar)
                        Screen.NewScan -> NewScanScreen(vm, snackbar)
                        Screen.Job -> JobScreen(vm, snackbar)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground(true)
    }

    override fun onStop() {
        super.onStop()
        vm.onForeground(false)
    }
}

@Composable
fun Photo3DTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFF9ECAFF))
        else -> lightColorScheme(primary = Color(0xFF2D5BB5), secondary = Color(0xFF4F7FD9))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
