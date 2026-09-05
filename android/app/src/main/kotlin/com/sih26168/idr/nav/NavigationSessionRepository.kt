package com.sih26168.idr.nav

import android.content.Context
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.RoutesSetCallback
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.BannerInstructionsObserver
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.maneuver.model.Maneuver
import com.mapbox.navigation.tripdata.maneuver.model.ManeuverError
import com.mapbox.navigation.tripdata.maneuver.model.ManeuverOptions
import com.mapbox.navigation.tripdata.shield.api.MapboxRouteShieldApi
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.bindgen.Expected
import com.sih26168.idr.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the Mapbox Navigation SDK's active-guidance session (PRD.md Section 7
 * 2026-09-05 amendment, developer-requested override of CLAUDE.md Rule 2/4's
 * normal discussion-first process — see that amendment for the full
 * reasoning, including the billing/scope tradeoffs flagged before starting).
 *
 * REAL ROUTING-BACKEND SPLIT (not just a UI addition, CLAUDE.md Rule 9 —
 * naming this explicitly since it's a non-obvious boundary): voice/banner/
 * lane instruction TEXT is generated server-side by Mapbox's own Directions
 * API and does not exist in a plain OSRM response, so routes used for
 * ACTIVE GUIDANCE come from [requestRoute] (Mapbox's routing engine via this
 * SDK), NOT `routing/RoutingRepository.kt`'s OSRM call. The route PREVIEW
 * (search, distance/duration estimate, ActiveRouteCard) is UNCHANGED and
 * still OSRM — only entering active turn-by-turn switches backends.
 *
 * A singleton object, not a class instance per screen, because
 * [MapboxNavigationApp]/[MapboxNavigation] are themselves process-wide
 * singletons in this SDK's own design (one native navigator per app) —
 * matching that shape rather than fighting it with a second layer of
 * instance management this app doesn't need.
 */
object NavigationSessionRepository {

    data class NavUiState(
        val isActive: Boolean = false,
        val isFreeDrive: Boolean = false,
        /** WORLD-frame WGS84 lat/lon (CLAUDE.md Rule 14) — the live map-matched position while a trip session is running (free-drive or active guidance). Null before the first location update arrives. */
        val currentLatDeg: Double? = null,
        val currentLonDeg: Double? = null,
        val currentHeadingDeg: Float? = null,
        val distanceRemainingMeters: Double? = null,
        val durationRemainingSeconds: Double? = null,
        val maneuvers: Expected<ManeuverError, List<Maneuver>>? = null,
        val isRerouting: Boolean = false,
        /**
         * REAL BUG FOUND (2026-09-05, user report: "the orange line path to
         * destination is not coming... just a free drive window with the
         * blue circle"): `ui/screens/ActiveGuidanceScreen.kt` originally
         * drew no route line at all — a deliberate scope-cut documented as
         * "simpler than StreetMapView.kt", but that reads as a real broken
         * feature to a user starting guidance, not an acceptable
         * simplification. Fixed by decoding the active route's geometry
         * here (once, in [startActiveGuidance]) rather than in the
         * Composable, since [NavigationRoute.directionsRoute]'s geometry is
         * an encoded polyline6 string, not something the UI layer should
         * need to know how to decode.
         */
        val routeGeometryPoints: List<Point> = emptyList(),
    )

    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    /** Feeds the live map-matched location to whichever screen's MapView is currently showing active guidance/free-drive — same pattern the SDK's own examples use for the location puck. */
    val locationProvider = NavigationLocationProvider()

    private var maneuverApi: MapboxManeuverApi? = null
    private var speechApi: MapboxSpeechApi? = null
    private var voicePlayer: MapboxVoiceInstructionsPlayer? = null
    private var isSetUp = false

    private val routeProgressObserver = RouteProgressObserver { routeProgress: RouteProgress ->
        _state.value = _state.value.copy(
            distanceRemainingMeters = routeProgress.distanceRemaining.toDouble(),
            durationRemainingSeconds = routeProgress.durationRemaining,
            maneuvers = maneuverApi?.getManeuvers(routeProgress),
        )
    }

    private val bannerInstructionsObserver = BannerInstructionsObserver { _ ->
        // MapboxManeuverView (ActiveGuidanceScreen.kt) renders straight from
        // routeProgressObserver's per-tick maneuvers above — this observer
        // exists so a future banner-specific need (e.g. a dedicated banner
        // sound/haptic) has somewhere to hook in without restructuring;
        // nothing else currently needs the raw BannerInstructions object.
    }

    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        val api = speechApi ?: return@VoiceInstructionsObserver
        val player = voicePlayer ?: return@VoiceInstructionsObserver
        api.generate(voiceInstructions) { expected ->
            expected.fold(
                { error ->
                    // Real network/parse failure generating the announcement
                    // audio (CLAUDE.md Rule 13) — falls back to nothing
                    // audible rather than a silent crash; the on-screen
                    // MapboxManeuverView instruction text is unaffected
                    // either way.
                    player.play(error.fallback) { announcement -> api.clean(announcement) }
                },
                { speechValue ->
                    player.play(speechValue.announcement) { announcement -> api.clean(announcement) }
                },
            )
        }
    }

    // True while this app's OWN dead-reckoned estimate is driving the puck
    // (see [setDeadReckonedPosition]). @Volatile because it is written from
    // the UI thread (the Composable effect in ui/screens/ActiveGuidanceScreen.kt)
    // and read on whichever thread the Navigation SDK delivers its location
    // callbacks on — two different threads, so the write must be visible to
    // the reader without a lock.
    @Volatile
    private var isDeadReckoningActive = false

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) = Unit
        override fun onNewLocationMatcherResult(locationMatcherResult: com.mapbox.navigation.core.trip.session.LocationMatcherResult) {
            // While dead reckoning owns the puck, the SDK's own map-matched
            // position must NOT also write to it — the two would fight for
            // the same puck every tick. This is the whole point of the DR
            // handover: during a GNSS outage the SDK's own estimate is
            // exactly the thing that has stopped being trustworthy.
            if (isDeadReckoningActive) return
            val enhanced = locationMatcherResult.enhancedLocation
            locationProvider.changePosition(enhanced, locationMatcherResult.keyPoints)
            _state.value = _state.value.copy(
                currentLatDeg = enhanced.latitude,
                currentLonDeg = enhanced.longitude,
                currentHeadingDeg = enhanced.bearing?.toFloat(),
            )
        }
    }

    /**
     * REAL BUG FOUND (2026-09-05, user report: "the dead reckoning system is
     * not working in the app... the marker freezes completely, doesn't move
     * at all during outage", testing via turn-by-turn navigation): this
     * screen's location puck is fed by [locationProvider], which until now
     * was written ONLY from [locationObserver] above — i.e. the Mapbox
     * Navigation SDK's own GPS-backed map matcher. That path has no
     * connection whatsoever to this project's dead-reckoning pipeline
     * (`gnss/GnssModeRepository` -> `dr/BaselineDeadReckoningRepository` ->
     * `fusion/StateEstimator`), so the moment GNSS was actually denied the
     * SDK simply stopped delivering location updates and the puck froze in
     * place — while the app's own DR estimate underneath was updating
     * correctly the entire time. `ui/screens/MapScreen.kt`'s marker never had
     * this bug because it projects `fusion/StateEstimator`'s fused position
     * itself; entering active guidance silently swapped in a second,
     * DR-unaware position source.
     *
     * Passing a real [latDeg]/[lonDeg] hands the puck over to this app's own
     * fused DR estimate and suppresses the SDK's own position updates until
     * handed back; passing null for either hands control back to the SDK
     * (GNSS trusted again). Called from `ui/screens/ActiveGuidanceScreen.kt`,
     * which already receives the GNSS mode + fused position it needs to
     * decide which of the two is live.
     *
     * @param latDeg/[lonDeg] WORLD-frame WGS84 degrees (CLAUDE.md Rule 14) —
     *   the fused DR position, already projected out of `fusion/StateEstimator`'s
     *   local East/North meter frame by the caller.
     * @param headingDeg WORLD-frame compass heading, degrees clockwise from
     *   north (CLAUDE.md Rule 15), or null if not known.
     *
     * KNOWN LIMITATION (deliberate scope cut, CLAUDE.md "ship the simpler
     * version... record the deferred sophistication as Future Work"): this
     * moves the PUCK and this class's own published position only. It does
     * NOT feed the DR estimate back into [MapboxNavigation]'s navigator, so
     * route progress (distance/duration remaining, maneuver advance, voice
     * announcements, off-route rerouting) still stalls during an outage and
     * resumes on reacquisition. Doing that properly means replacing the
     * SDK's location engine with a custom DeviceLocationProvider for the
     * whole trip session, which changes the position source for the working
     * turn-by-turn path too and cannot be validated without a real outdoor
     * drive through a real outage.
     */
    fun setDeadReckonedPosition(latDeg: Double?, lonDeg: Double?, headingDeg: Float?) {
        if (latDeg == null || lonDeg == null) {
            isDeadReckoningActive = false
            return
        }
        isDeadReckoningActive = true
        locationProvider.changePosition(
            Location.Builder()
                .latitude(latDeg)
                .longitude(lonDeg)
                .bearing(headingDeg?.toDouble())
                .timestamp(System.currentTimeMillis())
                .build(),
        )
        _state.value = _state.value.copy(
            currentLatDeg = latDeg,
            currentLonDeg = lonDeg,
            currentHeadingDeg = headingDeg,
        )
    }

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.registerBannerInstructionsObserver(bannerInstructionsObserver)
            mapboxNavigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
            mapboxNavigation.registerLocationObserver(locationObserver)
            // Automatic rerouting on off-route (developer-requested feature)
            // is MapboxNavigation's own built-in MapboxRerouteController —
            // this just confirms it's on rather than assuming the SDK
            // default (CLAUDE.md Rule 13).
            mapboxNavigation.setRerouteEnabled(true)
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.unregisterBannerInstructionsObserver(bannerInstructionsObserver)
            mapboxNavigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
            mapboxNavigation.unregisterLocationObserver(locationObserver)
        }
    }

    /** Idempotent — safe to call from every screen that might need a session; only does real setup once per process. */
    fun initIfNeeded(context: Context) {
        if (isSetUp) return
        isSetUp = true
        val appContext = context.applicationContext
        maneuverApi = MapboxManeuverApi(
            MapboxDistanceFormatter(DistanceFormatterOptions.Builder(appContext).build()),
            ManeuverOptions.Builder().build(),
            MapboxRouteShieldApi(),
        )
        speechApi = MapboxSpeechApi(appContext, BuildConfig.MAPBOX_PUBLIC_TOKEN)
        // REAL CRASH FOUND on-device (2026-09-05): unlike MapboxSpeechApi's
        // second constructor param (a real access token), this class's
        // second param is a LANGUAGE CODE ("en"), not a token -- passing
        // the Mapbox token here crashed with a NullPointerException deep in
        // Android's TextToSpeech.isLanguageAvailable/Locale.getISO3Language,
        // since it was trying to parse the token string as a Locale tag.
        voicePlayer = MapboxVoiceInstructionsPlayer(appContext, "en")
        MapboxNavigationApp.registerObserver(navigationObserver)
    }

    /**
     * Requests a REAL route from Mapbox's own Directions API (via this SDK)
     * with voice/banner instructions enabled — see this file's header doc
     * for why this is a different backend from the OSRM preview route.
     */
    fun requestRoute(
        originLatDeg: Double,
        originLonDeg: Double,
        destLatDeg: Double,
        destLonDeg: Double,
        onReady: (List<NavigationRoute>) -> Unit,
        onFailed: () -> Unit,
    ) {
        val mapboxNavigation = MapboxNavigationApp.current() ?: run { onFailed(); return }
        val routeOptions = RouteOptions.builder()
            .coordinatesList(
                listOf(
                    Point.fromLngLat(originLonDeg, originLatDeg),
                    Point.fromLngLat(destLonDeg, destLatDeg),
                ),
            )
            .profile("driving-traffic")
            .geometries("polyline6")
            .overview("full")
            .steps(true)
            .voiceInstructions(true)
            .bannerInstructions(true)
            .build()
        mapboxNavigation.requestRoutes(
            routeOptions,
            object : NavigationRouterCallback {
                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) = onReady(routes)
                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) = onFailed()
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) = onFailed()
            },
        )
    }

    @OptIn(com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI::class)
    fun startActiveGuidance(routes: List<NavigationRoute>) {
        val mapboxNavigation = MapboxNavigationApp.current() ?: return
        mapboxNavigation.setNavigationRoutes(routes, 0, RoutesSetCallback { })
        mapboxNavigation.startTripSessionWithPermissionCheck()
        // geometry() is an encoded polyline6 string (matches
        // RouteOptions.geometries("polyline6") in requestRoute above) --
        // decoded once here, not per-frame, since the primary route's
        // geometry doesn't change between reroutes without a fresh
        // requestRoutes/startActiveGuidance call.
        val geometry = routes.firstOrNull()?.directionsRoute?.geometry()
        val points = if (geometry != null) com.mapbox.geojson.utils.PolylineUtils.decode(geometry, 6) else emptyList()
        _state.value = _state.value.copy(isActive = true, isFreeDrive = false, routeGeometryPoints = points)
    }

    /** Free-drive mode (developer-requested feature): a trip session with no route set — map-matches the live position to the road network without turn-by-turn guidance. */
    @OptIn(com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI::class)
    fun startFreeDrive() {
        val mapboxNavigation = MapboxNavigationApp.current() ?: return
        mapboxNavigation.setNavigationRoutes(emptyList())
        mapboxNavigation.startTripSessionWithPermissionCheck()
        _state.value = _state.value.copy(isActive = true, isFreeDrive = true, routeGeometryPoints = emptyList())
    }

    fun stop() {
        // Cleared before the early return below so a session that ended
        // while dead reckoning owned the puck can never leave the SDK's own
        // location updates suppressed for the NEXT session.
        isDeadReckoningActive = false
        val mapboxNavigation = MapboxNavigationApp.current() ?: return
        mapboxNavigation.setNavigationRoutes(emptyList())
        mapboxNavigation.stopTripSession()
        _state.value = NavUiState()
    }
}
