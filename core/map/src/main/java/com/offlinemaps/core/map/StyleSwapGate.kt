package com.offlinemaps.core.map

/**
 * Decides which style document a controller's overlay state belongs to across rebuilds
 * (OPTIMIZATION.md H-05).
 *
 * Handing a new document to MapLibre destroys the style that was loaded before it, and the new one
 * only becomes usable when its callback fires - asynchronously, on the main looper. Two things can
 * go wrong in that window and neither is visible on screen:
 *
 *  * a write that arrives between `setStyle` and the callback still targets the old [Style] object,
 *    which is already torn down on the native side (the app's own `style` reference is what keeps
 *    the Java object alive, so nothing crashes loudly - it simply has nothing behind it);
 *  * two rebuilds in quick succession (a theme flip, an offline region registered while a style is
 *    still loading) mean the callback of the superseded rebuild can be delivered after the newer
 *    one started. Adopting it would install the overlays on a style the map no longer shows, and
 *    after a teardown it would add sources to a destroyed map.
 *
 * So each rebuild is issued a token, and only the callback holding the token of the rebuild that is
 * still pending may attach. [detach] retires both, which is what makes a callback that arrives
 * after the controller is destroyed inert.
 *
 * Deliberately free of MapLibre types - it is a counter and two slots - so the rule is unit-testable
 * on the JVM.
 */
class StyleSwapGate {

    private var issued: Long = 0L
    private var pending: Long? = null
    private var live: Long? = null

    /**
     * Issues the token for a rebuild. Any style attached before this is no longer owned by the
     * caller, and an attach carrying an older token will be refused.
     */
    fun beginSwap(): Long {
        issued += 1
        pending = issued
        live = null
        return issued
    }

    /**
     * Attaches the style [token] belongs to. False when the token is not the one still pending -
     * a superseded rebuild, a repeat call, or a callback that arrived after [detach].
     */
    fun attach(token: Long): Boolean {
        if (token != pending) return false
        pending = null
        live = token
        return true
    }

    /** Retires the pending and the live token, so nothing can attach until the next [beginSwap]. */
    fun detach() {
        pending = null
        live = null
    }
}
