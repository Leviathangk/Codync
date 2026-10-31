package com.codync.android.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ReviewTest {
    private val day = 86_400_000L

    private fun user(nonce: String, turn: Long) = Entry("u$turn", turn, "b", turn, "user", turn,
        buildJsonObject { put("text", "do it"); put("clientNonce", nonce); put("status", "sent") })
    private fun answer(turn: Long) = Entry("a$turn", turn, "b", turn, "agent", turn, buildJsonObject { put("text", "done"); put("final", true) })

    @Test fun `only a final answer to this phone's own message finishes it`() {
        val sent = mapOf("n1" to 0L)
        assertEquals(listOf("n1"), ReviewCompletion.match(listOf(user("n1", 1), answer(1)), sent, day))
        // Still working, or a notice in the turn (a stop, a failure).
        assertNull(ReviewCompletion.match(listOf(user("n1", 1)), sent, day))
        val notice = Entry("x", 2, "b", 2, "notice", 1, buildJsonObject { put("text", "Stopped.") })
        assertNull(ReviewCompletion.match(listOf(user("n1", 1), notice, answer(1)), sent, day))
        // Another device's message, or one sent over a month ago.
        assertNull(ReviewCompletion.match(listOf(user("n2", 1), answer(1)), sent, day))
        assertNull(ReviewCompletion.match(listOf(user("n1", 1), answer(1)), sent, 31 * day))
    }

    @Test fun `eligible after a week, three active days and five answers, once per version`() {
        var history = ReviewHistory(firstUse = 0, completed = 5)
        for (d in 0 until 3) history = history.recordingActivity(d * day + 1, java.time.ZoneOffset.UTC)
        assertFalse(history.eligible(6 * day, "2.34.0"))
        assertTrue(history.eligible(7 * day, "2.34.0"))
        history = history.recordingRequest(7 * day, "2.34.0")
        assertFalse(history.eligible(200 * day, "2.34.0"))
        // Another version needs 120 days and five more answers.
        assertFalse(history.eligible(100 * day, "2.35.0"))
        assertTrue(history.copy(completed = 10).eligible(130 * day, "2.35.0"))
    }

    @Test fun `returning from the chat whose answer was read makes one opportunity, and asking uses it`() {
        val events = mutableListOf<ReviewInvitation.Event>()
        var history = ReviewHistory(firstUse = 0, completed = 4, activeDays = setOf("a", "b", "c"))
        val invitation = ReviewInvitation(history, { history = it }) { events += it }
        val now = 10 * day
        invitation.setActive(true, now)
        invitation.sent("n1", now)
        invitation.viewed("chat", listOf(user("n1", 1), answer(1)), now + 1_000)
        invitation.returned("other", "2.34.0", now + 120_000)
        assertNull(invitation.opportunity)
        invitation.viewed("chat", listOf(user("n1", 1), answer(1)), now + 1_000)
        invitation.sent("n2", now + 2_000)
        invitation.viewed("chat", listOf(user("n2", 2), answer(2)), now + 3_000)
        invitation.returned("chat", "2.34.0", now + 120_000)
        val token = requireNotNull(invitation.opportunity)
        assertTrue(invitation.request(token, "2.34.0", now + 122_000))
        assertFalse(invitation.request(token, "2.34.0", now + 123_000))
        assertEquals(listOf(ReviewInvitation.Event.Opportunity(6), ReviewInvitation.Event.Requested(6)), events)
        assertEquals(setOf("2.34.0"), history.requestedVersions)
    }

    @Test fun `play version codes read back as versions and a newer one is reminded until dismissed`() {
        assertEquals("2.34.0", UpdateReminder.versionOf(2_034_000))
        assertEquals("2.34.12", UpdateReminder.versionOf(2_034_012))
        assertEquals("2.35.0", UpdateReminder.app("2.34.0", "2.35.0", null))
        assertNull(UpdateReminder.app("2.34.0", "2.35.0", "2.35.0"))
        assertNull(UpdateReminder.app("2.35.0", "2.35.0", null))
        assertNull(UpdateReminder.app("2.34.0", null, null))
    }
}
