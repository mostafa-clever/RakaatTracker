package com.ahmed.rakaattracker.sensor

import com.ahmed.rakaattracker.data.VibrationTiming
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class TrackingMode(val labelAr: String) {
    POCKET_MOTION("وضع الجيب (الحركة والميل)"),
    GROUND_PROXIMITY("وضع الأرض (مستشعر التقارب)")
}

enum class PrayerPosture(val labelAr: String) {
    CALIBRATING("جاري المعايرة..."),
    STANDING("قيام"),
    RUKU("ركوع"),
    SUJUD_1("سجود (1)"),
    SITTING("جلسة بين السجدتين"),
    SUJUD_2("سجود (2)")
}

data class TrackerConfig(
    val minRakahDurationMs: Long = 12_000L,
    val minPostureHoldMs: Long = 1_100L,
    val sujudTiltThresholdDeg: Float = 52f,
    val rukuTiltThresholdDeg: Float = 22f,
    val dropImpulseThreshold: Float = 2.2f,
    val pocketMode: Boolean = true,
    val autoGroundModeEnabled: Boolean = true,
    val vibrationTiming: VibrationTiming = VibrationTiming.SITTING_BETWEEN_SUJUDS
)

data class PostureEvent(
    val newRakahCount: Int? = null,
    val posture: PrayerPosture,
    val tiltDegrees: Float,
    val verticalDropScore: Float,
    val trackingMode: TrackingMode = TrackingMode.POCKET_MOTION,
    val modeChangedToGround: Boolean = false,
    val modeChangedToPocket: Boolean = false,
    val protectionBlockedReason: String? = null
)

/**
 * Dual-mode classifier:
 * 1) POCKET_MOTION: Uses gravity tilt + vertical linear acceleration.
 * 2) GROUND_PROXIMITY: Auto-activates after 3.5s flat+still+uncovered, and switches back to
 *    POCKET_MOTION if picked up / tilted upright for 1.5s. Proximity debounce is evaluated
 *    continuously inside onGravitySample() because TYPE_PROXIMITY is an on-change sensor.
 */
class PostureClassifier(private var config: TrackerConfig = TrackerConfig()) {

    var rakahCount: Int = 0
        private set

    var currentPosture: PrayerPosture = PrayerPosture.CALIBRATING
        private set

    var trackingMode: TrackingMode = TrackingMode.POCKET_MOTION
        private set

    private var refGravity: FloatArray? = null
    private val calibSamples = mutableListOf<FloatArray>()
    private var startTimeMs: Long = 0L

    private var lastGravity = floatArrayOf(0f, 9.81f, 0f)
    private var hasGravity = false

    private var verticalVelocity = 0f
    private var verticalDisplacement = 0f
    private var lastLinAccelTimeNs: Long = 0L
    private var recentDownwardDropScore = 0f
    private var lastDropTimeMs = 0L

    private var candidatePosture: PrayerPosture? = null
    private var candidateSinceMs: Long = 0L
    private var lastRakahCountedMs: Long = 0L
    private var lastSujud1Ms: Long = 0L
    private var motionVarianceEma = 0f
    private var alertedForCurrentRakah = false
    private var sujud2CompletedForCurrentRakah = false

    // Proximity state (TYPE_PROXIMITY only fires on change, so we store raw state & evaluate in gravity loop)
    private var rawProximityNear: Boolean = false
    private var rawProximityChangedMs: Long = 0L
    private var debouncedProximityNear: Boolean = false

    // Auto mode switching timers
    private var flatUncoveredSinceMs: Long = 0L
    private var pickedUpUprightSinceMs: Long = 0L

    fun reset(nowMs: Long = System.currentTimeMillis()) {
        rakahCount = 0
        currentPosture = PrayerPosture.CALIBRATING
        trackingMode = TrackingMode.POCKET_MOTION
        refGravity = null
        calibSamples.clear()
        startTimeMs = nowMs
        verticalVelocity = 0f
        verticalDisplacement = 0f
        lastLinAccelTimeNs = 0L
        recentDownwardDropScore = 0f
        candidatePosture = null
        candidateSinceMs = 0L
        lastRakahCountedMs = 0L
        lastSujud1Ms = 0L
        motionVarianceEma = 0f
        alertedForCurrentRakah = false
        sujud2CompletedForCurrentRakah = false
        rawProximityNear = false
        rawProximityChangedMs = nowMs
        debouncedProximityNear = false
        flatUncoveredSinceMs = 0L
        pickedUpUprightSinceMs = 0L
    }

