package com.offlinemaps.app.service

/**
 * Decides whether the navigation foreground service still has a reason to run.
 *
 * The service exists for one navigation session and nothing else: the session is the only thing it
 * has to show, and the session lives in this process, so a restart cannot bring one back. That
 * leaves exactly two moments it has to stop itself - when the session ends, and when the task has
 * been removed (the user swiped the app out of recents), which is the one case where guidance would
 * otherwise outlive the screen that owns it and leave an ongoing notification behind with no app
 * to open and no drive to end.
 *
 * Pure Kotlin, no Android types, so both cases are pinned by JVM tests rather than by a device run.
 */
internal class NavigationServiceStopPolicy {

    /**
     * True when the service should call `stopSelf()`: there is no session left to show, or the task
     * is gone and nothing on screen can end the session any more.
     */
    fun shouldStop(hasSession: Boolean, taskRemoved: Boolean): Boolean = !hasSession || taskRemoved
}
