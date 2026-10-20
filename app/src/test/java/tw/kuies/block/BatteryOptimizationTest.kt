package tw.kuies.block

import android.content.Context
import android.content.pm.ResolveInfo
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.kuies.block.power.BatteryOptimization

/**
 * The battery-exemption contract: which screen is requested, that the answer is read from the
 * platform rather than stored, and that a device with no handler is reported instead of crashing.
 *
 * `sdk = 35` is pinned like the other Robolectric tests here: `compileSdk 37` is newer than any
 * Robolectric image, and the pinned level only changes the platform the test runs against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryOptimizationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** The direct request must be Android's own dialog, scoped to this package. */
    @Test
    fun theDirectRequestNamesTheSystemDialogForThisPackage() {
        val intent = BatteryOptimization.directRequestIntent(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    /** A fresh install holds no exemption: the card must not claim one on its own. */
    @Test
    fun theExemptionIsNotClaimedBeforeAndroidSaysSo() {
        assertFalse(BatteryOptimization.isExempt(context))
    }

    /** ... and the answer follows the platform both ways, which is what makes a revocation visible. */
    @Test
    fun theAnswerFollowsThePlatformBothWays() {
        val power = requireNotNull(context.getSystemService(PowerManager::class.java))
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BatteryOptimization.isExempt(context))

        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BatteryOptimization.isExempt(context))
    }

    /** The dialog is preferred whenever the device handles it. */
    @Test
    fun theDirectDialogIsPreferredWhenTheDeviceHandlesIt() {
        val packageManager = shadowOf(context.packageManager)
        packageManager.addResolveInfoForIntent(
            BatteryOptimization.directRequestIntent(context),
            ResolveInfo()
        )
        packageManager.addResolveInfoForIntent(BatteryOptimization.settingsListIntent(), ResolveInfo())

        assertEquals(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            BatteryOptimization.requestIntent(context)?.action
        )
    }

    /** A device without the direct dialog gets Android's exemption list instead. */
    @Test
    fun theExemptionListIsUsedWhenTheDirectDialogIsUnavailable() {
        shadowOf(context.packageManager).addResolveInfoForIntent(
            BatteryOptimization.settingsListIntent(),
            ResolveInfo()
        )

        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            BatteryOptimization.requestIntent(context)?.action
        )
    }

    /** No screen at all is a reported failure, never an exception on the way out. */
    @Test
    fun noScreenMeansNoRequestAndNoCrash() {
        assertNull(BatteryOptimization.requestIntent(context))
        assertFalse(BatteryOptimization.openRequest(context))
    }
}
