package de.rohdewald.gps_forwarder

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import android.preference.PreferenceManager
import android.util.Base64
import com.android.volley.toolbox.*
import com.android.volley.*
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.*
import java.text.SimpleDateFormat

private const val noMmtId = "0"

var altitudeAsCounter = false


internal abstract class SendCommand(val location: Location?) {
    var sending: Boolean = false
    var sent: Boolean = false
    abstract val request: String
    abstract val expect: String
    var mmtId: String = noMmtId
    val all_locations: MutableList<Location> = mutableListOf()

    abstract fun post_dict(): HashMap<String, String>
    protected fun formatLocation(): String {
        // as expected by the MapMyTracks protocol
        move_first_location()
        return when (all_locations.size) {
            0 -> ""
            else -> all_locations.map { "${it.latitude} ${it.longitude} ${it.altitude} ${it.time / 1000}" }.joinToString(separator = " ")
        }
    }

    override fun toString(): String {
        move_first_location()
        var result = "Command($request ${all_locations.size} points sending=$sending"
        if (mmtId != noMmtId) result += " mmtId=$mmtId"
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

    fun locationsToString() =
        if (altitudeAsCounter) {
            "points " + all_locations.map { it.altitude.toInt() }.joinToString(separator=",")
        } else {
            "${all_locations.size} points"
        }

    abstract fun toLogString(answer: String): String
}


internal class SendStart(location: Location?) : SendCommand(location) {
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
    override fun toLogString(answer: String): String {
        if (mmtId != noMmtId && answer == expect)
            return "ID $mmtId: started, ${locationsToString()} forwarded"
        else
            return "$this got answer $answer"
    }
}

internal class SendUpdate(location: Location?) : SendCommand(location) {
    override val request = "update_activity"
    override val expect = "activity_updated"
    override fun post_dict() = hashMapOf(
            "request" to request,
            "activity_id" to mmtId,
            "points" to formatLocation())
    override fun toLogString(answer: String) =
        if (mmtId != noMmtId && answer == expect)
            "ID $mmtId: ${locationsToString()} forwarded"
        else
            "$this got answer $answer"
}

internal class SendStop(location: Location?) : SendCommand(location) {
    override val request = "stop_activity"
    override val expect = "activity_stopped"
    override fun post_dict() = hashMapOf(
            "request" to request,
            "activity_id" to mmtId)
    override fun toLogString(answer: String): String {
        if (mmtId != noMmtId && answer == expect)
            return "ID $mmtId: Forwarding stopped"
        else
            return "$this got answer $answer"
    }
}


class MapMyTracks(val mainActivity: MainActivity) {

    private var queue: RequestQueue = Volley.newRequestQueue(mainActivity)
    private var commands: MutableList<SendCommand> = mutableListOf()
    private var running = false
    private var stopping = false
    private lateinit var last_sent_location: Location
    private var location_count = 0.0

    lateinit private var url: String
    lateinit private var username: String
    lateinit private var password: String
    private var min_distance = 0
    private var max_ppt = 100
    private var update_interval = 2L
    private val noMmtId = "0"
    private var currentMmtId: String = noMmtId
    lateinit private var handler: Handler

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        preferenceChanged(prefs, "")
        currentMmtId = mainActivity.restoreString("MmtId", noMmtId)
        running =  hasMmtId()
    }

    fun hasMmtId() = currentMmtId != noMmtId

    fun send(location: Location) {
        schedule()
        if (altitudeAsCounter) {
            location_count += 1
            location.altitude = location_count
        }
        mainActivity.logGpsFix("GPS forwarded: ${location.toLog()}")

        if (!running) {
            running = true
            start(location)
        } else {
            update(location)
        }
    }

    private fun schedule() {
        if (!::handler.isInitialized) {
            handler = Handler().apply {
                val runnable = object : Runnable {
                    override fun run() = try {
                        transmit()
                    } finally {
                        if (stopping && commands.size == 0)
                            stopping = false
                        val interval = if (stopping) 10L else update_interval * 1000L
                        postDelayed(this, interval)
                    }
                }
                post(runnable)
            }
        }
    }

