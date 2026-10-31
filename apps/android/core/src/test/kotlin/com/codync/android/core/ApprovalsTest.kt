package com.codync.android.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalsTest {
    private val codex = AgentModels(emptyList(), modes = listOf(
        AgentMode("auto", "Auto review", "Only ask for actions detected as potentially unsafe"), AgentMode("read-only", "Read-only")),
        currentModeId = "auto")

    @Test fun newBotsStartInTheAgentsAutoModeAndAsk() {
        val draft = BotDraft(name = "New")
        assertEquals("ask" to "auto", draft.permission to draft.mode)
        assertEquals("auto", draft.body().getValue("mode").jsonPrimitive.content)
    }

    @Test fun approvalsListTheAgentsModesThenApproveAutomatically() {
        val choices = ApprovalChoice.all(codex)
        assertEquals(listOf("Auto review", "Read-only", "Approve automatically"), choices.map { it.label })
        assertEquals("Only ask for actions detected as potentially unsafe", choices.first().note)
        var draft = BotDraft(name = "Bot", backend = "codex")
        assertEquals("Auto review", ApprovalChoice.selected(draft, choices, codex)?.label)
        assertEquals("Auto review", ApprovalChoice.selected(draft.copy(mode = null), choices, codex)?.label)
        draft = draft.withApproval(choices[1].id)
        assertEquals("ask" to "read-only", draft.permission to draft.mode)
        draft = draft.withApproval(ApprovalChoice.APPROVE)
        assertEquals("auto" to null, draft.permission to draft.mode)
        // Approve automatically clears the mode on the host too.
        assertEquals(JsonNull, draft.copy(id = "b").body()["mode"])
        assertEquals("Approve automatically", ApprovalChoice.selected(draft, choices, codex)?.label)
    }

    @Test fun anAgentWithoutAutoAsksAndOneStillLoadingOffersAuto() {
        val plain = AgentModels(emptyList(), modes = emptyList())
        val choices = ApprovalChoice.all(plain)
        assertEquals(listOf("Ask me", "Approve automatically"), choices.map { it.label })
        assertEquals("Ask me", ApprovalChoice.selected(BotDraft(name = "Bot"), choices, plain)?.label)
        assertEquals(listOf("Auto", "Ask me", "Approve automatically"), ApprovalChoice.all(null).map { it.label })
        // Another agent's ids mean nothing: a picked mode becomes its Auto mode.
        assertEquals("auto", BotDraft(name = "Bot", mode = "plan").withAgent("codex").mode)
        assertEquals(null, BotDraft(name = "Bot", mode = null).withAgent("codex").mode)
    }
}
