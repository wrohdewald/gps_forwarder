package de.rohdewald.gps_forwarder

import android.content.SharedPreferences
import android.location.Location
import android.preference.PreferenceManager
import android.util.Log
import android.util.Base64
import com.android.volley.toolbox.*
import com.android.volley.*
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.timerTask
import java.text.SimpleDateFormat


abstract class SendCommand(val location: Location?) {
    var sending: Boolean = false
    var sent: Boolean = false
    abstract val request: String
    abstract val expect: String
    lateinit var id : String
    val all_locations: MutableList<Location> = mutableListOf()

    abstract fun post_dict() : HashMap<String, String>
    protected fun formatLocation() : String {
        move_first_location()
        return when (all_locations.size) {
            0 -> ""
            else -> all_locations.map {"${it.latitude} ${it.longitude} ${it.altitude} ${it.time / 1000}"}.joinToString(separator=" ")
        }
    }

    override fun toString(): String {
        move_first_location()
        var result = "Command($request ${all_locations.size} points sending=$sending"
        if (::id.isInitialized) result += " id=$id"
        return result + ")"
    }

    private fun move_first_location() {
        if (location != null && all_locations.size == 0)
            all_locations.add(location)
    }

    fun add_location(additional_location: Location) {
        move_first_location()
        all_locations.add(additional_location)
    }
}


class SendStart(location: Location?) : SendCommand(location) {
    override val request = "start_activity"
    override val expect = "activity_started"
    override fun post_dict() = hashMapOf(
            "request" to request,
            "title" to "Ich bin ein Tütel",
            "privacy" to "private",
            "activity" to "walking",
            "source" to "gps_forwarder",
            "version" to "${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE}",
            "points" to formatLocation())
}

class SendUpdate(location: Location?) : SendCommand(location) {
    override val request = "update_activity"
    override val expect = "activity_updated"
    override fun post_dict() = hashMapOf(
            "request" to request,
            "activity_id" to id,
            "points" to formatLocation())
}

class SendStop(location: Location?) : SendCommand(location) {
    override val request = "stop_activity"
    override val expect = "activity_stopped"
    override fun post_dict() = hashMapOf(
            "request" to request,
            "activity_id" to id)
}


class MapMyTracks(val mainActivity: MainActivity) {

    private val TAG = "WR.MapMyTracks"
    var queue = Volley.newRequestQueue(mainActivity)
    var commands : MutableList<SendCommand> = mutableListOf()
    var send_timer = Timer()
    var start_sent = false
    var running = false
    lateinit var last_sent_location: Location
    var location_count = 0.0
    private var lock = ReentrantLock()

