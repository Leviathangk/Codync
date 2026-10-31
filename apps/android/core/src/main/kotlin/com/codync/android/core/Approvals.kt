package com.codync.android.core

import kotlinx.serialization.Serializable

/** One of the modes an agent works in (Claude's Manual / Plan / Auto, Codex's Read-only / Auto review…). */
@Serializable data class AgentMode(val id: String, val name: String, val description: String? = null) {
    companion object {
        /** Codync's id for the agent's Auto mode, whatever the agent calls it: new bots start there. */
        const val AUTO = "auto"
    }
}

/** A bot's Approvals choice: [note] is the agent's own words, untranslated; [detail] is Codync's, to translate. */
data class ApprovalChoice(val id: String, val label: String, val note: String?, val detail: String) {
    companion object {
        const val ASK = "ask"
        const val APPROVE = "approve"
        private fun modeId(mode: String) = "mode:$mode"

        /**
         * The agent's modes, its Auto mode first, then Codync approving everything. An agent without modes offers Ask me
         * instead; while its list loads (or from an older host), Auto and Ask me.
         */
        fun all(catalog: AgentModels?): List<ApprovalChoice> {
            val ask = ApprovalChoice(ASK, "Ask me", null, "You'll get an approval card and a notification whenever the agent asks.")
            val approve = ApprovalChoice(APPROVE, "Approve automatically", null,
                "Tool requests are approved automatically. Your agent's own settings (like Claude Code's permission rules) still apply.")
            val modes = catalog?.modes
                ?: return listOf(ApprovalChoice(modeId(AgentMode.AUTO), "Auto", null, "The agent decides what's safe and asks you about the rest."), ask, approve)
            if (modes.isEmpty()) return listOf(ask, approve)
            return modes.map { ApprovalChoice(modeId(it.id), it.name, it.description, "Anything the agent still asks about comes to you as an approval card.") } + approve
        }

        /** The choice a bot's setting shows as: an unset mode is the one the agent starts in. */
        fun selected(draft: BotDraft, choices: List<ApprovalChoice>, catalog: AgentModels?): ApprovalChoice? {
            if (draft.permission == "auto") return choices.firstOrNull { it.id == APPROVE }
            val mode = draft.mode ?: catalog?.currentModeId
            return mode?.let { m -> choices.firstOrNull { it.id == modeId(m) } } ?: choices.firstOrNull { it.id == ASK } ?: choices.firstOrNull()
        }
    }
}

/** The draft with an Approvals choice picked. */
fun BotDraft.withApproval(id: String): BotDraft = when (id) {
    ApprovalChoice.APPROVE -> copy(permission = "auto", mode = null)
    ApprovalChoice.ASK -> copy(permission = "ask", mode = null)
    else -> copy(permission = "ask", mode = id.removePrefix("mode:"))
}
