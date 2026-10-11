package tw.kuies.block

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
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
        setContent {
            KuiesBlockTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    KuiesBlockApp(
                        lifecycleOwner = this,
                        appVersion = appVersion,
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
