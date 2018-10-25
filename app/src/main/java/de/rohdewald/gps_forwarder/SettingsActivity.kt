package de.rohdewald.gps_forwarder

import android.os.Bundle
import android.widget.Toast
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.preference.*
import android.util.Log
import kotlinx.android.synthetic.main.*


// TODO proper default settings. Why do I have to define them twice?

class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    lateinit var fragment: PreferenceFragment
    lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        fragment = SettingsFragment()
        fragmentManager.beginTransaction()
                .replace(android.R.id.content,fragment)
                .commit()
        PreferenceManager.setDefaultValues(this, R.xml.preference, false)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        updateSummaries()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }



    private fun setSummary(key: String, template: (String) -> String) {
        val pref = fragment.findPreference(key)
        if (pref != null) {
            val value =
                if (key == "pref_key_log") {
                    val values = prefs.getStringSet(key,HashSet<String>())
                    values.filter { it[0] !in "0123456789"}.map { LogType.from(it) }.toSet().joinToString()
                } else if (pref is CheckBoxPreference) {
                    prefs.getBoolean(key, false).toString()
                } else {
                    prefs.getString(key, "")
                }

            pref.summary = template(value)
        }
    }

    fun updateSummaries() {
        setSummary("pref_key_url") {"$it"}
        setSummary("pref_key_username") {"$it"}
        setSummary("pref_key_password") {if (it == "") "not set" else ""}
        setSummary("pref_key_min_distance") {if (it == "0") "Transmit all positions" else "When distance is below $it meters"}
        setSummary("pref_key_update_interval") {"Send every $it seconds"}
        setSummary("pref_key_elevation_counter") {"Useful for debugging"}
        setSummary("pref_key_log") {"$it"}
    }
}

class SettingsFragment() : PreferenceFragment() {
    var activity: SettingsActivity? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preference)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        if (context is SettingsActivity) {
            activity = context
        }
    }

    override fun onDetach() {
        activity = null
        super.onDetach()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (activity != null) {
            (activity as SettingsActivity).updateSummaries()
        }
    }
}