    fun preferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs != null) {
            url = prefs.getString("pref_key_url", "")
            username = prefs.getString("pref_key_username", "")
            password = prefs.getString("pref_key_password", "")
            altitudeAsCounter = prefs.getBoolean("pref_key_elevation_counter", false)
            update_interval = prefs.getString("pref_key_update_interval", "9").toLong()
            min_distance = prefs.getString("pref_key_min_distance", "2").toInt()
        }
    }

    private fun start(location: Location) {
        commands.add(SendStart(location))
        last_sent_location = location
        transmit()
    }

    private fun timefmt(location: Location): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        return formatter.format(location.time)
    }

    private fun update(location: Location) {
        var distance = 100000.0f
        if (::last_sent_location.isInitialized) {
            distance = location.distanceTo(last_sent_location)
        }
        if (!stopping && distance >= min_distance) {
            last_sent_location = location
            var upd_command = SendUpdate(location)
            upd_command.mmtId = currentMmtId
            commands.add(upd_command)
        }
    }

    private fun gotMmtId(newId: String) {
        if (currentMmtId != newId) {
            currentMmtId = newId
            mainActivity.saveString("MmtId",newId)
            if (newId != noMmtId)
                commands.forEach { it.mmtId = newId }
        }
    }

    fun stop() {
        if (currentMmtId != noMmtId) {
            schedule()
            var stop_command = SendStop(null)
            stop_command.mmtId = currentMmtId
            stopping = true
            commands.add(stop_command)
            gotMmtId(noMmtId)
            running = false
        }
    }

    private fun parseString(networkResponse: String): Document? {
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
            mainActivity.logError(e.toString())
            return null
        }
    }

    private fun combine() {
        if (commands.size == 0) return
        if (is_sending()) return
        var new_command = commands[0]
        if (new_command is SendStop) return
        if (new_command.all_locations.size >= max_ppt) return
        var added: MutableList<SendCommand> = mutableListOf()
        for (command in commands.subList(1, commands.size)) {
            if (command !is SendUpdate) continue
            if (command.location == null) continue
            if (new_command.all_locations.size >= max_ppt) break
            new_command.add_location(command.location)
            added.add(command)
        }
        commands = commands.filter { it !in added }.toMutableList()
    }

    private fun is_sending() = commands.count { it.sending } > 0

    private fun transmit() {
        var command: SendCommand
        if (commands.size == 0) {
            return
        }
        if (is_sending()) return
        combine()
        if (commands.size == 0) {
            mainActivity.logError("combine returns 0 commands")
            return
        }
        command = commands[0]
        command.sending = true
        var request = object : StringRequest(Request.Method.POST, url,
                Response.Listener {
                    if (command != commands[0]) throw IllegalStateException("response: wrong command")
                    command.sending = false
                    command.sent = true
                    var document = parseString(it)
                    if (document != null) {
                        var type_item = document.getElementsByTagName("type")
                        var answerForLog = type_item.item(0).textContent
                        if (type_item.length != 1 || type_item.item(0).textContent != command.expect) {
                            mainActivity.logError("unexpected answer " + type_item.item(0) + " map=" + command.toString())
                        }
                        var id_item = document.getElementsByTagName("activity_id")
                        if (id_item.length != 0) {
                            gotMmtId(id_item.item(0).textContent)
                        }
                        mainActivity.logSend(command.toLogString(answerForLog))
                    }
                    commands = commands.drop(1).toMutableList()
                    if (!is_sending() && mainActivity.isFinishing())
                        mainActivity.finishAndRemoveTask()
                },
                Response.ErrorListener {
                    command.sending = false
                    when (it) {
                        is AuthFailureError -> {
                            mainActivity.logError("Authorization failed")
                        }
                        else -> {
                            if (it.networkResponse != null) {
                                var document = parseError(it.networkResponse)
                                if (document != null) {
                                    var reason_item = document.getElementsByTagName("reason")
                                    if (reason_item.length != 0) {
                                        mainActivity.logError(url + it.toString() + ": " + reason_item.item(0).textContent + " command:" + command.toString())
                                    } else {
                                        mainActivity.logError(url + it.toString() + "command:" + command.toString())
                                    }
                                }
                            } else {
                                mainActivity.logError( url + it.toString())
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