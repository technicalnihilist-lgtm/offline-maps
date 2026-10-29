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
    private var speech: SpeechGuidance? = null
    private var lastRerouteAt = 0L
    private var rerouteAttempts = 0

    val speechGuidance: SpeechGuidance? get() = speech

    /** Calculates a route without starting navigation - used by the route preview. */
    fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: TravelProfile,
    ): RouteResult = locator.routingEngine.route(origin, destination, profile)

    /**
     * Starts guidance. The route is calculated from the current GPS fix (or [origin] when
     * supplied); nothing is hardcoded.
     */
    fun start(
        destination: PlaceResult,
        profile: TravelProfile,
        origin: GeoPoint? = null,
        voiceEnabled: Boolean = true,
    ): Result<Unit> {
        val from = origin ?: locator.locationSource.lastKnown()?.point
            ?: return Result.failure(
                IllegalStateException("Waiting for a GPS fix - move to an open area and try again."),
            )

        val result = locator.routingEngine.route(from, destination.point, profile)
        val plan = when (result) {
            is RouteResult.Success -> result.plan
            is RouteResult.Failure -> return Result.failure(IllegalStateException(result.message))
        }

        stopInternal()

        tracker = NavigationTracker(plan)
        announcer = GuidanceAnnouncer(
            speak = { text -> speech?.speak(text) },
            metricUnits = locator.settings.current().metricUnits,
        )

        if (voiceEnabled) {
            val engine = SpeechGuidance(locator.appContext)
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
        val state = currentTracker.onLocation(fix)
        _session.update { session ->
            session?.copy(state = state, gpsAvailable = true, gpsWeak = state.gpsSignalWeak, rerouting = false)
        }
        announcer?.onState(state)

        if (NavigationTracker.shouldRecalculate(state)) maybeReroute(fix)
    }

    private fun maybeReroute(fix: LocationFix) {
        val now = System.currentTimeMillis()
        if (now - lastRerouteAt < REROUTE_COOLDOWN_MS) return
        if (rerouteAttempts >= MAX_REROUTE_ATTEMPTS) return
        val current = _session.value ?: return
        lastRerouteAt = now
        rerouteAttempts++
        _session.update { it?.copy(rerouting = true) }

        scope.launch {
            when (val result = locator.routingEngine.route(fix.point, current.plan.destination, current.profile)) {
                is RouteResult.Success -> {
                    tracker?.updatePlan(result.plan)
                    rerouteAttempts = 0
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
        )
    }

    fun stop() {
        stopInternal()
        _session.value = null
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        speech?.shutdown()
        speech = null
        tracker = null
        announcer = null
        rerouteAttempts = 0
    }

    private companion object {
        const val TAG = "NavigationManager"
        const val REROUTE_COOLDOWN_MS = 12_000L
        const val MAX_REROUTE_ATTEMPTS = 5
    }
}
