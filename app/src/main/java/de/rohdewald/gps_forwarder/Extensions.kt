package de.rohdewald.gps_forwarder

import android.location.Location
import android.support.annotation.LayoutRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.Date

fun ViewGroup.inflate(@LayoutRes layoutRes: Int, attachToRoot: Boolean = false): View {
    return LayoutInflater.from(context).inflate(layoutRes, this, attachToRoot)
}

internal val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")

fun Date.toLog() = timeFormat.format(this)



fun Location.toLog(): String {
    val fmt = SimpleDateFormat("YYYY dd.MM HH:mm:ss.SSS")
    val bootTime = java.lang.System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
    val sysTime = Date()
    val gpsEtToTime = bootTime + elapsedRealtimeNanos / 1000000L
    val altct = if (altitudeAsCounter) " ${altitude.toString()}" else ""
    return "Systime ${fmt.format(sysTime)} location.time ${fmt.format(time)} bootTime ${fmt.format(bootTime)} gpsEtToTime ${fmt.format(gpsEtToTime)} ${"%.6f".format(latitude)} ${"%.6f".format(longitude)}$altct"
}
