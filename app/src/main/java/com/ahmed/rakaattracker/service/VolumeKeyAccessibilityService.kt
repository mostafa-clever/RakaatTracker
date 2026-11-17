package com.ahmed.rakaattracker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ahmed.rakaattracker.data.SettingsStore
import com.ahmed.rakaattracker.data.TriggerButtonMode
import com.ahmed.rakaattracker.prayer.PrayerTimeCalculator

/**
 * Intercepts hardware Volume Up / Volume Down key presses globally based on user settings:
 * - 3x selected trigger button within 1.5s -> Start/Stop Prayer Mode (respecting prayer-time window if enabled)
 * - Volume Up or Down during the 6s undo window after vibration -> Cancel last rak'ah count
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    private val triggerTimestamps = ArrayDeque<Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

        SettingsStore.init(this)
        val settings = SettingsStore.settings.value
        val now = System.currentTimeMillis()

        val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val isVolUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!isVolDown && !isVolUp) return super.onKeyEvent(event)

        // Manual protection: Undo last rak'ah during the 6s window after vibration
        if (settings.manualUndoEnabled &&
            PrayerTrackerService.isPrayerActive &&
            PrayerTrackerService.isInUndoWindow(now)
        ) {
            sendServiceAction(PrayerTrackerService.ACTION_UNDO_RAKAH)
            return true
        }

        // Check if this key matches the user's chosen 3x trigger button
        val matchesTrigger = when (settings.triggerMode) {
            TriggerButtonMode.VOLUME_DOWN_3X -> isVolDown
            TriggerButtonMode.VOLUME_UP_3X -> isVolUp
            TriggerButtonMode.ANY_VOLUME_3X -> true
            TriggerButtonMode.POWER_SCREEN_3X -> false
        }

        if (!matchesTrigger) return super.onKeyEvent(event)

        triggerTimestamps.addLast(now)
        while (triggerTimestamps.size > 3) {
            triggerTimestamps.removeFirst()
        }

        if (triggerTimestamps.size == 3 && (now - triggerTimestamps.first()) <= 1_500L) {
            triggerTimestamps.clear()

            if (!PrayerTrackerService.isPrayerActive) {
                if (settings.onlyDuringPrayerTimes) {
                    val (inWindow, _) = PrayerTimeCalculator.isWithinPrayerWindow(
                        nowMillis = now,
                        windowMinutesAfterAdhan = settings.prayerWindowMinutes
                    )
                    if (!inWindow) {
                        return super.onKeyEvent(event)
                    }
                }
                sendServiceAction(PrayerTrackerService.ACTION_START_PRAYER)
            } else {
                sendServiceAction(PrayerTrackerService.ACTION_STOP_PRAYER)
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, PrayerTrackerService::class.java).apply {
            this.action = action
        }
        startForegroundService(intent)
    }
}
