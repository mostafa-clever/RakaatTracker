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
    val protectionBlockedReason: String? = null
)

/**
 * Dual-mode classifier:
 * 1) POCKET_MOTION: Uses gravity tilt + vertical linear acceleration.
 * 2) GROUND_PROXIMITY: Auto-activates when phone is flat, still, and uncovered; uses proximity sensor
 *    to detect Sujud when the user's chest/face approaches the phone on the prayer mat.
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

    private var isProximityNear: Boolean = false
    private var proximityCandidateNear: Boolean? = null
    private var proximityCandidateSinceMs: Long = 0L
    private var flatUncoveredSinceMs: Long = 0L

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
        isProximityNear = false
        proximityCandidateNear = null
        proximityCandidateSinceMs = 0L
        flatUncoveredSinceMs = 0L
    }

    fun updateConfig(newConfig: TrackerConfig) {
        config = newConfig
    }

    fun undoLastRakah(): Int {
        if (rakahCount > 0) {
            rakahCount -= 1
        }
        alertedForCurrentRakah = true
        currentPosture = PrayerPosture.STANDING
        candidatePosture = null
        lastRakahCountedMs = System.currentTimeMillis()
        return rakahCount
    }

    fun onProximitySample(
        distanceCm: Float,
        maxRangeCm: Float,
        nowMs: Long = System.currentTimeMillis()
    ): PostureEvent {
        val near = distanceCm < (maxRangeCm * 0.8f).coerceAtLeast(1.0f)

        if (trackingMode != TrackingMode.GROUND_PROXIMITY) {
            isProximityNear = near
            return PostureEvent(null, currentPosture, 0f, 0f, trackingMode)
        }

        if (near != proximityCandidateNear) {
            proximityCandidateNear = near
            proximityCandidateSinceMs = nowMs
            return PostureEvent(null, currentPosture, 0f, 0f, trackingMode)
        }

        if (nowMs - proximityCandidateSinceMs < 350L) {
            return PostureEvent(null, currentPosture, 0f, 0f, trackingMode)
        }

        if (near == isProximityNear) {
            return PostureEvent(null, currentPosture, 0f, 0f, trackingMode)
        }

        isProximityNear = near
        val raw = if (near) {
            if (currentPosture == PrayerPosture.SITTING || currentPosture == PrayerPosture.SUJUD_2) {
                PrayerPosture.SUJUD_2
            } else {
                PrayerPosture.SUJUD_1
            }
        } else {
            PrayerPosture.SITTING
        }
        return applySujudStateTransition(raw, 0f, 0f, nowMs)
    }

    fun onGravitySample(gx: Float, gy: Float, gz: Float, nowMs: Long = System.currentTimeMillis()): PostureEvent {
        lastGravity = floatArrayOf(gx, gy, gz)
        hasGravity = true

        var switchedToGround = false
        if (config.autoGroundModeEnabled && trackingMode == TrackingMode.POCKET_MOTION) {
            val isFlatOnFloor = abs(gz) > 7.8f && abs(gx) < 3.5f && abs(gy) < 3.5f
            val isStill = motionVarianceEma < 0.65f
            val isUncovered = !isProximityNear

            if (isFlatOnFloor && isStill && isUncovered) {
                if (flatUncoveredSinceMs == 0L) {
                    flatUncoveredSinceMs = nowMs
                } else if (nowMs - flatUncoveredSinceMs >= 1_500L) {
                    trackingMode = TrackingMode.GROUND_PROXIMITY
                    currentPosture = PrayerPosture.STANDING
                    switchedToGround = true
                }
            } else {
                flatUncoveredSinceMs = 0L
            }
        }

        if (trackingMode == TrackingMode.GROUND_PROXIMITY) {
            return PostureEvent(
                newRakahCount = null,
                posture = currentPosture,
                tiltDegrees = 0f,
                verticalDropScore = 0f,
                trackingMode = trackingMode,
                modeChangedToGround = switchedToGround
            )
        }

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
                return PostureEvent(null, PrayerPosture.CALIBRATING, 0f, recentDownwardDropScore, trackingMode)
            }
        }

        val tiltDeg = computeTiltDegrees(lastGravity, refGravity!!)
        val dropAgeMs = nowMs - lastDropTimeMs
        val activeDropScore = if (dropAgeMs <= 4_500L) recentDownwardDropScore else 0f

        val rawDetected = classifyRawPosture(tiltDeg, activeDropScore)
        return advancePocketStateMachine(rawDetected, tiltDeg, activeDropScore, nowMs)
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
                if (currentPosture == PrayerPosture.SITTING || currentPosture == PrayerPosture.SUJUD_2) {
                    PrayerPosture.SUJUD_2
                } else {
                    PrayerPosture.SUJUD_1
                }
            }
            tiltDeg >= rukuAngle -> {
                if (currentPosture == PrayerPosture.SUJUD_1 || currentPosture == PrayerPosture.SITTING) {
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
                    currentPosture == PrayerPosture.SITTING && timeSinceSujud1 < 18_000L -> {
                        currentPosture = PrayerPosture.SUJUD_2
                        if (config.vibrationTiming == VibrationTiming.SECOND_SUJUD &&
                            !alertedForCurrentRakah &&
                            rakahCount > 0
                        ) {
                            notifyRakah = rakahCount
                            alertedForCurrentRakah = true
                        }
                    }
                    lastRakahCountedMs != 0L && timeSinceLastRakah < config.minRakahDurationMs -> {
                        blockedReason = "حماية تلقائية: مدة الركعة أقل من ${config.minRakahDurationMs / 1000} ثوانٍ"
                        currentPosture = PrayerPosture.SUJUD_2
                    }
                    else -> {
                        rakahCount += 1
                        lastRakahCountedMs = nowMs
                        lastSujud1Ms = nowMs
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
                if (config.vibrationTiming == VibrationTiming.SECOND_SUJUD &&
                    !alertedForCurrentRakah &&
                    rakahCount > 0
                ) {
                    notifyRakah = rakahCount
                    alertedForCurrentRakah = true
                }
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

        return PostureEvent(notifyRakah, currentPosture, tiltDeg, dropScore, trackingMode, false, blockedReason)
    }

    private fun computeTiltDegrees(v1: FloatArray, v2: FloatArray): Float {
        val m1 = sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2]).coerceAtLeast(0.1f)
        val m2 = sqrt(v2[0] * v2[0] + v2[1] * v2[1] + v2[2] * v2[2]).coerceAtLeast(0.1f)
        val dot = (v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2]) / (m1 * m2)
        val clamped = dot.coerceIn(-1f, 1f)
        return Math.toDegrees(acos(clamped.toDouble())).toFloat()
    }
}
