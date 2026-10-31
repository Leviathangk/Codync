package com.codync.android

import com.codync.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

// Push registration, notification settings and the links notifications and widgets open.

fun AppStore.syncPush() = action("push") {
    val active = channel?.takeIf { it.state.value is LinkState.Ready } ?: return@action
    try { PushDelivery.sync(getApplication(), local, state.value.computer, active) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { PushDelivery.schedule(getApplication()) }
}

/** Saved with the computer on this phone (iOS `AccountStore.setNotifications`). */
fun AppStore.notifications(enabled: Boolean) = action("notificationSettings") {
    changeComputer { it.copy(notifications = enabled) }
    Analytics.capture(AnalyticsEvent.SettingChanged(PhoneSetting.Push, enabled))
    if (!enabled) NotificationDisplay.cancel(getApplication(), 1, "${local.referenceId}/$id/")
    syncPush(); PushDelivery.schedule(getApplication())
}
