package com.sih26168.idr.gnss

/** PRD.md Section 18's four states, in the exact order the state machine moves through them. */
enum class GnssMode {
    GNSS_AIDED,
    TRANSITION,
    DEAD_RECKONING,
    REACQUISITION,
}

/**
 * One recorded state transition — CLAUDE.md Rule 17 requires every
 * transition be logged with state, timestamp, and trigger condition so
 * a test run can be replayed and transition timing verified afterward.
 */
data class GnssModeTransition(
    val fromMode: GnssMode,
    val toMode: GnssMode,
    val atMs: Long,
    val triggerDescription: String,
)

/**
 * The GNSS_AIDED / TRANSITION / DEAD_RECKONING / REACQUISITION hysteresis
 * state machine (PRD.md Section 18), driven by repeated calls to
 * [evaluate] with a boolean "is GNSS good right now" ([GnssQuality]) and
 * a wall-clock timestamp. Pure Kotlin — no Android dependency — so the
 * dwell-time/hysteresis logic is unit-testable on the plain JVM with a
 * synthetic sequence of (time, gnssGoodNow) inputs (CLAUDE.md Rule 19),
 * instead of only verifiable by an actual GNSS outage on a real device.
 *
 * Hysteresis (CLAUDE.md Rule 16): leaving GNSS_AIDED requires GNSS to
 * have been continuously bad for [outageEnterDwellMs] (a single bad
 * sample cannot flip the mode); leaving DEAD_RECKONING requires GNSS to
 * have been continuously good for [reacquisitionEnterDwellMs].
 * TRANSITION is a fixed-duration freeze/average window (PRD.md Section
 * 18) — it always waits out [transitionDwellMs] in full before deciding,
 * once, which way to go next, by design (not a single-sample-flip bug:
 * nothing changes mid-freeze). REACQUISITION is NOT symmetric with this,
 * and deliberately so: it advances to GNSS_AIDED only once GNSS has been
 * good CONTINUOUSLY for the full [reacquisitionDwellMs], but bails back
 * to DEAD_RECKONING the instant GNSS goes bad at any point in that
 * window, without waiting out the dwell first — there's no reason to
 * keep blending toward a fix already known bad. REAL BUG FIX
 * (2026-08-26, on-device test — mode flapping constantly between
 * DEAD_RECKONING and REACQUISITION): REACQUISITION used to check
 * gnssGoodNow only ONCE, right at the dwell boundary — exactly the
 * single-noisy-sample flip Rule 16 prohibits, just on the exit side
 * instead of the entry side. With marginal/indoor GNSS accuracy hovering
 * near [GnssQuality]'s threshold, that one unlucky sample failed on
 * essentially every cycle, so REACQUISITION never once reached
 * GNSS_AIDED in practice.
 *
 * TRANSITION/REACQUISITION here are state-machine bookkeeping only —
 * this class itself does NOT blend GNSS and dead-reckoned position
 * estimates together (PRD.md Section 18's "freeze/average" and "blend DR
 * toward new GNSS fix" behavior). That blending is
 * fusion/PositionFusion.kt (Slice 7, driven by fusion/StateEstimator.kt
 * off this class's mode output) — this class's job stays only to detect
 * the outage and transition state correctly and honestly, not to fuse
 * positions itself.
 */
class GnssOutageDetector(
    private val outageEnterDwellMs: Long = 2_000L,
    private val reacquisitionEnterDwellMs: Long = 2_000L,
    private val transitionDwellMs: Long = 1_000L,
    private val reacquisitionDwellMs: Long = 1_000L,
) {
    var mode: GnssMode = GnssMode.GNSS_AIDED
        private set

    private var modeEnteredAtMs: Long = 0L
    private var badStreakStartMs: Long? = null
    private var goodStreakStartMs: Long? = null

    private val _transitions = mutableListOf<GnssModeTransition>()
    val transitions: List<GnssModeTransition> get() = _transitions

    /** Advances the state machine by one evaluation tick and returns the (possibly new) mode. */
    fun evaluate(nowMs: Long, gnssGoodNow: Boolean): GnssMode {
        when (mode) {
            GnssMode.GNSS_AIDED -> {
                if (gnssGoodNow) {
                    badStreakStartMs = null
                } else {
                    val streakStart = badStreakStartMs ?: nowMs.also { badStreakStartMs = it }
                    if (nowMs - streakStart >= outageEnterDwellMs) {
                        transitionTo(GnssMode.TRANSITION, nowMs, "GNSS degraded/lost for >= ${outageEnterDwellMs}ms")
                    }
                }
            }
            GnssMode.TRANSITION -> {
                if (nowMs - modeEnteredAtMs >= transitionDwellMs) {
                    if (gnssGoodNow) {
                        transitionTo(GnssMode.GNSS_AIDED, nowMs, "GNSS recovered during TRANSITION window")
                    } else {
                        transitionTo(GnssMode.DEAD_RECKONING, nowMs, "TRANSITION window elapsed, GNSS still degraded/lost")
                    }
                }
            }
            GnssMode.DEAD_RECKONING -> {
                if (!gnssGoodNow) {
                    goodStreakStartMs = null
                } else {
                    val streakStart = goodStreakStartMs ?: nowMs.also { goodStreakStartMs = it }
                    if (nowMs - streakStart >= reacquisitionEnterDwellMs) {
                        transitionTo(GnssMode.REACQUISITION, nowMs, "GNSS good for >= ${reacquisitionEnterDwellMs}ms")
                    }
                }
            }
            GnssMode.REACQUISITION -> {
                // REAL BUG FIX (2026-08-26, real on-device test — mode
                // flapping constantly between DEAD_RECKONING and
                // REACQUISITION every ~reacquisitionDwellMs): this used to
                // wait for the dwell timer, then check gnssGoodNow ONCE at
                // that single instant — exactly the "a single noisy sample
                // must never flip the mode" case CLAUDE.md Rule 16
                // prohibits, just on the EXIT side of REACQUISITION rather
                // than the entry side. With marginal/indoor GNSS accuracy
                // hovering right at GnssQuality's threshold, that one
                // unlucky sample (often landing right on a freshly-arrived,
                // still-marginal fix) failed on every single cycle in
                // practice, so REACQUISITION never once reached GNSS_AIDED.
                // Now: bail out to DEAD_RECKONING as soon as GNSS goes bad
                // at ANY point during the window (fail fast — matches this
                // branch's own "GNSS degraded again" trigger text, and
                // there is no reason to keep blending toward a fix that's
                // already known bad), and only advance to GNSS_AIDED once
                // GNSS has been good CONTINUOUSLY for the full dwell period
                // (by construction, since any bad sample bails out
                // immediately rather than waiting).
                if (!gnssGoodNow) {
                    transitionTo(GnssMode.DEAD_RECKONING, nowMs, "GNSS degraded again during REACQUISITION window")
                } else if (nowMs - modeEnteredAtMs >= reacquisitionDwellMs) {
                    transitionTo(GnssMode.GNSS_AIDED, nowMs, "REACQUISITION window elapsed with GNSS continuously good")
                }
            }
        }
        return mode
    }

    private fun transitionTo(newMode: GnssMode, nowMs: Long, trigger: String) {
        _transitions.add(GnssModeTransition(mode, newMode, nowMs, trigger))
        mode = newMode
        modeEnteredAtMs = nowMs
        badStreakStartMs = null
        goodStreakStartMs = null
    }
}