    fun updateConfig(newConfig: TrackerConfig) {
        config = newConfig
    }

    fun undoLastRakah(): Int {
        if (rakahCount > 0) {
            rakahCount -= 1
        }
        alertedForCurrentRakah = true
        sujud2CompletedForCurrentRakah = true
        currentPosture = PrayerPosture.STANDING
        candidatePosture = null
        lastRakahCountedMs = System.currentTimeMillis()
        return rakahCount
    }

    fun onProximitySample(
        distanceCm: Float,
        maxRangeCm: Float,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val near = distanceCm < (maxRangeCm * 0.8f).coerceAtLeast(1.0f)
        if (near != rawProximityNear) {
            rawProximityNear = near
            rawProximityChangedMs = nowMs
        }
    }

    fun onGravitySample(gx: Float, gy: Float, gz: Float, nowMs: Long = System.currentTimeMillis()): PostureEvent {
        lastGravity = floatArrayOf(gx, gy, gz)
        hasGravity = true

        var switchedToGround = false
        var switchedToPocket = false

        val isFlatOnFloor = abs(gz) > 8.2f && abs(gx) < 3.2f && abs(gy) < 3.2f
        val isUprightOrTilted = abs(gz) < 6.5f
        val isStill = motionVarianceEma < 0.55f
        val isUncovered = !rawProximityNear

        // 1) Switch Pocket -> Ground only after 3.5s of flat + still + uncovered (tolerance period)
        if (config.autoGroundModeEnabled && trackingMode == TrackingMode.POCKET_MOTION) {
            if (isFlatOnFloor && isStill && isUncovered) {
                if (flatUncoveredSinceMs == 0L) {
                    flatUncoveredSinceMs = nowMs
                } else if (nowMs - flatUncoveredSinceMs >= 3_500L) {
                    trackingMode = TrackingMode.GROUND_PROXIMITY
                    currentPosture = PrayerPosture.STANDING
                    debouncedProximityNear = rawProximityNear
                    switchedToGround = true
                    pickedUpUprightSinceMs = 0L
                }
            } else {
                flatUncoveredSinceMs = 0L
            }
        }
        // 2) Allow switching Ground -> Pocket if user picks up the phone upright for 1.5s
        else if (trackingMode == TrackingMode.GROUND_PROXIMITY) {
            if (isUprightOrTilted) {
                if (pickedUpUprightSinceMs == 0L) {
                    pickedUpUprightSinceMs = nowMs
                } else if (nowMs - pickedUpUprightSinceMs >= 1_500L) {
                    trackingMode = TrackingMode.POCKET_MOTION
                    switchedToPocket = true
                    flatUncoveredSinceMs = 0L
                    if (rakahCount == 0) {
                        refGravity = floatArrayOf(gx, gy, gz)
                        currentPosture = PrayerPosture.STANDING
                    }
                }
            } else {
                pickedUpUprightSinceMs = 0L
            }
        }

        // 3) If in Ground Mode, evaluate debounced proximity inside this continuous 50Hz loop
        if (trackingMode == TrackingMode.GROUND_PROXIMITY) {
            if (rawProximityNear != debouncedProximityNear && (nowMs - rawProximityChangedMs) >= 350L) {
                debouncedProximityNear = rawProximityNear
                val raw = if (debouncedProximityNear) PrayerPosture.SUJUD_1 else PrayerPosture.SITTING
                val ev = applySujudStateTransition(raw, 0f, 0f, nowMs)
                return ev.copy(modeChangedToGround = switchedToGround)
            }
            return PostureEvent(
                newRakahCount = null,
                posture = currentPosture,
                tiltDegrees = 0f,
                verticalDropScore = 0f,
                trackingMode = trackingMode,
                modeChangedToGround = switchedToGround,
                modeChangedToPocket = switchedToPocket
            )
        }

        // 4) Pocket Motion calibration & detection
        if (refGravity == null) {
            calibSamples.add(floatArrayOf(gx, gy, gz))
            if (nowMs - startTimeMs >= 1_200L && calibSamples.isNotEmpty()) {
                val avg = FloatArray(3)
                for (s in calibSamples) {
                    avg[0] += s[0]
                    avg[1] += s[1]
                    avg[2] += s[2]
                }
                val n = calibSamples.size.toFloat()
                refGravity = floatArrayOf(avg[0] / n, avg[1] / n, avg[2] / n)
                currentPosture = PrayerPosture.STANDING
            } else {
                return PostureEvent(
                    null,
                    PrayerPosture.CALIBRATING,
                    0f,
                    recentDownwardDropScore,
                    trackingMode,
                    modeChangedToPocket = switchedToPocket
                )
            }
        }

        val tiltDeg = computeTiltDegrees(lastGravity, refGravity!!)
        val dropAgeMs = nowMs - lastDropTimeMs
        val activeDropScore = if (dropAgeMs <= 4_500L) recentDownwardDropScore else 0f

        val rawDetected = classifyRawPosture(tiltDeg, activeDropScore)
        val ev = advancePocketStateMachine(rawDetected, tiltDeg, activeDropScore, nowMs)
        return if (switchedToPocket) ev.copy(modeChangedToPocket = true) else ev
    }

