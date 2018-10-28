package de.rohdewald.gps_forwarder

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.lang.System.currentTimeMillis

// TODO import android.net.ConnectivityManager.NetworkCallback


// TODO: if (!PowerManager.isIgnoringBatteryOptimizations (String packageName))
// fire intent android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS


class SenderSingleton constructor(val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: SenderSingleton? = null
        fun getInstance(context: Context) =
                INSTANCE ?: synchronized(this) {
                    INSTANCE ?: SenderSingleton(context).also {
                        INSTANCE = it
                    }
                }
        fun exists() =  INSTANCE != null
    }


    val sender: MapMyTracks by lazy {
        // applicationContext is key, it keeps you from leaking the
        // Activity or BroadcastReceiver if someone passes one in.
        MapMyTracks(context)
    }
}

class MainActivity : AppCompatActivity(), android.location.LocationListener, SharedPreferences.OnSharedPreferenceChangeListener {


    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sender.preferenceChanged(sharedPreferences)
        loggerPreferenceChanged()
    }

    private val got_permission = 1234
    lateinit private var mLocationManager: LocationManager
    private lateinit var sender : MapMyTracks
    lateinit var prefs: SharedPreferences
    var prevLocationTime = 0L
    private var prevAppliedTimeDelta = 0
    private var currentMenu: Menu? = null

    fun onClickSettings(item: MenuItem) {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)

    }
// TODO: nach Drehen ist StartStop Icon falsch

    fun onClickStart(item: MenuItem) {
        sender.isEnabled = true
        logStartStop("Forwarding started")
        updateActionBar()
    }

    fun onClickStop(item: MenuItem) {
        sender.isEnabled = false
        logStartStop("Forwarding stopped")
        sender.stop()
        updateActionBar()
    }

    fun onClickTail(item: MenuItem) {
        // I would like this to be an extension in Logger.kt but then
        // the resource inflater will not find it at runtime
        scrollToEnd = true
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onProviderEnabled(provider: String) {
        logGpsFix("providerEnabled:" + provider)
    }

    override fun onProviderDisabled(provider: String) {
        logGpsFix("providerDisabled:" + provider)
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        logGpsFix("statusChanged: provider:" + provider + " status:" + status)
    }

    override fun onLocationChanged(location: Location) {
        if (altitudeAsCounter) {
            location_count += 1
            location.altitude = location_count
        }
        if (location.provider == "gps") {
            val hour = 1000L * 3600L
            val thisTime = currentTimeMillis()
            fun deltaHours() = (thisTime - location.time) /  hour

            // for whatever reason we are sometimes getting the same location several times.
            // Maybe some interaction between the android studio debugger and the app?
            if (location.time == prevLocationTime) return
            prevLocationTime = location.time

            // if Oruxmaps records more than 24 hours, location.time
            // goes back by 24 hours.
            // TODO: take a closer look. What exactly do I have to restart
            // to avoid this? What happens after 2 days?
            var appliedDelta = 0
            while (deltaHours() in 23..25) {
                location.time += hour * 24
                appliedDelta += 1
            }
            if (appliedDelta != prevAppliedTimeDelta) {
                logError("Will add $appliedDelta days to GPS times")
                prevAppliedTimeDelta = appliedDelta
            }
            if (sender.isEnabled)
                sender.send(location)
            else
                logGpsFix("GPS ignored: ${location.toLogString()}")
        } else {
            logError("GPS from other source ignored: ${location.provider} ${location.toLogString()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // TODO: kommt hier nie durch, wenn im Handy Lokalisation aus ist
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)  // when starting, onResume is never called
        loggerPreferenceChanged()
        setContentView(R.layout.activity_main)
        setupLogger(logView)
        val senderExisted = SenderSingleton.exists()
        sender = SenderSingleton.getInstance(applicationContext).sender
        logStartStop("Activity $this new sender: $sender")
        if (!senderExisted) {
            sender.isEnabled = sender.hasMmtId()  // if the previous app instance was abruptly killed, just continue
            if (sender.isEnabled) {
                logStartStop("GPS Forwarder continuing after interruption")
            } else
                logStartStop("GPS Forwarder started")
        }
        val manager : LocationManager? = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        if (manager == null) {
            logError("Cannot get a LocationManager")
            finishAndRemoveTask()
        } else {
            mLocationManager = manager
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), got_permission)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            got_permission -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    try {
                        mLocationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0.0f, this)
                    } catch (e: SecurityException) {
                        logError("permission revoked?")
                    }
                } else {
                    finishAndRemoveTask()
                }
                return
            }
            else -> {
                logError("App has no permission to use location service")
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        updateActionBar()
        return super.onCreateOptionsMenu(menu)
    }

    private fun updateActionBar() {
            currentMenu?.findItem(R.id.start_action)?.setVisible(!sender.isEnabled)
            currentMenu?.findItem(R.id.stop_action)?.setVisible(sender.isEnabled)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
            currentMenu = menu
            updateActionBar()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onBackPressed() {
        if (sender.isEnabled) {
            Toast.makeText(this, "Please stop forwarding first", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }
}
