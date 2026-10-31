package com.codync.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codync.android.core.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * The ViewModel the UI talks to (iOS `AccountStore`): the active account, its computers with one
 * [AppStore] each, and the one writer of [AccountContext]. This file holds the account context's
 * lifecycle (load, retire) and the computers' list; signing in, pairing, access and links are
 * `AccountStore` extensions in the `AccountStore*.kt` files beside it.
 */
class AccountStore(application: Application) : AndroidViewModel(application) {
    internal val activityOwnerId: String = UUID.randomUUID().toString()
    internal var local = LocalStore(application)
    private var contextJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    internal var contextScope = CoroutineScope(contextJob + Dispatchers.Main.immediate)
    internal val transitions = Mutex()
    /** One change to the saved computers at a time. */
    private val computerChanges = Mutex()
    internal var cloud: CloudClient? = null
    private var sessionJob: Job? = null
    internal var linkJob: Job? = null
    internal var pairingJob: Job? = null
    internal val actions = mutableMapOf<String, Job>()
    internal val mutableState = MutableStateFlow(AccountContext())
    val state = mutableState.asStateFlow()
    private val mutableStores = MutableStateFlow<List<AppStore>>(emptyList())
    /** One store per computer, in the order the computers show. */
    val stores = mutableStores.asStateFlow()
    /** Every computer's state, by computer ID. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val computerStates: StateFlow<Map<String, AppState>> = mutableStores.flatMapLatest { stores ->
        if (stores.isEmpty()) flowOf(emptyMap()) else combine(stores.map { it.state }) { states -> states.associateBy { it.computer.id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    internal var foreground = false
    internal var widgetOwner: WidgetOwner? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { PushPreferences.splitSwitch(getApplication()) }
            transitions.withLock { loadContext(null) }
            NativeAccounts.observe(viewModelScope) { account ->
                mutableState.update { it.copy(account = account) }
                if (account.ready) transitions.withLock {
                    // Read the latest state after a concurrent logout/reset finishes.
                    val current = state.value.account
                    val desired = current.userId ?: "local"
                    if (desired != local.contextId) {
                        retireContext()
                        loadContext(current.userId)
                    } else if (cloud == null && current.userId != null) {
                        configureCloud(current.userId)
                        refreshCloud()
                    }
                }
            }
        }
    }

    fun store(id: String?): AppStore? = id?.let { wanted -> stores.value.firstOrNull { it.id == wanted } }

    internal suspend fun loadContext(userId: String?) {
        try {
            WidgetRefreshWorker.stop(getApplication())
            local = LocalStore(getApplication(), userId ?: "local")
            widgetOwner = withContext(Dispatchers.IO) { WidgetStore.activate(getApplication(), local.contextId) }
            PushDelivery.activate(getApplication(), userId)
            val computers = withContext(Dispatchers.IO) { local.computers() }
            val complete = withContext(Dispatchers.IO) { local.setupComplete() }
            val last = withContext(Dispatchers.IO) { local.lastComputer() }
            mutableState.update { AccountContext(contextId = local.referenceId, generation = it.generation + 1, account = it.account,
                computers = computers, lastComputerId = last, tasks = PushPreferences.tasks(getApplication()),
                setupComplete = complete || computers.isNotEmpty() || userId != null) }
            for (computer in computers) open(computer)
            // Outgoing files no queued message or draft of any computer needs anymore.
            withContext(Dispatchers.IO) { AttachmentFiles(getApplication(), local.directory).retainPending(stores.value.flatMap { it.pendingFiles() }) }
            if (userId != null) configureCloud(userId)
            mutableState.update { it.copy(loading = false) }
            observeWidgets(requireNotNull(widgetOwner))
            refreshCloud()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { showError(error) }
    }

    private suspend fun configureCloud(userId: String) {
        val identity = withContext(Dispatchers.IO) { local.identity() }
        cloud = CloudClient(BuildConfig.CLOUD_URL, identity) { NativeAccounts.token(userId) }
    }

    /** Join every writer before closing a context's databases or replacing its identity. */
    internal suspend fun retireContext() = withContext(NonCancellable) {
        mutableState.update { it.copy(loading = true) }
        withContext(Dispatchers.IO) { WidgetStore.retire(getApplication(), widgetOwner) }
        widgetOwner = null
        WidgetRefreshWorker.stop(getApplication())
        try { CodyncWidgets.update(getApplication()) } catch (_: Exception) { }
        val writers = actions.values.toList()
        writers.forEach { it.cancel() }; writers.forEach { it.join() }
        PushDelivery.retire(getApplication())
        val retiring = stores.value
        mutableStores.value = emptyList()
        retiring.forEach { it.retire() }
        contextJob.cancelAndJoin()
        AccountImages.retire()
        pairingJob = null
        actions.clear(); cloud = null
        withContext(Dispatchers.IO) { ConnectorOAuthBook(local.directory).clear() }
        contextJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        contextScope = CoroutineScope(contextJob + Dispatchers.Main.immediate)
    }

