package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.ManualRuleDraft
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.ui.AllowBlockPresentation

/**
 * What the allow/block section tells the user.
 *
 * The confirmation added on 2026-10-16 is what makes an accepted rule visible at all: the row itself
 * can sit below the fold, so the sentence the field shows is the only feedback the user is certain to
 * see, and it has to name both the domain and the scope that were actually stored.
 */
class AllowBlockPresentationTest {

    @Test
    fun theConfirmationNamesTheDomainAndTheScopeThatWereStored() {
        assertEquals(
            "已新增：ads.example（含所有子網域）",
            AllowBlockPresentation.addedMessage(ManualRule("ads.example", MatchScope.SUBDOMAIN))
        )
        assertEquals(
            "已新增：ads.example（僅此網域）",
            AllowBlockPresentation.addedMessage(ManualRule("ads.example", MatchScope.EXACT))
        )
    }

    @Test
    fun theAllowNoteIsVerbatim() {
        assertEquals(
            "允許清單只會略過 KuiesBlock 本機封鎖，上游 DNS 服務仍可能阻擋該網域。",
            AllowBlockPresentation.ALLOW_UPSTREAM_NOTE
        )
    }

    @Test
    fun everyRejectionHasItsOwnSentence() {
        val messages = ManualRuleDraft.Rejection.entries.map {
            AllowBlockPresentation.rejectionMessage(it)
        }

        assertTrue("a rejection must never be silent", messages.none { it.isBlank() })
        assertEquals(
            "different reasons need different wording, or the user cannot act on them",
            messages.size,
            messages.distinct().size
        )
    }
}