    lateinit var url: String
    lateinit var username: String
    lateinit var password: String
    var altitude_count = false
    var min_distance = 0
    var max_ppt = 100
    var update_interval = 2L

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        preferenceChanged(prefs,"")
    }
    // vor Ausschalten retten: commands, running,start_sent,location_count,id
    var id: String = "0"

    fun send(location: Location) {
        Log.d(TAG + ":TIME","point time: ${timefmt(location)}")
        if (altitude_count) {
            location_count += 1
            location.altitude = location_count
        }

        if (!start_sent) {
            start_sent = true
            start(location)
        } else {
            update(location)
        }
    }

    private fun load_all_settings() {

    }

    fun preferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs != null) {
            url = prefs.getString("pref_key_url", "")
            username = prefs.getString("pref_key_username", "")
            password = prefs.getString("pref_key_password", "")
            lock.lock()
            try {
                altitude_count = prefs.getBoolean("pref_key_elevation_counter",false)
                update_interval = prefs.getString("pref_key_update_interval", "").toLong()
                min_distance = prefs.getString("pref_key_min_distance", "2").toInt()
            } finally {
                lock.unlock()
            }
            when (key) {
                "pref_key_update_interval" -> schedule()
            }

        }
    }

    private fun start(location: Location) {
        lock.lock()
        try {
            commands.add(SendStart(location))
            running = true
            schedule()
            last_sent_location = location
            transmit()
        } finally {
            lock.unlock()
        }
    }

    private fun schedule() {
        lock.lock()
        try {
            if (send_timer != null) {
                try {
                    send_timer.cancel()
                } catch (e: java.lang.Exception) {
                }
            }
            send_timer = Timer()
            send_timer.schedule(timerTask { transmit() }, 0L, 1000L * update_interval)
        } finally {
            lock.unlock()
        }
        Log.d(TAG,"Scheduler set to $update_interval seconds")
    }

    private fun timefmt(location: Location): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        return formatter.format(location.time)
    }

    private fun update(location: Location) {
        lock.lock()
        try {
            if (location.time < last_sent_location.time) {
                Log.e(TAG,"Time runs backwards. Last: ${timefmt(last_sent_location)} New: ${timefmt(location)}")
            }
            val distance = location.distanceTo(last_sent_location)
            if (running && distance >= min_distance) {
                last_sent_location = location
                commands.add(SendUpdate(location))
            }
        } finally {
            lock.unlock()
        }
    }

    fun stop() {
        lock.lock()
        try {
            var command = SendStop(null)
            commands.add(command)
            send_timer.schedule(timerTask { transmit() }, 0L, 1L) // finish fast
            running = false
        } finally {
            lock.unlock()
        }
    }

    private fun parseString(networkResponse: String): Document? {
        Log.d(TAG,"answer from server:" + networkResponse)
        if (networkResponse == "") {
            return null
        }
        var documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return documentBuilder.parse(networkResponse.byteInputStream(Charsets.UTF_8))
    }

    private fun parseError(networkResponse: NetworkResponse): Document? {
        var documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        try {
            return documentBuilder.parse(networkResponse.data.inputStream())
        } catch (e: Exception) {
            Log.e(TAG,"ERROR: " + e.toString())
            return null
        }
    }

    private fun combine() {
        if (commands.size == 0) return
        if (is_sending()) return
        var new_command = commands[0]
        if (new_command.all_locations.size >= max_ppt) return
        var added: MutableList<SendCommand> = mutableListOf()
        if (new_command is SendStop) return
        for (command in commands.subList(1, commands.size)) {
            if (command !is SendUpdate) continue
            if (command.location == null) continue
            if (new_command.all_locations.size >= max_ppt) break
            new_command.add_location(command.location)
            added.add(command)
        }
        commands = commands.filter { it !in added}.toMutableList()
    }

    private fun is_sending() = commands.count { it.sending } > 0

    private fun transmit() {
        var command: SendCommand
        lock.lock()
        try {
            if (commands.size == 0) {
                if (!running)
                    send_timer.cancel()
                return
            }
            if (is_sending()) return
            combine()
            command = commands[0]
            command.sending = true
            command.id = id
        } finally {
            lock.unlock()
        }
        Log.d(TAG,"----> transmit $command")
        var request = object: StringRequest(Request.Method.POST, url,
                Response.Listener {
                    var document = parseString(it)
                    if (document != null) {
                        var type_item = document.getElementsByTagName("type")
                        if (type_item.length != 1 || type_item.item(0).textContent != command.expect) {
                            Log.e(TAG, "unexpected answer " + type_item.item(0) + " map=" + command.toString())
                        }
                        var id_item = document.getElementsByTagName("activity_id")
                        if (id_item.length != 0) {
                            id = id_item.item(0).textContent
                        }
                        lock.lock()
                        try {
                            if (command != commands[0]) throw IllegalStateException("response: wrong command")
                            command.sending = false
                            command.sent = true
                            commands = commands.drop(1).toMutableList()
                            if (!is_sending() && mainActivity.isFinishing())
                                mainActivity.finishAndRemoveTask()
                        } finally {
                            lock.unlock()
                        }
                    }
                },
                Response.ErrorListener {
                    command.sending = false
                    when (it) {
                        is AuthFailureError -> {
                            Log.d(TAG,"Authorization failed")
                        } else -> {
                            if (it.networkResponse != null) {
                                var document = parseError(it.networkResponse)
                                if (document != null) {
                                    var reason_item = document.getElementsByTagName("reason")
                                    if (reason_item.length != 0) {
                                        Log.d("ERR1", url + it.toString() + ": " + reason_item.item(0).textContent + " command:" + command.toString())
                                    } else {
                                        Log.d("ERR2", url + it.toString() + "command:" + command.toString())
                                    }
                                }
                            } else {
                                Log.d(TAG,url + it.toString())
                            }
                        }
                    }
                    if (mainActivity.isFinishing())
                        mainActivity.finishAndRemoveTask()
                }) {
            override fun getParams(): MutableMap<String, String> = command.post_dict()
            override fun getHeaders(): Map<String, String> = hashMapOf(
                    "Authorization" to "Basic " + Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.DEFAULT))
        }
        queue.add(request)
    }
}