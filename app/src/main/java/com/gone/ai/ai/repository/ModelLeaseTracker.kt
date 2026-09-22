package com.gone.ai.ai.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reference-counted ownership of the loaded model.
 *
 * WHY LEASES AND NOT A SINGLE OWNER
 *
 * The model used to be "owned" by the health monitoring service: it was the only
 * component allowed to unload it, and it did so in onDestroy. That fixed screens freeing
 * the model under monitoring, but made the reverse true — stopping monitoring freed the
 * model under chat and every document tool, which then failed with "Model not loaded"
 * until the app restarted. It also blocked the main thread in onDestroy waiting for any
 * running generation to finish.
 *
 * With leases, every component that needs the model says so for as long as it needs it.
 * The model is released only after the last lease closes and nobody reacquires it within
 * [idleUnloadMillis], so navigating between screens does not reload a 1 GB model.
 *
 * Pure: no Android, so the policy is unit-testable on virtual time.
 */
class ModelLeaseTracker(
    private val scope: CoroutineScope,
    private val idleUnloadMillis: Long,
    private val onIdle: suspend () -> Unit
) {
    private val lock = Any()
    private val holders = LinkedHashMap<Long, String>()
    private var nextId = 1L
    private var pendingRelease: Job? = null

    /** True when no lease is open. */
    val isIdle: Boolean get() = synchronized(lock) { holders.isEmpty() }

    /** Owners of currently open leases, for diagnostics. */
    val holderNames: List<String> get() = synchronized(lock) { holders.values.toList() }

    fun acquire(owner: String): Lease = synchronized(lock) {
        pendingRelease?.cancel()
        pendingRelease = null
        val id = nextId++
        holders[id] = owner
        Lease(id, owner)
    }

    private fun release(id: Long) {
        synchronized(lock) {
            if (holders.remove(id) == null || holders.isNotEmpty()) return
            pendingRelease?.cancel()
            pendingRelease = scope.launch {
                delay(idleUnloadMillis)
                if (isIdle) onIdle()
            }
        }
    }

    /** An open claim on the model. Closing it twice is harmless. */
    inner class Lease internal constructor(private val id: Long, val owner: String) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) release(id)
        }
    }
}
