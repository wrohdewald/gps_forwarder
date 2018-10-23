package de.rohdewald.gps_forwarder

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.util.Log
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.view.Menu
import android.view.MenuInflater
import android.support.v7.widget.LinearLayoutManager
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_main.*
import java.util.HashSet

// TODO import android.net.ConnectivityManager.NetworkCallback


// TODO: if (!PowerManager.isIgnoringBatteryOptimizations (String packageName))
// fire intent android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS

class MainActivity : AppCompatActivity(), android.location.LocationListener, SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sender.preferenceChanged(sharedPreferences, key)
        logThis = get_logThis()
    }

    private fun get_logThis() : List<LogType> {
        var foundSettings = prefs.getStringSet("pref_key_log", HashSet<String>())
        return foundSettings.map { LogType.from(it) }
    }

    private val TAG = "WR.MainActivity"
    private val got_permission = 1234
    lateinit private var mLocationManager: LocationManager
    lateinit private var sender : MapMyTracks
    lateinit var logAdapter: LogRecyclerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    lateinit var prefs: SharedPreferences
    var logThis = listOf<LogType>()


    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        Log.i(TAG, "statusChanged: provider:" + provider + " status:" + status)
    }

    fun onClickSettings(item: MenuItem) {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)

    }

    override fun onDestroy() {
        logStartStop("onDestroy")
        sender.stop()
        // TODO: wait until sender queue is empty
        // Android may say Activity destroy timeout.
        // We have to catch the exit button and retard destroy()
        // until all is sent or aborted by user.
        super.onDestroy()
    }

/*
    override fun onAvailable(network: Network) {
        Log.i(TAG,"Network available: $network ")
    }
*/

    override fun onResume() {
    super.onResume()
    prefs.registerOnSharedPreferenceChangeListener(this)
}

    override fun onPause() {
        super.onPause()
//        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }


    override fun onProviderEnabled(provider: String) {
        logStartStop("providerEnabled:" + provider)
    }

    override fun onProviderDisabled(provider: String) {
        logStartStop("providerDisabled:" + provider)
    }

    override fun onLocationChanged(location: Location) {
        if (!isFinishing() && location.provider == "gps") {
            logGpsFix("got GPS ${"%.3f".format(location.latitude)} ${"%.3f".format(location.longitude)}")
            sender.send(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // TODO: kommt hier nie durch, wenn im Handy Lokalisation aus ist
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        logThis = get_logThis()
        prefs.registerOnSharedPreferenceChangeListener(this)  // when starting, onResume is never called
        linearLayoutManager = LinearLayoutManager(this)
        logView.layoutManager = linearLayoutManager
        logAdapter = LogRecyclerAdapter(logItems)
        logView.adapter = logAdapter
        logStartStop("GPS Forwarder started")
        val manager : LocationManager? = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        if (manager == null) {
            logError("Cannot get a LocationManager")
            finishAndRemoveTask()
        } else {
            mLocationManager = manager
            sender = MapMyTracks(this)
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
    val inflater: MenuInflater = menuInflater
    inflater.inflate(R.menu.main, menu)
    return true
}
}

