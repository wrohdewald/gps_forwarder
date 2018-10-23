package de.rohdewald.gps_forwarder

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
