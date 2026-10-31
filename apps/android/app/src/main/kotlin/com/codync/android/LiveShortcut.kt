package com.codync.android

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.codync.android.core.Bot
import com.codync.android.core.BotDestination
import com.codync.android.core.LinkState
import com.codync.android.design.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Android's counterparts of the iPhone's Shortcuts actions (docs/features/ios-shortcuts.md): "Talk to Codync" in the
// app icon's shortcuts calls the lead bot like Open Live Mode, and text shared from another app goes to a bot's
// message box like Send Text to Bot with Open Chat on.

/** The app icon's "Talk to Codync" shortcut. */
internal object LiveShortcut {
    const val ACTION = "com.codync.android.action.TALK"

    /** Published while a computer is paired; the launcher keeps it until the next account without one. */
    fun publish(context: Context, paired: Boolean) {
        if (!paired) { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID)); return }
        val shortcut = ShortcutInfoCompat.Builder(context, ID).setShortLabel(context.getString(R.string.talk_shortcut))
            .setIcon(IconCompat.createWithResource(context, com.codync.android.design.R.drawable.sf_waveform))
            .setIntent(Intent(ACTION).setClass(context, MainActivity::class.java)).build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    private const val ID = "talk"
}

/** The lead the shortcut calls: the one on the computer picked in Settings, else the first computer's in the user's order that has one (iOS `ShortcutBotQuery.lead`). */
internal fun shortcutLead(computers: List<AppState>, chosen: String?): Pair<AppState, Bot>? {
    val leads = computers.mapNotNull { state -> state.bots.firstOrNull { it.lead && !it.deleted }?.let { state to it } }
    return leads.firstOrNull { it.first.computer.id == chosen } ?: leads.firstOrNull()
}

/**
 * Opens the lead's chat and starts a call once its computer is connected, as Open Live Mode without a bot does;
 * with no lead (deleted, not synced yet) it opens Bots to pick one, and an unreachable computer is an error.
 */
fun AccountStore.talkToLead() {
    linkJob?.cancel()
    linkJob = contextScope.launch {
        val ready = withTimeoutOrNull(30_000) { activated() } ?: return@launch
        val (state, lead) = shortcutLead(ready.computers.mapNotNull { store(it.id)?.state?.value }, ready.shortcutLeadComputer)
            ?: return@launch openPage(LinkPage.Bots)
        val store = store(state.computer.id) ?: return@launch
        openBot(BotDestination(ready.contextId, store.id, lead.id))
        val connected = withTimeoutOrNull(20_000) { store.state.first { it.actualConnection is LinkState.Ready } }
        if (connected == null) store.showError(java.io.IOException("Can't reach the computer. Check that it's on, then try again."))
        else store.requestCall(lead.id)
    }
}

/** Saved per account, like the iPhone's `shortcutLeadComputer`; null is Automatic. */
fun AccountStore.chooseShortcutLead(computerId: String?) {
    mutableState.update { it.copy(shortcutLeadComputer = computerId) }
    contextScope.launch(kotlinx.coroutines.Dispatchers.IO) { local.saveShortcutLead(computerId) }
}

/**
 * Settings' Shortcuts card (iOS `ShortcutLeadSettings`): which lead "Talk to Codync" calls, shown once more than one
 * computer has a lead. A computer whose lead is gone shows as Automatic, which is what the shortcut does then.
 */
@Composable internal fun ShortcutLeadSettings(app: AccountContext, states: List<AppState>, accounts: AccountStore) {
    val leads = states.mapNotNull { state -> state.bots.firstOrNull { it.lead && !it.deleted }?.let { state to it } }
    if (leads.size < 2) return
    val chosen = app.shortcutLeadComputer?.takeIf { id -> leads.any { it.first.computer.id == id } }.orEmpty()
    CardSection("Shortcuts", footer = "Talk to Codync, in the app icon's shortcuts, calls this lead. Automatic calls the lead of the first computer in your list.") {
        row {
            ValueRow("Lead to call") {
                ChoicePicker(chosen, listOf("" to tr("Automatic")) + leads.map { (state, bot) -> state.computer.id to "${bot.name} · ${state.hostName}" },
                    { accounts.chooseShortcutLead(it.ifEmpty { null }) }, label = "Lead to call")
            }
        }
    }
}

/**
 * Text shared from another app (Android's share sheet): pick a bot and its chat opens with the text in its message
 * box, after anything typed there, for you to send.
 */
@Composable internal fun ShareToBot(text: String, states: List<AppState>, accounts: AccountStore) {
    val dismiss = LocalDismissModal.current
    androidx.compose.foundation.layout.Column {
        ModalHeader("Send to a bot")
        CardForm {
            CardSection(footer = "The message waits in the chat's box until you send it.") {
                row { androidx.compose.material3.Text(text, style = Type.footnote, color = Palette.secondary, maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            for (state in states) {
                val bots = roster(state.bots).filter { it.kind != "group" || it.members.isNotEmpty() }
                if (bots.isNotEmpty()) CardSection(if (states.size > 1) state.hostName else null) {
                    for (bot in bots) row {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.plainButton {
                            accounts.store(state.computer.id)?.let { store -> store.appendDraft(bot.id, text) }
                            accounts.openBot(BotDestination(state.contextId, state.computer.id, bot.id))
                            accounts.finishSharing()
                            dismiss()
                        }) { BotRow(bot, state) }
                    }
                }
            }
        }
    }
}
