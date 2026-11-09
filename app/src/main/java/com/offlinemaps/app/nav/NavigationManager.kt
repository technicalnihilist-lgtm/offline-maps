package com.offlinemaps.app.nav

import android.util.Log
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.app.speech.SpeechGuidance
import com.offlinemaps.core.common.Formatters
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import com.offlinemaps.core.common.PlaceResult
import com.offlinemaps.core.common.TravelProfile
import com.offlinemaps.core.routing.GuidanceAnnouncer
import com.offlinemaps.core.routing.InstructionMapper
import com.offlinemaps.core.routing.NavigationState
import com.offlinemaps.core.routing.NavigationTracker
import com.offlinemaps.core.routing.RerouteGate
import com.offlinemaps.core.routing.RoutePlan
import com.offlinemaps.core.routing.RouteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the UI and the notification need to describe an active navigation session. */
data class NavigationSessionUi(
    val destinationName: String,
    val profile: TravelProfile,
    val plan: RoutePlan,
    val state: NavigationState?,
    val gpsAvailable: Boolean,
    val gpsWeak: Boolean,
    val rerouting: Boolean,
    val voiceEnabled: Boolean,
    val muted: Boolean,
    val message: String? = null,
) {
    val nextInstruction: String
        get() = (state?.nextManeuver ?: plan.maneuvers.firstOrNull())?.text ?: "Continue"

    val nextInstructionDistance: String
        get() = Formatters.distance(state?.distanceToNextManeuverMeters ?: 0.0)

    val remainingDistance: String
        get() = Formatters.distance(state?.remainingMeters ?: plan.distanceMeters)

    val remainingTime: String
        get() = Formatters.duration(state?.remainingSeconds ?: plan.durationSeconds)

    val etaMillis: Long
        get() = System.currentTimeMillis() + (state?.remainingSeconds ?: plan.durationSeconds) * 1000
}

/**
 * Owns the live navigation loop: GPS updates in, [NavigationTracker] state out, speech on the way,
 * and a recalculation when the driver leaves the route.
 *
 * The loop lives here (not in the service) so the same logic drives the in-app UI and the
 * foreground service notification. The service only keeps the process alive.
 */
