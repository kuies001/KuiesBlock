package tw.kuies.block

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.ui.theme.DarkErrorContainer
import tw.kuies.block.ui.theme.DarkOnErrorContainer
import tw.kuies.block.ui.theme.DarkOnPrimary
import tw.kuies.block.ui.theme.DarkOnSecondaryContainer
import tw.kuies.block.ui.theme.DarkPrimary
import tw.kuies.block.ui.theme.DarkSecondaryContainer
import tw.kuies.block.ui.theme.LightErrorContainer
import tw.kuies.block.ui.theme.LightOnErrorContainer
import tw.kuies.block.ui.theme.LightOnPrimary
import tw.kuies.block.ui.theme.LightOnSecondaryContainer
import tw.kuies.block.ui.theme.LightPrimary
import tw.kuies.block.ui.theme.LightSecondaryContainer

/**
 * The protection button resolves its colours from the Material 3 [androidx.compose.material3.ColorScheme]
 * (primary / secondaryContainer / errorContainer). These tests pin down the two things a pure state
 * mapping cannot express: that the three roles are actually distinguishable, and that both the light
 * and dark scheme keep the label readable.
 */
class ProtectionActionColorSchemeTest {

    @Test
    fun theThreeActionRolesAreDistinctInTheLightScheme() {
        assertDistinct(LightPrimary, LightSecondaryContainer, LightErrorContainer, "light")
    }

    @Test
    fun theThreeActionRolesAreDistinctInTheDarkScheme() {
        assertDistinct(DarkPrimary, DarkSecondaryContainer, DarkErrorContainer, "dark")
    }

    @Test
    fun everyRoleHasASeparateLightAndDarkVariant() {
        assertTrue("primary", LightPrimary != DarkPrimary)
        assertTrue("secondaryContainer", LightSecondaryContainer != DarkSecondaryContainer)
        assertTrue("errorContainer", LightErrorContainer != DarkErrorContainer)
    }

    @Test
    fun theLabelStaysReadableInBothSchemes() {
        assertTrue("light primary", contrast(LightPrimary, LightOnPrimary) >= MIN_CONTRAST)
        assertTrue("light secondaryContainer", contrast(LightSecondaryContainer, LightOnSecondaryContainer) >= MIN_CONTRAST)
        assertTrue("light errorContainer", contrast(LightErrorContainer, LightOnErrorContainer) >= MIN_CONTRAST)
        assertTrue("dark primary", contrast(DarkPrimary, DarkOnPrimary) >= MIN_CONTRAST)
        assertTrue("dark secondaryContainer", contrast(DarkSecondaryContainer, DarkOnSecondaryContainer) >= MIN_CONTRAST)
        assertTrue("dark errorContainer", contrast(DarkErrorContainer, DarkOnErrorContainer) >= MIN_CONTRAST)
    }

    private fun assertDistinct(a: Color, b: Color, c: Color, scheme: String) {
        assertTrue("$scheme primary vs secondaryContainer", a != b)
        assertTrue("$scheme primary vs errorContainer", a != c)
        assertTrue("$scheme secondaryContainer vs errorContainer", b != c)
    }

    /** WCAG 2.x contrast ratio between two sRGB colours. */
    private fun contrast(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** WCAG relative luminance, computed from the sRGB channels. */
    private fun luminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private companion object {
        /** WCAG AA for normal text. */
        const val MIN_CONTRAST = 4.5
    }
}
