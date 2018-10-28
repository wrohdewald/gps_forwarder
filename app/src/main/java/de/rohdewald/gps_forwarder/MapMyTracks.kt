package de.rohdewald.gps_forwarder

import android.content.Context
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
import kotlin.math.max
import kotlin.math.min

private const val noMmtId = "0"
private const val pingInterval = 60000L // even if the positions are all within prefMinDistance send one at least every X ms

var altitudeAsCounter = false
var location_count = 0.0


internal abstract class SendCommand(val location: Location?) {
    var sending: Boolean = false
    var sent: Boolean = false
    abstract val request: String
    abstract val expect: String
    var mmtId: String = noMmtId
    val allLocations: MutableList<Location> = mutableListOf()
    var queueTime = 0L

    abstract fun postDict(): HashMap<String, String>
    protected fun formatLocation(): String {
        // as expected by the MapMyTracks protocol
        moveFirstLocation()
        return when (allLocations.size) {
            0 -> ""
            else -> allLocations.map { "${it.latitude} ${it.longitude} ${it.altitude} ${it.time / 1000}" }.joinToString(separator = " ")
        }
    }

    private fun time_spent(command: SendCommand) = System.currentTimeMillis() - command.queueTime

    override fun toString(): String {
        moveFirstLocation()
        var result = "Command($request ${allLocations.size} points sending=$sending"
        if (mmtId != noMmtId) result += " mmtId=$mmtId"
        return result + ")" + durationString()
    }

    fun durationString() =
        if (queueTime > 0L)
            " in ${System.currentTimeMillis() - queueTime}ms"
        else ""

    private fun moveFirstLocation() {
        if (location != null && allLocations.size == 0)
            allLocations.add(location)
    }

    fun addLocation(additional_location: Location) {
        moveFirstLocation()
        allLocations.add(additional_location)
    }

    fun locationsToString() =
        if (altitudeAsCounter) {
            "points " + allLocations.map { it.altitude.toInt() }.joinToString(separator=",")
        } else {
            "${allLocations.size} points"
        }

    abstract fun toLogStringCore(answer: String): String

    fun toLogString(answer: String) = "${if (mmtId != noMmtId) "ID $mmtId: " else ""} ${if (answer == expect) toLogStringCore(answer) else "$this got $answer"} ${durationString()}"
}


internal class SendStart(location: Location?) : SendCommand(location) {
    override val request = "start_activity"
    override val expect = "activity_started"
    override fun postDict() = hashMapOf(
            "request" to request,
            "title" to "Ich bin ein Tütel",
            "privacy" to "private",
            "activity" to "walking",
            "source" to "gps_forwarder",
            "version" to "${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE}",
            "points" to formatLocation())
    override fun toLogStringCore(answer: String) = "started, ${locationsToString()} forwarded"
}

internal class SendUpdate(location: Location?) : SendCommand(location) {
    override val request = "update_activity"
    override val expect = "activity_updated"
    override fun postDict() = hashMapOf(
            "request" to request,
            "activity_id" to mmtId,
            "points" to formatLocation())
    override fun toLogStringCore(answer: String) = "${locationsToString()} forwarded"
}

internal class SendStop(location: Location?) : SendCommand(location) {
    override val request = "stop_activity"
    override val expect = "activity_stopped"
    override fun postDict() = hashMapOf(
            "request" to request,
            "activity_id" to mmtId)
    override fun toLogStringCore(answer: String) = "Forwarding stopped"
}


class MapMyTracks(val context: Context) {

    private var queue: RequestQueue = Volley.newRequestQueue(context.applicationContext)
    private var commands: MutableList<SendCommand> = mutableListOf()
    var isEnabled = false
    private var running = false
    private var connectionLost = false
    private lateinit var last_sent_location: Location

    lateinit private var prefUrl: String
    lateinit private var prefUsername: String
    lateinit private var prefPassword: String
    private var prefMinDistance = 0
    private var prefUpdateInterval = 2L
    private val maxPointsPerTransfer = 100
    private var updateInterval = 0L
    private var currentMmtId: String = noMmtId
    lateinit private var currentHandler: Handler
    var prefs: SharedPreferences

    init {
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        preferenceChanged(prefs)
        currentMmtId = prefs.getString("MmtId", noMmtId)
        running =  hasMmtId()
        logStartStop("Ich bin MapMyTracks.init mit queue $queue")
    }

    fun hasMmtId() = currentMmtId != noMmtId

    private fun queueCommand(command: SendCommand) {
        if (command.allLocations.size > 0 )
            logSend("GPS queued: ${command.locationsToString()}")
        commands.add(command)
    }

    fun send(location: Location) {

        if (!running) {
            running = true
            start(location)
            schedule(10L)
        } else {
            update(location)
            schedule()
        }
    }

    private fun schedule(newInterval: Long = 1000L * prefUpdateInterval) {
        // newInterval in ms.
        // 0L: do not schedule anymore
        // if this is a new interval, do not currentHandler.postDelayed() but
        // create a new currentHandler and use that one from now on. After the
        // latest event from the old currentHandler happens, it will stay silent.
        if (newInterval != updateInterval) {
            updateInterval = newInterval
            currentHandler = Handler().apply {
                val thisHandler = this
                val runnable = object : Runnable {
                    override fun run() = try {
                        transmit()
                    } finally {
                        if (thisHandler == currentHandler && updateInterval > 0L) {
                            // just let previous handlers run out when their queue is empty
                            logSend("${currentHandler} next transmission in $updateInterval ms")
                            postDelayed(this, updateInterval)
                        }
                    }
                }
                post(runnable)
            }
        }
    }