    fun onLinearAccelerationSample(
        ax: Float,
        ay: Float,
        az: Float,
        timestampNs: Long,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!hasGravity) return

        val gNorm = sqrt(
            lastGravity[0] * lastGravity[0] +
                lastGravity[1] * lastGravity[1] +
                lastGravity[2] * lastGravity[2]
        ).coerceAtLeast(0.1f)

        val aDown = (ax * lastGravity[0] + ay * lastGravity[1] + az * lastGravity[2]) / gNorm
        val mag = sqrt(ax * ax + ay * ay + az * az)
        motionVarianceEma = 0.85f * motionVarianceEma + 0.15f * mag

        if (lastLinAccelTimeNs != 0L) {
            val dt = ((timestampNs - lastLinAccelTimeNs) / 1_000_000_000.0f).coerceIn(0f, 0.1f)
            verticalVelocity = (verticalVelocity + aDown * dt) * 0.92f
            verticalDisplacement = (verticalDisplacement + verticalVelocity * dt) * 0.96f

            if (aDown > config.dropImpulseThreshold || verticalDisplacement > 0.18f) {
                val score = maxOf(aDown, verticalDisplacement * 10f)
                if (score > recentDownwardDropScore || nowMs - lastDropTimeMs > 2000L) {
                    recentDownwardDropScore = score
                    lastDropTimeMs = nowMs
                }
            }
        }
        lastLinAccelTimeNs = timestampNs
    }

    private fun classifyRawPosture(tiltDeg: Float, dropScore: Float): PrayerPosture {
        val sujudAngle = if (config.pocketMode) config.sujudTiltThresholdDeg else 70f
        val rukuAngle = if (config.pocketMode) config.rukuTiltThresholdDeg else 42f

        return when {
            tiltDeg >= sujudAngle || (tiltDeg >= sujudAngle - 12f && dropScore >= config.dropImpulseThreshold) -> {
                PrayerPosture.SUJUD_1
            }
            tiltDeg >= rukuAngle -> {
                if (currentPosture == PrayerPosture.SUJUD_1 ||
                    currentPosture == PrayerPosture.SITTING ||
                    currentPosture == PrayerPosture.SUJUD_2
                ) {
                    PrayerPosture.SITTING
                } else {
                    PrayerPosture.RUKU
                }
            }
            else -> PrayerPosture.STANDING
        }
    }

    private fun advancePocketStateMachine(
        raw: PrayerPosture,
        tiltDeg: Float,
        dropScore: Float,
        nowMs: Long
    ): PostureEvent {
        if (raw != candidatePosture) {
            candidatePosture = raw
            candidateSinceMs = nowMs
            return PostureEvent(null, currentPosture, tiltDeg, dropScore, trackingMode)
        }

        val holdTime = nowMs - candidateSinceMs
        if (holdTime < config.minPostureHoldMs || motionVarianceEma > 2.8f) {
            return PostureEvent(null, currentPosture, tiltDeg, dropScore, trackingMode)
        }

        if (raw == currentPosture) {
            return PostureEvent(null, currentPosture, tiltDeg, dropScore, trackingMode)
        }

        return applySujudStateTransition(raw, tiltDeg, dropScore, nowMs)
    }

    private fun applySujudStateTransition(
        raw: PrayerPosture,
        tiltDeg: Float,
        dropScore: Float,
        nowMs: Long
    ): PostureEvent {
        var notifyRakah: Int? = null
        var blockedReason: String? = null

        when (raw) {
            PrayerPosture.SUJUD_1 -> {
                val timeSinceLastRakah = nowMs - lastRakahCountedMs
                val timeSinceSujud1 = nowMs - lastSujud1Ms

                when {
                    // Second sujud of the same rak'ah
                    rakahCount > 0 && !sujud2CompletedForCurrentRakah && timeSinceSujud1 < 20_000L -> {
                        sujud2CompletedForCurrentRakah = true
                        currentPosture = PrayerPosture.SUJUD_2
                        if (config.vibrationTiming == VibrationTiming.SECOND_SUJUD &&
                            !alertedForCurrentRakah
                        ) {
                            notifyRakah = rakahCount
                            alertedForCurrentRakah = true
                        }
                    }
                    // Automatic protection: rak'ah too short
                    lastRakahCountedMs != 0L && timeSinceLastRakah < config.minRakahDurationMs -> {
                        blockedReason = "حماية تلقائية: مدة الركعة أقل من ${config.minRakahDurationMs / 1000} ثوانٍ"
                        currentPosture = PrayerPosture.SUJUD_2
                    }
                    // New rak'ah!
                    else -> {
                        rakahCount += 1
                        lastRakahCountedMs = nowMs
                        lastSujud1Ms = nowMs
                        sujud2CompletedForCurrentRakah = false
                        currentPosture = PrayerPosture.SUJUD_1
                        alertedForCurrentRakah = false
                        if (config.vibrationTiming == VibrationTiming.FIRST_SUJUD) {
                            notifyRakah = rakahCount
                            alertedForCurrentRakah = true
                        }
                    }
                }
            }
            PrayerPosture.SUJUD_2 -> {
                currentPosture = PrayerPosture.SUJUD_2
            }
            PrayerPosture.SITTING -> {
                currentPosture = PrayerPosture.SITTING
                if (config.vibrationTiming == VibrationTiming.SITTING_BETWEEN_SUJUDS &&
                    !alertedForCurrentRakah &&
                    rakahCount > 0
                ) {
                    notifyRakah = rakahCount
                    alertedForCurrentRakah = true
                }
            }
            PrayerPosture.RUKU -> {
                if (currentPosture != PrayerPosture.SUJUD_1 && currentPosture != PrayerPosture.SITTING) {
                    currentPosture = PrayerPosture.RUKU
                }
            }
            PrayerPosture.STANDING -> {
                currentPosture = PrayerPosture.STANDING
            }
            PrayerPosture.CALIBRATING -> Unit
        }

        return PostureEvent(notifyRakah, currentPosture, tiltDeg, dropScore, trackingMode, false, false, blockedReason)
    }

    private fun computeTiltDegrees(v1: FloatArray, v2: FloatArray): Float {
        val m1 = sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2]).coerceAtLeast(0.1f)
        val m2 = sqrt(v2[0] * v2[0] + v2[1] * v2[1] + v2[2] * v2[2]).coerceAtLeast(0.1f)
        val dot = (v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2]) / (m1 * m2)
        val clamped = dot.coerceIn(-1f, 1f)
        return Math.toDegrees(acos(clamped.toDouble())).toFloat()
    }
}
