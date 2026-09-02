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
 * nothing changes mid-freeze). REACQUISITION advances to GNSS_AIDED only
 * once GNSS has been good CONTINUOUSLY for the full [reacquisitionDwellMs],
 * and bails back to DEAD_RECKONING only once GNSS has been bad
 * CONTINUOUSLY for [reacquisitionExitDwellMs] — both sides use the same
 * streak-tracking pattern as GNSS_AIDED/DEAD_RECKONING above.
 *
 * REAL BUG (2026-08-26, on-device test — mode flapping constantly between
 * DEAD_RECKONING and REACQUISITION): REACQUISITION used to check
 * gnssGoodNow only ONCE, right at the dwell boundary — a single-noisy-
 * sample flip on the exit side. That first fix made the exit instant
 * (bail on ANY single bad sample, no dwell), which cleared the 08-26
 * symptom but is itself the single-sample flip Rule 16 prohibits, just
 * moved rather than removed.
 *
 * REAL BUG #2 (2026-09-02, on-device test — mode flapping constantly
 * between DEAD_RECKONING and REACQUISITION again, ~every 7s, phone
 * stationary indoors): the instant-exit from the first fix is exactly
 * what caused this — marginal indoor GNSS accuracy flickers in and out
 * of "good" faster than [reacquisitionDwellMs], so the very next bad
 * sample after entering REACQUISITION bailed it out every single cycle,
 * and REACQUISITION never once reached GNSS_AIDED. Because [lastAidedAtMs]-
 * style "time since last real fix" bookkeeping in StateEstimator only
 * updates on a genuine GNSS_AIDED transition, this also made the
 * AI-predicted-drift number in StateEstimator climb without bound across
 * "outages" that were never really distinct outages. Fixed by giving
 * REACQUISITION's exit its own dwell, symmetric with every other
 * transition in this class: bail to DEAD_RECKONING only once GNSS has
 * been bad CONTINUOUSLY for [reacquisitionExitDwellMs], not on the first
 * bad sample. A brief blip resets the good-streak (so GNSS_AIDED still
 * requires genuine continuous-good, not just wall-clock time since entry)
 * without immediately throwing away the whole REACQUISITION attempt.
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
    private val reacquisitionExitDwellMs: Long = 2_000L,
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
                // See the class doc's "REAL BUG #2 (2026-09-02)" note: both
                // sides of REACQUISITION need their own streak-tracked
                // dwell, same pattern as GNSS_AIDED/DEAD_RECKONING above —
                // neither a single bad sample nor raw wall-clock time since
                // entry is allowed to decide the mode on its own.
                if (!gnssGoodNow) {
                    goodStreakStartMs = null
                    val streakStart = badStreakStartMs ?: nowMs.also { badStreakStartMs = it }
                    if (nowMs - streakStart >= reacquisitionExitDwellMs) {
                        transitionTo(
                            GnssMode.DEAD_RECKONING,
                            nowMs,
                            "GNSS degraded continuously for >= ${reacquisitionExitDwellMs}ms during REACQUISITION window",
                        )
                    }
                } else {
                    badStreakStartMs = null
                    val streakStart = goodStreakStartMs ?: nowMs.also { goodStreakStartMs = it }
                    if (nowMs - streakStart >= reacquisitionDwellMs) {
                        transitionTo(GnssMode.GNSS_AIDED, nowMs, "REACQUISITION window elapsed with GNSS continuously good")
                    }
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