    /** Opens [computer]'s store and, in the foreground, its link (iOS `AccountStore.open`). */
    internal suspend fun open(computer: Computer) {
        val context = state.value
        val store = AppStore(getApplication(), local, computer, context.contextId, context.generation, contextJob, ::saveComputer) { opened ->
            for (other in stores.value) if (other !== opened) other.openBot(null)
            noteActive(opened.id)
        }
        store.usageRead = ::publishUsage
        store.open()
        mutableStores.update { list -> list.filter { it.id != computer.id } + store }
        sortStores()
        if (foreground) store.setForeground(true)
    }

    /** Forgets [id] here: its link, mirror and drafts; its bots and chats stay on the computer. */
    internal suspend fun close(id: String) {
        val store = store(id) ?: return
        mutableStores.update { list -> list.filter { it.id != id } }
        store.retire()
        computerChanges.withLock {
            val computers = state.value.computers.filter { it.id != id }
            withContext(Dispatchers.IO) {
                local.saveComputers(computers)
                local.directory.listFiles()?.filter { it.name.startsWith("mirror-$id") || it.name == "drafts-$id.json" }?.forEach { it.delete() }
            }
            mutableState.update { it.copy(computers = computers) }
        }
    }

    /** Saves a computer's latest copy among the others (a new one goes last), then shows the list. */
    internal suspend fun saveComputer(computer: Computer) = computerChanges.withLock {
        val saved = state.value.computers
        val computers = if (saved.any { it.id == computer.id }) saved.map { if (it.id == computer.id) computer else it } else saved + computer
        withContext(Dispatchers.IO) { local.saveComputers(computers) }
        mutableState.update { it.copy(computers = computers) }
    }

    /**
     * Shows the computers (with their bots) in the order of [ids], on this phone only (iOS `AccountStore.move`);
     * computers [ids] leaves out keep their places after it.
     */
    fun orderComputers(ids: List<String>) = action("orderComputers") {
        computerChanges.withLock {
            val saved = state.value.computers
            val computers = ids.mapNotNull { wanted -> saved.firstOrNull { it.id == wanted } } + saved.filter { it.id !in ids }
            if (computers == saved) return@withLock
            withContext(Dispatchers.IO) { local.saveComputers(computers) }
            mutableState.update { it.copy(computers = computers) }
        }
        sortStores()
    }

    private fun sortStores() {
        val order = state.value.computers.map { it.id }
        mutableStores.update { list -> list.sortedBy { store -> order.indexOf(store.id).let { if (it < 0) Int.MAX_VALUE else it } } }
    }

    /** The computer a chat was opened or sent on: the Usage sheet and widget follow it (iOS `lastComputerId`). */
    internal fun noteActive(id: String) {
        if (state.value.lastComputerId == id) return
        mutableState.update { it.copy(lastComputerId = id) }
        contextScope.launch(Dispatchers.IO) { local.saveLastComputer(id) }
    }

