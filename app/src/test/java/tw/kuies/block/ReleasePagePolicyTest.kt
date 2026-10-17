package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kuies.block.update.ReleasePagePolicy

/**
 * The address policy that keeps "前往下載" from ever leaving for somewhere it should not.
 *
 * A release page is data from the network, so the tests below are about what a hostile or broken
 * response can and cannot make the dialog open.
 */
class ReleasePagePolicyTest {

    @Test
    fun acceptsThisProjectsOwnReleasePages() {
        val tag = "https://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1"
        val latest = "https://github.com/kuies001/KuiesBlock/releases/latest"

        assertEquals(tag, ReleasePagePolicy.officialUrlOrNull(tag))
        assertEquals(latest, ReleasePagePolicy.officialUrlOrNull(latest))
    }

    @Test
    fun refusesAnyOtherHostIncludingALookAlike() {
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "https://github.com.evil.example/kuies001/KuiesBlock/releases/tag/v0.4.1"
            )
        )
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "https://evil.example/kuies001/KuiesBlock/releases/tag/v0.4.1"
            )
        )
        // The user-info form: the real host here is evil.example, not github.com.
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "https://github.com@evil.example/kuies001/KuiesBlock/releases/tag/v0.4.1"
            )
        )
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "https://raw.githubusercontent.com/kuies001/KuiesBlock/releases/tag/v0.4.1"
            )
        )
    }

    @Test
    fun refusesAPlainHttpAddress() {
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "http://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1"
            )
        )
    }

    @Test
    fun refusesAPathThatIsNotUnderTheReleasePages() {
        assertNull(ReleasePagePolicy.officialUrlOrNull("https://github.com/kuies001/KuiesBlock"))
        assertNull(
            ReleasePagePolicy.officialUrlOrNull("https://github.com/other/repo/releases/tag/v1.0.0")
        )
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "https://github.com/kuies001/KuiesBlock/archive/refs/tags/v0.4.1.zip"
            )
        )
    }

    @Test
    fun refusesWhatIsNotAnAddressAtAll() {
        assertNull(ReleasePagePolicy.officialUrlOrNull(null))
        assertNull(ReleasePagePolicy.officialUrlOrNull(""))
        assertNull(ReleasePagePolicy.officialUrlOrNull("   "))
        assertNull(ReleasePagePolicy.officialUrlOrNull("not a url"))
        assertNull(ReleasePagePolicy.officialUrlOrNull("javascript:alert(1)"))
        assertNull(ReleasePagePolicy.officialUrlOrNull("file:///etc/hosts"))
        assertNull(
            ReleasePagePolicy.officialUrlOrNull(
                "ftp://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1"
            )
        )
    }
}