class NavigationManager(private val locator: ServiceLocator) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _session = MutableStateFlow<NavigationSessionUi?>(null)
    val session: StateFlow<NavigationSessionUi?> = _session.asStateFlow()

    private var job: Job? = null
    private var tracker: NavigationTracker? = null
    private var announcer: GuidanceAnnouncer? = null

    /**
     * The engine speaking in the current session, or null when the session runs without voice.
     *
     * [sharedSpeech] outlives a session: a [SpeechGuidance] binds the system speech service and
     * takes hundreds of milliseconds to become ready, so it is built once and reused instead of
     * being created and shut down on every start (OPTIMIZATION.md F-01).
     */
    private var speech: SpeechGuidance? = null
    private var sharedSpeech: SpeechGuidance? = null
    /**
     * Decides when a deviation may be answered with a recalculation: a cooldown, a minimum distance
     * travelled since the last attempt (so a stationary off-route fix does not loop), and an attempt
     * budget. OPTIMIZATION.md E-04.
     */
    private val rerouteGate = RerouteGate()

    val speechGuidance: SpeechGuidance? get() = speech

    /** The process-wide speech engine, created on first use. */
    private fun speechEngine(): SpeechGuidance =
        sharedSpeech ?: SpeechGuidance(locator.appContext).also { sharedSpeech = it }

    /**
     * Calculates a route without starting navigation - used by the route preview.
     *
     * Suspends on [Dispatchers.Default] because the engine may have to read the graph back from
     * disk first: a memory trim closes it (OPTIMIZATION.md E-01), and loading it is disk I/O plus
     * a graph import that must never run on the main thread.
     */
    suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: TravelProfile,
    ): RouteResult = withContext(Dispatchers.Default) {
        locator.routingEngine.route(origin, destination, profile)
    }

    /**
     * Starts guidance. The route is calculated from the current GPS fix (or [origin] when
     * supplied); nothing is hardcoded.
     */
    suspend fun start(
        destination: PlaceResult,
        profile: TravelProfile,
        origin: GeoPoint? = null,
        voiceEnabled: Boolean = true,
    ): Result<Unit> {
        val from = origin ?: locator.locationSource.lastKnown()?.point
            ?: return Result.failure(
                IllegalStateException("Waiting for a GPS fix - move to an open area and try again."),
            )

        // Off the main thread: the graph may have been released by a memory trim and has to be
        // read back from disk before the first route of the session (OPTIMIZATION.md E-01).
        val result = withContext(Dispatchers.Default) {
            locator.routingEngine.route(from, destination.point, profile)
        }
        val plan = when (result) {
            is RouteResult.Success -> result.plan
            is RouteResult.Failure -> return Result.failure(IllegalStateException(result.message))
        }

        stopInternal()

        tracker = NavigationTracker(plan)
        announcer = GuidanceAnnouncer(
            speak = { text -> speech?.speak(text, SpeechGuidance.Priority.INSTRUCTION) },
            notice = { text -> speech?.speak(text, SpeechGuidance.Priority.NOTICE) },
            metricUnits = locator.settings.current().metricUnits,
        )

        if (voiceEnabled) {
            val engine = speechEngine()
            speech = engine
            engine.initialise { available, message ->
                if (!available) {
                    _session.update { it?.copy(message = message ?: "Voice guidance unavailable") }
                }
            }
            engine.setLanguage(locator.settings.current().ttsLanguageTag)
            engine.setVolume(locator.settings.current().ttsVolume)
        }

        _session.value = NavigationSessionUi(
            destinationName = destination.name,
            profile = profile,
            plan = plan,
            state = null,
            gpsAvailable = locator.locationSource.hasPermission(),
            gpsWeak = false,
            rerouting = false,
            voiceEnabled = voiceEnabled,
            muted = !voiceEnabled,
        )

        locator.setGuidanceActive(true)
        job = scope.launch {
            locator.locationSource.updates(minTimeMs = 1_000L, minDistanceMeters = 2f)
                .catch { e ->
                    Log.w(TAG, "location stream failed", e)
                    _session.update { it?.copy(message = "GPS updates stopped: ${e.message}") }
                }
                .collect { fix -> onFix(fix) }
        }
        return Result.success(Unit)
    }

    private fun onFix(fix: LocationFix) {
        val currentTracker = tracker ?: return
        // A fix older than the one already followed is ignored whole: no state update, no
        // announcement, and no reroute decided from a position the driver has already left behind.
        val state = currentTracker.onLocation(fix) ?: return
        _session.update { session ->
            session?.copy(state = state, gpsAvailable = true, gpsWeak = state.gpsSignalWeak, rerouting = false)
        }
        announcer?.onState(state)

        // Arrival is the end of the drive: the announcement above is the last thing the session has
        // to say, so the loop is closed here rather than followed to the end of the trip
        // (OPTIMIZATION.md E-07). Without this the GPS subscription kept waking the app every second
        // for a destination already reached, recomposing the panel and re-posting the notification
        // each time, and the routing graph stayed pinned against memory trims for a reroute that
        // could never be wanted again.
        if (state.arrived) {
            stopFollowing()
            return
        }

        if (NavigationTracker.shouldRecalculate(state)) maybeReroute(fix)
    }

    /**
     * Ends the live loop once the destination is reached, keeping the last [NavigationSessionUi] so
     * the panel and the notification go on showing the arrived state exactly as they did before -
     * nothing on screen changes, it simply stops being recomputed every second. The speech engine is
     * left alone so the repeat and mute controls still answer; [stop] still does the full teardown.
     */
    private fun stopFollowing() {
        job?.cancel()
        job = null
        tracker = null
        announcer = null
        rerouteGate.reset()
        // No reroute can follow, so a memory trim may now close the routing graph.
        locator.setGuidanceActive(false)
    }

    private fun maybeReroute(fix: LocationFix) {
        val now = System.currentTimeMillis()
        if (!rerouteGate.shouldAttempt(now, fix.point)) return
        val current = _session.value ?: return
        rerouteGate.recordAttempt(now, fix.point)
        _session.update { it?.copy(rerouting = true) }

        scope.launch {
            when (val result = locator.routingEngine.route(fix.point, current.plan.destination, current.profile)) {
                is RouteResult.Success -> {
                    // The destination was reached while this recalculation was in flight. Applying
                    // the new plan would restart progress tracking on a trip that is over.
                    if (_session.value?.state?.arrived == true) {
                        _session.update { it?.copy(rerouting = false) }
                        return@launch
                    }
                    tracker?.updatePlan(result.plan)
                    rerouteGate.onRerouted()
                    announcer?.onRerouted()
                    _session.update { it?.copy(plan = result.plan, rerouting = false, message = null) }
                }
                is RouteResult.Failure -> _session.update {
                    it?.copy(rerouting = false, message = "Could not recalculate: ${result.message}")
                }
            }
        }
    }

    fun setMuted(value: Boolean) {
        speech?.setMuted(value)
        _session.update { it?.copy(muted = value) }
    }

    fun repeatLastInstruction() {
        val current = _session.value ?: return
        val maneuver = current.state?.nextManeuver ?: current.plan.maneuvers.firstOrNull() ?: return
        speech?.speak(
            InstructionMapper.spoken(
                type = maneuver.type,
                streetName = maneuver.streetName,
                distanceToManeuverMeters = current.state?.distanceToNextManeuverMeters,
            ),
            SpeechGuidance.Priority.INSTRUCTION,
        )
    }

    fun stop() {
        stopInternal()
        _session.value = null
    }

    private fun stopInternal() {
        locator.setGuidanceActive(false)
        job?.cancel()
        job = null
        // Only silence this session: the engine itself is kept for the next one (F-01).
        speech?.stop()
        speech = null
        tracker = null
        announcer = null
        rerouteGate.reset()
    }

    /**
     * Shuts the shared speech engine down once nothing is speaking any more.
     *
     * Called from the `onDestroy` of both components that keep guidance alive (the activity and the
     * foreground service), so the engine is released with them instead of living for the whole
     * process - but only when no session still needs it, which is what lets one engine serve
     * several navigation sessions instead of being rebuilt on every start (OPTIMIZATION.md F-01).
     */
    fun releaseSpeech() {
        if (_session.value != null) return
        speech?.stop()
        speech = null
        sharedSpeech?.shutdown()
        sharedSpeech = null
    }

    private companion object {
        const val TAG = "NavigationManager"
    }
}
