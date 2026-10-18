package tw.kuies.block

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import tw.kuies.block.data.AppSettingsStore
import tw.kuies.block.data.ThemeMode
import tw.kuies.block.ui.KuiesBlockApp
import tw.kuies.block.ui.theme.KuiesBlockTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        val appVersion = currentVersionName()
        val settingsStore = AppSettingsStore(applicationContext)
        setContent {
            // The theme lives above KuiesBlockTheme so a change can re-skin every screen, dialog and
            // system bar without restarting the activity.
            var themeMode by remember { mutableStateOf(settingsStore.load().themeMode) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            KuiesBlockTheme(darkTheme = darkTheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    KuiesBlockApp(
                        lifecycleOwner = this,
                        appVersion = appVersion,
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            settingsStore.setThemeMode(mode)
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            ?: "0.1.0"
}
