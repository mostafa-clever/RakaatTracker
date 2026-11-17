package com.ahmed.rakaattracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.ahmed.rakaattracker.MainActivity
import com.ahmed.rakaattracker.data.SettingsStore
import com.ahmed.rakaattracker.data.TriggerButtonMode
import com.ahmed.rakaattracker.prayer.PrayerTimeCalculator
import com.ahmed.rakaattracker.sensor.PostureClassifier
import com.ahmed.rakaattracker.sensor.PostureEvent
import com.ahmed.rakaattracker.sensor.PrayerPosture
import com.ahmed.rakaattracker.sensor.TrackerConfig
import com.ahmed.rakaattracker.sensor.TrackingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TrackerUiState(
    val isPrayerActive: Boolean = false,
    val isStandbyWindowActive: Boolean = false,
    val rakahCount: Int = 0,
    val posture: PrayerPosture = PrayerPosture.CALIBRATING,
    val trackingMode: TrackingMode = TrackingMode.POCKET_MOTION,
    val tiltDegrees: Float = 0f,
    val lastStatusNote: String = "جاهز"
)

class PrayerTrackerService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "prayer_tracker_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_PRAYER = "com.ahmed.rakaattracker.ACTION_START_PRAYER"
        const val ACTION_STOP_PRAYER = "com.ahmed.rakaattracker.ACTION_STOP_PRAYER"
        const val ACTION_UNDO_RAKAH = "com.ahmed.rakaattracker.ACTION_UNDO_RAKAH"
        const val ACTION_START_STANDBY = "com.ahmed.rakaattracker.ACTION_START_STANDBY"
        const val ACTION_TEST_VIBRATION = "com.ahmed.rakaattracker.ACTION_TEST_VIBRATION"

        private val _uiState = MutableStateFlow(TrackerUiState())
        val uiState: StateFlow<TrackerUiState> = _uiState

        @Volatile
        var isPrayerActive: Boolean = false
            private set

        @Volatile
        private var lastVibrationTimestampMs: Long = 0L

        fun isInUndoWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
            return (nowMs - lastVibrationTimestampMs) <= 6_000L
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val classifier = PostureClassifier(TrackerConfig())
    private var previousDndFilter: Int? = null
    private val screenToggleTimestamps = ArrayDeque<Long>()
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_SCREEN_OFF) return

            SettingsStore.init(context)
            val settings = SettingsStore.settings.value
            if (settings.triggerMode != TriggerButtonMode.POWER_SCREEN_3X) return

            val now = System.currentTimeMillis()
            screenToggleTimestamps.addLast(now)
            while (screenToggleTimestamps.size > 3) {
                screenToggleTimestamps.removeFirst()
            }
            if (screenToggleTimestamps.size == 3 && (now - screenToggleTimestamps.first()) <= 2_500L) {
                screenToggleTimestamps.clear()
                if (!isPrayerActive) {
                    if (settings.onlyDuringPrayerTimes) {
                        val (inWindow, _) = PrayerTimeCalculator.isWithinPrayerWindow(
                            nowMillis = now,
                            windowMinutesAfterAdhan = settings.prayerWindowMinutes
                        )
                        if (!inWindow) return
                    }
                    startPrayerMode()
                } else {
                    stopPrayerMode()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        createNotificationChannel()
        registerScreenReceiverIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PRAYER -> startPrayerMode()
            ACTION_STOP_PRAYER -> stopPrayerMode()
            ACTION_UNDO_RAKAH -> handleUndoRakah()
            ACTION_START_STANDBY -> startStandbyMode()
            ACTION_TEST_VIBRATION -> vibrateStartupMelody()
        }
        return START_STICKY
    }

    private fun startStandbyMode() {
        val settings = SettingsStore.settings.value
        val notification = buildNotification("في انتظار دخول الصلاة (${settings.triggerMode.labelAr})")
        startForeground(NOTIFICATION_ID, notification)
        _uiState.value = _uiState.value.copy(
            isStandbyWindowActive = true,
            lastStatusNote = "النظام جاهز — ${settings.triggerMode.descriptionAr}"
        )
    }

    private fun startPrayerMode() {
        val settings = SettingsStore.settings.value
        classifier.updateConfig(
            TrackerConfig(
                minRakahDurationMs = settings.minRakahSeconds * 1_000L,
                pocketMode = settings.pocketMode,
                autoGroundModeEnabled = settings.autoGroundModeEnabled,
                vibrationTiming = settings.vibrationTiming
            )
        )
        isPrayerActive = true
        classifier.reset()

        enableDndSilence()
        acquireWakeLock()
        registerSensors()

        // Distinct startup haptic melody (3 rising pulses)
        vibrateStartupMelody()

        val notification = buildNotification("وضع الصلاة نشط — الركعة: 0")
        startForeground(NOTIFICATION_ID, notification)

        _uiState.value = TrackerUiState(
            isPrayerActive = true,
            isStandbyWindowActive = false,
            rakahCount = 0,
            posture = PrayerPosture.CALIBRATING,
            trackingMode = TrackingMode.POCKET_MOTION,
            lastStatusNote = "تم تفعيل وضع الصلاة وكتم الإشعارات"
        )
    }

    private fun stopPrayerMode() {
        isPrayerActive = false
        unregisterSensors()
        releaseWakeLock()
        restoreDndFilter()
        vibrator.cancel()

        // Distinct falling 2-pulse shutdown feedback
        vibrateWaveform(longArrayOf(0, 100, 70, 50), intArrayOf(0, 200, 0, 120))

        _uiState.value = TrackerUiState(
            isPrayerActive = false,
            isStandbyWindowActive = false,
            rakahCount = classifier.rakahCount,
            posture = PrayerPosture.STANDING,
            trackingMode = classifier.trackingMode,
            lastStatusNote = "تم إنهاء وضع الصلاة"
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleUndoRakah() {
        if (!isPrayerActive) return
        vibrator.cancel()
        val updatedCount = classifier.undoLastRakah()
        lastVibrationTimestampMs = 0L

        vibrateDistinctPattern(count = 2, pulseMs = 45L, gapMs = 60L, amplitude = 160)

        _uiState.value = _uiState.value.copy(
            rakahCount = updatedCount,
            posture = classifier.currentPosture,
            lastStatusNote = "تم إلغاء العدة الخاطئة يدوياً (الركعة: $updatedCount)"
        )
        updateNotification("وضع الصلاة نشط — الركعة: $updatedCount")
    }

    private fun registerScreenReceiverIfNeeded() {
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }
    }

    private fun registerSensors() {
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isPrayerActive) return

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val res = classifier.onGravitySample(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )
                handlePostureResult(res)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                classifier.onLinearAccelerationSample(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    event.timestamp
                )
            }
            Sensor.TYPE_PROXIMITY -> {
                classifier.onProximitySample(
                    event.values[0],
                    event.sensor.maximumRange
                )
            }
            Sensor.TYPE_LIGHT -> {
                classifier.onLightSample(event.values[0])
            }
        }
    }

    private fun handlePostureResult(res: PostureEvent) {
        if (res.modeChangedToGround) {
            // Subtle single soft tick to confirm switching to Ground Proximity Mode
            vibrateDistinctPattern(count = 1, pulseMs = 60L, gapMs = 60L, amplitude = 90)
        }

        if (res.newRakahCount != null) {
            lastVibrationTimestampMs = System.currentTimeMillis()
            vibrateRakahCount(res.newRakahCount, res.trackingMode)
            updateNotification("وضع الصلاة (${res.trackingMode.labelAr}) — الركعة: ${res.newRakahCount}")
        }

        val note = when {
            res.modeChangedToGround -> "تم التحويل تلقائياً لوضع الأرض (مستشعر التقارب)"
            res.modeChangedToPocket -> "تم العودة تلقائياً لوضع الجيب"
            res.protectionBlockedReason != null -> res.protectionBlockedReason
            else -> _uiState.value.lastStatusNote
        }

        _uiState.value = _uiState.value.copy(
            rakahCount = classifier.rakahCount,
            posture = res.posture,
            trackingMode = res.trackingMode,
            tiltDegrees = res.tiltDegrees,
            lastStatusNote = note
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Startup haptic melody: 3 rising pulses (short-short-medium) to signal Prayer Mode started.
     */
    private fun vibrateStartupMelody() {
        val timings = longArrayOf(0, 55, 65, 55, 75, 130)
        val amplitudes = intArrayOf(0, 120, 0, 180, 0, 255)
        vibrateWaveform(timings, amplitudes)
    }

    private fun vibrateRakahCount(count: Int, mode: TrackingMode) {
        if (mode == TrackingMode.GROUND_PROXIMITY) {
            // Gentle, low-noise vibration for floor placement so others nearby don't hear it
            val strength = SettingsStore.settings.value.groundVibrationStrength.coerceIn(30, 255)
            vibrateDistinctPattern(count = count.coerceIn(1, 12), pulseMs = 115L, gapMs = 210L, amplitude = strength)
        } else {
            vibrateDistinctPattern(count = count.coerceIn(1, 12), pulseMs = 240L, gapMs = 260L, amplitude = 255)
        }
    }

    private fun vibrateDistinctPattern(count: Int, pulseMs: Long, gapMs: Long, amplitude: Int) {
        val timings = LongArray(count * 2)
        val amplitudes = IntArray(count * 2)
        for (i in 0 until count) {
            timings[i * 2] = if (i == 0) 0L else gapMs
            amplitudes[i * 2] = 0
            timings[i * 2 + 1] = pulseMs
            amplitudes[i * 2 + 1] = amplitude
        }
        vibrateWaveform(timings, amplitudes)
    }

    private fun vibrateWaveform(timings: LongArray, amplitudes: IntArray) {
        if (!vibrator.hasVibrator()) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            // Devices without amplitude control (like Redmi 9) fail or stay silent if custom amplitudes are passed
            VibrationEffect.createWaveform(timings, -1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vAttrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            vibrator.vibrate(effect, vAttrs)
        } else {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, attrs)
        }
    }

    private fun enableDndSilence() {
        if (notificationManager.isNotificationPolicyAccessGranted) {
            previousDndFilter = notificationManager.currentInterruptionFilter
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
    }

    private fun restoreDndFilter() {
        val prev = previousDndFilter ?: return
        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(prev)
        }
        previousDndFilter = null
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RakaatTracker::PrayerWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(45 * 60_000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "متابع الركعات",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PrayerTrackerService::class.java).apply { action = ACTION_STOP_PRAYER },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("متابع الركعات")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "إنهاء", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        unregisterSensors()
        releaseWakeLock()
        restoreDndFilter()
        super.onDestroy()
    }
}
