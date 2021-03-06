package de.rohdewald.gps_forwarder

import android.content.SharedPreferences
import android.location.Location
import android.support.annotation.LayoutRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

fun ViewGroup.inflate(@LayoutRes layoutRes: Int, attachToRoot: Boolean = false): View {
    return LayoutInflater.from(context).inflate(layoutRes, this, attachToRoot)
}

internal val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")

fun Date.toLogString() = timeFormat.format(this)


fun Location.toLogString(): String {
    val fmt = SimpleDateFormat("YYYY dd.MM HH:mm:ss.SSS")
    val bootTime = java.lang.System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
    val sysTime = System.currentTimeMillis()
    val gpsEtToTime = bootTime + elapsedRealtimeNanos / 1000000L
    val altct = if (altitudeAsCounter) " ${altitude.toInt()}" else ""
    val times = mutableListOf(sysTime, gpsEtToTime, time)
    if (abs(times.min()!! - times.max()!!) > 60000L) {
        // more than 60s
        logError("GPS Time problem: Systime ${fmt.format(sysTime)} location.time ${fmt.format(time)} bootTime ${fmt.format(bootTime)} gpsEtToTime ${fmt.format(gpsEtToTime)}")
    }
    return "${"%.6f".format(latitude)} ${"%.6f".format(longitude)}$altct"
}


fun SharedPreferences.putString(key: String, value: String) =
        edit()?.apply {
            putString(key, value)
            commit()
        }