    /** Opens a chat on its computer, closing the one open on another; null closes it. */
    fun openBot(ref: BotDestination?) {
        for (store in stores.value) store.openBot(if (store.id == ref?.computerId) ref.botId else null)
    }

    fun setForeground(active: Boolean) {
        foreground = active
        PushDelivery.foreground = active || stores.value.any { it.state.value.voice != null }
        for (store in stores.value) store.setForeground(active)
        if (active && !state.value.loading) refreshCloud()
        if (!active) pairingJob?.cancel()
    }

    /** Signing in or out, removing a computer, starting over: one at a time, a second while one runs is dropped. */
    internal fun sessionAction(operation: suspend () -> Unit) {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = it.busy + "session", error = null) }
            try { operation() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutableState.update { it.copy(busy = it.busy - "session") } }
        }
    }

    /** Runs [operation] as [key], its error shown; not while loading or while [key] still runs. Whether it started. */
    internal fun action(key: String, operation: suspend () -> Unit): Boolean {
        if (state.value.loading || key in state.value.busy) return false
        mutableState.update { it.copy(busy = it.busy + key, error = null) }
        val job = contextScope.launch(start = CoroutineStart.LAZY) {
            try { operation() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { actions.remove(key); mutableState.update { it.copy(busy = it.busy - key) } }
        }
        actions[key] = job
        job.start()
        return true
    }

    internal fun showError(error: Exception) { mutableState.update { it.copy(loading = false, error = error.message ?: "Couldn't complete the action.") } }

    /** Every computer registers (or drops) this phone's push again, after the permission changed. */
    fun syncPush() { for (store in stores.value) store.syncPush() }

    /** The account's error and every computer's: the one dialog shows the first, OK clears them all. */
    fun clearError() {
        mutableState.update { it.copy(error = null) }
        for (store in stores.value) store.clearError()
    }

    private data class WidgetSource(val computers: List<Computer>, val states: List<AppState>, val current: String?)

    /** The Bots widgets show every computer's bots; Usage the last active computer's (iOS `BotsEntry`, `UsageTimeline`). */
    @OptIn(FlowPreview::class)
    private fun observeWidgets(owner: WidgetOwner) {
        contextScope.launch {
            combine(state, computerStates) { context, states -> context to states }
                .filter { (context, _) -> !context.loading && context.contextId == owner.scope }
                .map { (context, states) -> WidgetSource(context.computers, context.computers.mapNotNull { states[it.id] }, context.currentComputerId) }
                .distinctUntilChanged().debounce(350).collect { source ->
                    try {
                        val usage = source.states.firstOrNull { it.computer.id == source.current }
                        val accepted = withContext(Dispatchers.IO) {
                            WidgetStore.publish(getApplication(), owner, source.states.map { WidgetComputer(it.computer, it.bots, it.widgetBotsCurrent) },
                                usage?.computer, UsageReport.decode(usage?.usage ?: kotlinx.serialization.json.JsonObject(emptyMap())), false)
                        }
                        if (!accepted) return@collect
                        WidgetRefreshWorker.schedule(getApplication(), source.computers.isNotEmpty())
                        CodyncWidgets.update(getApplication())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(error = "Couldn't update widgets. Open State and try again.") } }
                }
        }
    }

    /** A usage read just finished on [store]: the Usage widget shows it as fresh when that's its computer. */
    private suspend fun publishUsage(store: AppStore) {
        val owner = widgetOwner ?: return
        if (state.value.currentComputerId != store.id) return
        val current = store.state.value
        withContext(Dispatchers.IO) { WidgetStore.publishUsage(getApplication(), owner, current.computer, UsageReport.decode(current.usage), true) }
        CodyncWidgets.update(getApplication())
    }

    override fun onCleared() {
        for (store in stores.value) store.close(CoroutineScope(Dispatchers.IO))
        super.onCleared()
    }
}
