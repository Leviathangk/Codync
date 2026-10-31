package com.codync.android

import com.codync.android.core.Bot
import com.codync.android.core.Computer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutLeadTest {
    private fun computer(id: String, vararg bots: Bot) = AppState(Computer(id, id, id), loading = false, bots = bots.toList())
    private val mac = computer("mac", Bot("a", "Mac helper"), Bot("lead-mac", "Mac lead", lead = true))
    private val linux = computer("linux", Bot("lead-linux", "Linux lead", lead = true))

    @Test fun `talk to codync calls the picked computer's lead, else the first computer's in the list`() {
        assertEquals("lead-linux", shortcutLead(listOf(mac, linux), "linux")?.second?.id)
        assertEquals("lead-mac", shortcutLead(listOf(mac, linux), null)?.second?.id)
        // A picked computer whose lead is gone falls back to Automatic.
        assertEquals("lead-linux", shortcutLead(listOf(computer("mac", Bot("a", "Helper")), linux), "mac")?.second?.id)
        assertEquals("lead-mac", shortcutLead(listOf(linux.copy(bots = listOf(Bot("lead-linux", "Gone", lead = true, deleted = true))), mac), null)?.second?.id)
        assertNull(shortcutLead(listOf(computer("mac", Bot("a", "Helper"))), null))
    }
}