    fun preferenceChanged(prefs: SharedPreferences?) {
        if (prefs != null) {
            prefUrl = prefs.getString("pref_key_url", "")
            prefUsername = prefs.getString("pref_key_username", "")
            prefPassword = prefs.getString("pref_key_password", "")
            altitudeAsCounter = prefs.getBoolean("pref_key_elevation_counter", false)
            prefUpdateInterval = prefs.getString("pref_key_update_interval", "9").toLong()
            prefMinDistance = prefs.getString("pref_key_min_distance", "2").toInt()
        }
    }

    private fun start(location: Location) {
        queueCommand(SendStart(location))
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
        if (distance >= prefMinDistance || System.currentTimeMillis() - last_sent_location.time > pingInterval) {
            last_sent_location = location
            var upd_command = SendUpdate(location)
            upd_command.mmtId = currentMmtId
            queueCommand(upd_command)
        }
    }

    private fun gotMmtId(newId: String) {
        if (currentMmtId != newId) {
            currentMmtId = newId
            logStartStop(("got new MmtId ${newId}"))
            prefs.putString("MmtId",newId)
            if (newId != noMmtId)
                commands.forEach { it.mmtId = newId }
        }
    }

    fun stop() {
        if (currentMmtId != noMmtId) {
            var stop_command = SendStop(null)
            stop_command.mmtId = currentMmtId
            queueCommand(stop_command)
            logError("MMT.stop sets noMmtId")
            gotMmtId(noMmtId)
            running = false
            schedule(10L)
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
            logError(e.toString())
            return null
        }
    }

    private fun combine() {
        if (commands.size == 0) return
        if (is_sending()) return
        var new_command = commands[0]
        if (new_command is SendStop) return
        if (new_command.allLocations.size >= maxPointsPerTransfer) return
        var added: MutableList<SendCommand> = mutableListOf()
        for (command in commands.subList(1, commands.size)) {
            if (command !is SendUpdate) continue
            if (command.location == null) continue
            if (new_command.allLocations.size >= maxPointsPerTransfer) break
            new_command.addLocation(command.location)
            added.add(command)
        }
        commands = commands.filter { it !in added }.toMutableList()
    }

    private fun is_sending() = commands.count { it.sending } > 0

    private fun transmit() {
        var command: SendCommand
        if (commands.size == 0) {
            schedule(0L)
            return
        }
        if (is_sending()) return
        combine()
        if (commands.size == 0) {
            logError("combine returns 0 commands")
            return
        }
        command = commands[0]
        command.sending = true
        var request = object : StringRequest(Request.Method.POST, prefUrl,
                Response.Listener {
                    if (connectionLost) {
                        logError("Regained connection to $prefUrl")
                        connectionLost = false
                        updateInterval = prefUpdateInterval
                    }
                    schedule()
                    if (command != commands[0]) throw IllegalStateException("response: wrong command")
                    command.sending = false
                    command.sent = true
                    var document = parseString(it)
                    if (document != null) {
                        var type_item = document.getElementsByTagName("type")
                        var answerForLog = type_item.item(0).textContent
                        if (type_item.length != 1 || type_item.item(0).textContent != command.expect) {
                            logError("unexpected answer " + type_item.item(0) + " map=" + command.toString())
                        }
                        var id_item = document.getElementsByTagName("activity_id")
                        if (id_item.length != 0) {
                            gotMmtId(id_item.item(0).textContent)
                        }
                        logSend(command.toLogString(answerForLog))
                    }
                    commands = commands.drop(1).toMutableList()
                },
                Response.ErrorListener {
                    command.sending = false
                    when (it) {
                        is AuthFailureError -> {
                            logError("Authorization failed")
                        }
                        else -> {
                            if (it.networkResponse != null) {
                                var document = parseError(it.networkResponse)
                                if (document != null) {
                                    var reason_item = document.getElementsByTagName("reason")
                                    if (reason_item.length != 0) {
                                        logError(prefUrl + it.toString() + ": " + reason_item.item(0).textContent + " command:" + command.toString())
                                    } else {
                                        logError(prefUrl + it.toString() + "command:" + command.toString())
                                    }
                                }
                            } else if ("NoConnectionError" in it.toString()) {
                                if (!connectionLost) {
                                    logError("Lost connection to $prefUrl")
                                    connectionLost = true
                                }
                                // double updateInterval up to 5 minutes
                                val base = max(prefUpdateInterval, updateInterval)
                                schedule(1000L * min(base * 2, 300))
                            } else {
                                logError( prefUrl + it.toString())
                            }
                        }
                    }
                }) {
            override fun getParams(): MutableMap<String, String> = command.postDict()
            override fun getHeaders(): Map<String, String> = hashMapOf(
                    "Authorization" to "Basic " + Base64.encodeToString("$prefUsername:$prefPassword".toByteArray(Charsets.UTF_8), Base64.DEFAULT))
        }
        request.retryPolicy = DefaultRetryPolicy(10000,DefaultRetryPolicy.DEFAULT_MAX_RETRIES, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
        queue.add(request)
        command.queueTime = System.currentTimeMillis()
    }
}
