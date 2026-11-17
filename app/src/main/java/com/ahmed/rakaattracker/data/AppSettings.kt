package com.ahmed.rakaattracker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TriggerButtonMode(val labelAr: String, val descriptionAr: String) {
    VOLUME_DOWN_3X("زر خفض الصوت (3 مرات)", "اضغط خفض الصوت 3 ضغطات متتالية"),
    VOLUME_UP_3X("زر رفع الصوت (3 مرات)", "اضغط رفع الصوت 3 ضغطات متتالية"),
    ANY_VOLUME_3X("أي زر صوت (3 مرات)", "أي 3 ضغطات على أزرار الصوت"),
    POWER_SCREEN_3X("زر الباور / الشاشة (3 مرات)", "فتح وقفل الشاشة 3 مرات خلال 2.5 ثانية")
}

enum class VibrationTiming(val labelAr: String) {
    SITTING_BETWEEN_SUJUDS("الجلوس بين السجدتين (الافتراضي)"),
    FIRST_SUJUD("السجدة الأولى"),
    SECOND_SUJUD("السجدة الثانية")
}

data class AppSettings(
    val onlyDuringPrayerTimes: Boolean = true,
    val prayerWindowMinutes: Int = 45,
    val triggerMode: TriggerButtonMode = TriggerButtonMode.VOLUME_DOWN_3X,
    val vibrationTiming: VibrationTiming = VibrationTiming.SITTING_BETWEEN_SUJUDS,
    val minRakahSeconds: Int = 12,
    val pocketMode: Boolean = true,
    val manualUndoEnabled: Boolean = true,
    val autoGroundModeEnabled: Boolean = true,
    val groundVibrationStrength: Int = 80
)

object SettingsStore {
    private const val PREFS_NAME = "rakaat_tracker_prefs"
    private const val KEY_ONLY_PRAYER_TIMES = "only_during_prayer_times"
    private const val KEY_PRAYER_WINDOW_MIN = "prayer_window_minutes"
    private const val KEY_TRIGGER_MODE = "trigger_mode"
    private const val KEY_VIBRATION_TIMING = "vibration_timing"
    private const val KEY_MIN_RAKAH_SEC = "min_rakah_seconds"
    private const val KEY_POCKET_MODE = "pocket_mode"
    private const val KEY_MANUAL_UNDO = "manual_undo_enabled"
    private const val KEY_AUTO_GROUND = "auto_ground_mode_enabled"
    private const val KEY_GROUND_VIB = "ground_vibration_strength"

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_TRIGGER_MODE, TriggerButtonMode.VOLUME_DOWN_3X.name)
        val mode = runCatching { TriggerButtonMode.valueOf(modeName ?: "") }
            .getOrDefault(TriggerButtonMode.VOLUME_DOWN_3X)

        val timingName = prefs.getString(KEY_VIBRATION_TIMING, VibrationTiming.SITTING_BETWEEN_SUJUDS.name)
        val timing = runCatching { VibrationTiming.valueOf(timingName ?: "") }
            .getOrDefault(VibrationTiming.SITTING_BETWEEN_SUJUDS)

        _settings.value = AppSettings(
            onlyDuringPrayerTimes = prefs.getBoolean(KEY_ONLY_PRAYER_TIMES, true),
            prayerWindowMinutes = prefs.getInt(KEY_PRAYER_WINDOW_MIN, 45),
            triggerMode = mode,
            vibrationTiming = timing,
            minRakahSeconds = prefs.getInt(KEY_MIN_RAKAH_SEC, 12),
            pocketMode = prefs.getBoolean(KEY_POCKET_MODE, true),
            manualUndoEnabled = prefs.getBoolean(KEY_MANUAL_UNDO, true),
            autoGroundModeEnabled = prefs.getBoolean(KEY_AUTO_GROUND, true),
            groundVibrationStrength = prefs.getInt(KEY_GROUND_VIB, 80)
        )
        initialized = true
    }

    fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        init(context)
        val updated = transform(_settings.value)
        _settings.value = updated
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ONLY_PRAYER_TIMES, updated.onlyDuringPrayerTimes)
            .putInt(KEY_PRAYER_WINDOW_MIN, updated.prayerWindowMinutes)
            .putString(KEY_TRIGGER_MODE, updated.triggerMode.name)
            .putString(KEY_VIBRATION_TIMING, updated.vibrationTiming.name)
            .putInt(KEY_MIN_RAKAH_SEC, updated.minRakahSeconds)
            .putBoolean(KEY_POCKET_MODE, updated.pocketMode)
            .putBoolean(KEY_MANUAL_UNDO, updated.manualUndoEnabled)
            .putBoolean(KEY_AUTO_GROUND, updated.autoGroundModeEnabled)
            .putInt(KEY_GROUND_VIB, updated.groundVibrationStrength)
            .apply()
    }
}
