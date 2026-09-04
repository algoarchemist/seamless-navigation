package com.sih26168.idr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.ui.map.StreetMapView
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * TEST TOOLING ONLY (CLAUDE.md Android Rule 8 / PRD.md Section 31/32's
 * "controlled simulated" testing path) — exercises `ui/map/StreetMapView.kt`'s
 * Mapbox rendering (position marker, directional arrow, outage-anchor
 * dashed line, route line, destination pin, heading-up camera rotation,
 * follow/recenter gesture logic) with entirely client-side simulated data,
 * so it can be verified on a real device without waiting on a real GNSS
 * fix or an outdoor drive. Reached only from the existing debug screen
 * (`MainActivity.kt`'s `IdrSensorScreen`, same "Debug" entry point already
 * used for the sensor-recording/drive-logging test tools) — this file
 * NEVER reads or writes any real GNSS/DR/fusion state, and
 * `ui/screens/MapScreen.kt`'s real pipeline is completely untouched by it,
 * satisfying Rule 8's "clearly separated from the shipped demo path."
 *
 * The simulated position walks a small fixed square loop (deliberately not
 * a straight line) so every rendered feature gets exercised without any
 * button taps: a changing heading (tests marker-arrow rotation and,
 * when heading-up is toggled on, map bearing rotation), continuous
 * movement (tests the PositionSmoother glide + follow-camera recentering),
 * and, once "Simulate outage" is toggled, a fixed anchor point behind the
 * moving position (tests the dashed anchor line in DEAD_RECKONING/
 * REACQUISITION mode).
 */
private const val SIM_TICK_MS = 150L // ~6-7Hz, matching this app's real ~5-10Hz GNSS/DR tick cadence
private const val SIM_STEP_DEG = 0.00006 // roughly a few meters per tick at Chennai's latitude
private const val SIM_LOOP_TICKS = 240 // full loop period before repeating

// Starting point: central Chennai — an arbitrary real-world WGS84
// coordinate, not derived from any actual fix (Rule 13: this is
// SIMULATED test data, never presented as a measured result).
private const val SIM_START_LAT_DEG = 12.9516
private const val SIM_START_LON_DEG = 80.1912

@Composable
fun MapVerificationScreen(onExit: () -> Unit) {
    var simLatDeg by remember { mutableStateOf(SIM_START_LAT_DEG) }
    var simLonDeg by remember { mutableStateOf(SIM_START_LON_DEG) }
    var simHeadingDeg by remember { mutableStateOf(0f) }
    var isDarkTheme by remember { mutableStateOf(true) }
    var isHeadingUp by remember { mutableStateOf(false) }
    var isSimulatingOutage by remember { mutableStateOf(false) }
    var isRouteActive by remember { mutableStateOf(false) }
    val anchorLatDeg = remember { mutableStateOf<Double?>(null) }
    val anchorLonDeg = remember { mutableStateOf<Double?>(null) }

    // Walks a small square loop, tick by tick — each edge holds a constant
    // heading so the directional arrow/map-bearing rotation is visibly
    // exercised at each of the loop's 4 corners, not just once.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(SIM_TICK_MS)
            val phase = (tick % SIM_LOOP_TICKS) / (SIM_LOOP_TICKS / 4)
            val headingDeg = when (phase) {
                0 -> 0f // north
                1 -> 90f // east
                2 -> 180f // south
                else -> 270f // west
            }
            simHeadingDeg = headingDeg
            val headingRad = Math.toRadians(headingDeg.toDouble())
            // Screen/compass convention: heading is clockwise from north,
            // so north-component is cos(heading), east-component is
            // sin(heading) — same convention this app's real world-frame
            // integration (dr/WorldFrameAcceleration.kt) already documents
            // at its own boundary (CLAUDE.md Rule 9).
            simLatDeg += SIM_STEP_DEG * cos(headingRad)
            simLonDeg += SIM_STEP_DEG * sin(headingRad)
            if (isSimulatingOutage && anchorLatDeg.value == null) {
                anchorLatDeg.value = simLatDeg
                anchorLonDeg.value = simLonDeg
            }
            tick++
        }
    }

    val fakeRouteGeometry = remember(isRouteActive, simLatDeg, simLonDeg) {
        if (!isRouteActive) {
            null
        } else {
            // A short fixed-shape path ahead of the current simulated
            // position — real GeoPoints, just not derived from an actual
            // OSRM response (this is what the destination-pin/route-line
            // rendering needs to draw, not a routing-correctness test).
            listOf(
                GeoPoint(simLatDeg, simLonDeg),
                GeoPoint(simLatDeg + 0.0015, simLonDeg + 0.0010),
                GeoPoint(simLatDeg + 0.0028, simLonDeg + 0.0004),
                GeoPoint(simLatDeg + 0.0040, simLonDeg + 0.0022),
            )
        }
    }

    val mode = if (isSimulatingOutage) GnssMode.DEAD_RECKONING else GnssMode.GNSS_AIDED

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            StreetMapView(
                currentLatDeg = simLatDeg,
                currentLonDeg = simLonDeg,
                anchorLatDeg = anchorLatDeg.value,
                anchorLonDeg = anchorLonDeg.value,
                mode = mode,
                isDarkTheme = isDarkTheme,
                routeGeometry = fakeRouteGeometry,
                headingDeg = if (isHeadingUp) simHeadingDeg else null,
                markerHeadingDeg = simHeadingDeg,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Text(
                        text = "MAPBOX TEST TOOLING — simulated GNSS, not real. " +
                            "mode=$mode heading=${simHeadingDeg.toInt()}°",
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { isSimulatingOutage = !isSimulatingOutage; if (!isSimulatingOutage) { anchorLatDeg.value = null; anchorLonDeg.value = null } }) {
                        Text(text = if (isSimulatingOutage) "End sim outage" else "Simulate outage")
                    }
                    Button(onClick = { isRouteActive = !isRouteActive }) {
                        Text(text = if (isRouteActive) "Clear route" else "Simulate route")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { isHeadingUp = !isHeadingUp }) {
                        Text(text = if (isHeadingUp) "Heading-up: ON" else "Heading-up: OFF")
                    }
                    Button(onClick = { isDarkTheme = !isDarkTheme }) {
                        Text(text = if (isDarkTheme) "Dark" else "Light")
                    }
                }
                Button(onClick = onExit) {
                    Text(text = "Exit map verification")
                }
            }
        }
    }
}
