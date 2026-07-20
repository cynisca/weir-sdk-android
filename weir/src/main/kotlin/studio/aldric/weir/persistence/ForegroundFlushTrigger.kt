package studio.aldric.weir.persistence

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * App-foreground flush trigger — the Android analogue of iOS's
 * `UIApplication.willEnterForegroundNotification` observer inside `EventQueue`.
 *
 * Kept as an injectable interface (not hard-wired into [EventQueue]) so unit
 * tests never depend on a real Android lifecycle: [EventQueue] holds a nullable
 * [ForegroundFlushTrigger] and tests simply pass none. Production wiring (via
 * `Weir.present` / `EventQueue.configureIngest`) supplies
 * [ProcessLifecycleForegroundTrigger].
 */
interface ForegroundFlushTrigger {
    /** Begin observing app-foreground transitions; invoke [onForeground] on each. */
    fun register(onForeground: () -> Unit)

    /** Stop observing (idempotent). */
    fun unregister()
}

/**
 * Default [ForegroundFlushTrigger] backed by
 * `androidx.lifecycle.ProcessLifecycleOwner`. `ON_START` (whole-app foreground)
 * is the closest analogue to iOS's `willEnterForeground`.
 *
 * [register]/[unregister] touch `ProcessLifecycleOwner`, which must be reached
 * from the main thread — call them from a main-thread context (e.g.
 * `Weir.present`), never a binder/background thread.
 */
class ProcessLifecycleForegroundTrigger : ForegroundFlushTrigger, DefaultLifecycleObserver {
    private var callback: (() -> Unit)? = null

    override fun register(onForeground: () -> Unit) {
        callback = onForeground
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        callback?.invoke()
    }

    override fun unregister() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        callback = null
    }
}
