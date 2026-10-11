package tw.kuies.block

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tw.kuies.block.vpn.KuiesVpnService

/**
 * Verifies the manifest wiring of the VPN service, which is a product requirement but cannot be
 * checked from a plain JVM unit test. Requires a connected device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class VpnServiceDeclarationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun vpnServiceIsDeclaredWithBindPermissionAndForegroundType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, KuiesVpnService::class.java),
            0
        )

        assertEquals(
            "The VPN service must be protected by BIND_VPN_SERVICE",
            Manifest.permission.BIND_VPN_SERVICE,
            info.permission
        )
        assertFalse("The VPN service must not be exported to other apps", info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue(
                "Android 14+ requires a foreground service type",
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0
            )
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun theAppRequestsEverythingTheTunnelNeeds() {
        val info: PackageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
        ).forEach { permission ->
            assertTrue("missing permission: $permission", requested.contains(permission))
        }
    }
}
