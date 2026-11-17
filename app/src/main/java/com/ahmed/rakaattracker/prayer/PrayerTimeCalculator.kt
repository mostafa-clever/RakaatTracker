package com.ahmed.rakaattracker.prayer

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

data class PrayerTimeEntry(
    val nameAr: String,
    val hour: Int,
    val minute: Int
) {
    fun toCalendar(base: Calendar): Calendar {
        return (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    fun formatTime(): String {
        val period = if (hour >= 12) "م" else "ص"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%d:%02d %s".format(h12, minute, period)
    }
}

/**
 * Offline prayer time calculator using the Egyptian General Authority of Survey method
 * (Fajr: 19.5 deg, Isha: 17.5 deg, Shafi/Standard Asr).
 * Default coordinates: Beni Suef, Egypt (29.0661 N, 31.0994 E).
 */
object PrayerTimeCalculator {

    private const val FAJR_ANGLE = 19.5
    private const val ISHA_ANGLE = 17.5

    fun calculateToday(
        latitude: Double = 29.0661,
        longitude: Double = 31.0994,
        date: Calendar = Calendar.getInstance()
    ): List<PrayerTimeEntry> {
        val year = date.get(Calendar.YEAR)
        val month = date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)

        val tzOffsetHours = TimeZone.getDefault().getOffset(date.timeInMillis) / 3_600_000.0
        val jd = julianDate(year, month, day) - longitude / (15.0 * 24.0)

        val (declination, eqTime) = sunPosition(jd)
        val dhuhr = 12.0 + tzOffsetHours - longitude / 15.0 - eqTime

        val fajr = dhuhr - hourAngle(latitude, declination, FAJR_ANGLE) / 15.0
        val asr = dhuhr + asrHourAngle(latitude, declination, 1.0) / 15.0
        val maghrib = dhuhr + hourAngle(latitude, declination, 0.833) / 15.0
        val isha = dhuhr + hourAngle(latitude, declination, ISHA_ANGLE) / 15.0

        return listOf(
            toEntry("الفجر", fajr),
            toEntry("الظهر", dhuhr),
            toEntry("العصر", asr),
            toEntry("المغرب", maghrib),
            toEntry("العشاء", isha)
        )
    }

    fun isWithinPrayerWindow(
        nowMillis: Long = System.currentTimeMillis(),
        windowMinutesAfterAdhan: Int = 45,
        latitude: Double = 29.0661,
        longitude: Double = 31.0994
    ): Pair<Boolean, PrayerTimeEntry?> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val prayers = calculateToday(latitude, longitude, cal)
        val windowMs = windowMinutesAfterAdhan * 60_000L

        for (prayer in prayers) {
            val startMs = prayer.toCalendar(cal).timeInMillis
            val endMs = startMs + windowMs
            if (nowMillis in startMs..endMs) {
                return true to prayer
            }
        }
        return false to null
    }

    private fun toEntry(nameAr: String, decimalHours: Double): PrayerTimeEntry {
        val normalized = ((decimalHours % 24.0) + 24.0) % 24.0
        var hours = floor(normalized).toInt()
        var minutes = ((normalized - hours) * 60.0).roundToInt()
        if (minutes >= 60) {
            minutes -= 60
            hours = (hours + 1) % 24
        }
        return PrayerTimeEntry(nameAr, hours, minutes)
    }

    private fun julianDate(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
        val q = (280.459 + 0.98564736 * d) % 360.0
        val l = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360.0)
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l))) / 15.0
        val eqTime = q / 15.0 - fixHour(ra)
        val decl = Math.toDegrees(asin(sin(e) * sin(l)))
        return decl to eqTime
    }

    private fun hourAngle(latDeg: Double, declDeg: Double, angleDeg: Double): Double {
        val lat = Math.toRadians(latDeg)
        val decl = Math.toRadians(declDeg)
        val ang = Math.toRadians(angleDeg)
        val cosH = (-sin(ang) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        return Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0)))
    }

    private fun asrHourAngle(latDeg: Double, declDeg: Double, shadowFactor: Double): Double {
        val lat = Math.toRadians(latDeg)
        val decl = Math.toRadians(declDeg)
        val angle = -atan(1.0 / (shadowFactor + tan(abs(lat - decl))))
        val cosH = (sin(angle) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        return Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0)))
    }

    private fun fixHour(a: Double): Double {
        var res = a - 24.0 * floor(a / 24.0)
        if (res < 0) res += 24.0
        return res
    }
}
