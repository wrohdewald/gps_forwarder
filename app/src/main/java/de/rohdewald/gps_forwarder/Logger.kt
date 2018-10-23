package de.rohdewald.gps_forwarder

import android.content.Intent
import android.graphics.Color
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.Date
import java.time.LocalDateTime
import kotlinx.android.synthetic.main.log_row.view.*
import kotlinx.android.synthetic.main.activity_main.*

enum class LogType(val type: Int = 0) {
    StartStop,
    GPS_Fix(1),
    Send(2),
    Error(3);

    companion object {
        fun from(findValue: String): LogType {
            return try {
                LogType.valueOf(findValue)
            } catch (e: Exception) {
                try {
                    val it_int = findValue.toInt()
                    val type_array = LogType.values()
                    type_array[it_int]
                } catch (e: Exception) {
                    LogType.Error
                }
            }
        }
    }
}

internal val logItems = mutableListOf<LogItem>()

fun MainActivity.log(type: LogType, short: String, long: String? = null) {
    if (type in this.logThis) {
        logItems.add(LogItem(type, short, long))
        this.logAdapter.notifyItemInserted(logItems.size)
        this.logView.scrollToPosition(this.logAdapter.itemCount - 1)
    }
}

fun MainActivity.logStartStop(short: String, long: String? = null) = log(LogType.StartStop,short,long)
fun MainActivity.logGpsFix(short: String, long: String? = null) = log(LogType.GPS_Fix,short,long)
fun MainActivity.logSend(short: String, long: String? = null) = log(LogType.Send,short,long)
fun MainActivity.logError(short: String, long: String? = null) = log(LogType.Error,short,long)

open class LogItem(val type: LogType, val short: String, val long: String? = null) {
    val time = Date()
    val tid = android.os.Process.myTid()
}

class LogStartStop(short: String, long: String? = null) : LogItem(LogType.StartStop, short, long)

class LogRecyclerAdapter(private val logLines: List<LogItem>) : RecyclerView.Adapter<LogRecyclerAdapter.LogItemHolder>() {

    class LogItemHolder(v: View) : RecyclerView.ViewHolder(v), View.OnClickListener {
        private var view: View = v
        private var item: LogItem? = null

        init {
            v.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            Log.d("RecyclerView", "CLICK!")
        }

        companion object {
            private val PHOTO_KEY = "PHOTO"
        }
        fun bindItem(item: LogItem) {
            // TODO: use item.type for different colors
            this.item = item
            view.itemTime.text = item.time.toLog()
            view.itemShort.text = "${item.tid} ${item.short}"
    //        view.itemShort.text = item.short
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogRecyclerAdapter.LogItemHolder {
        val inflatedView = parent.inflate(R.layout.log_row, false)
        return LogItemHolder(inflatedView)
    }

    override fun getItemCount() = logLines.size

    override fun onBindViewHolder(holder: LogRecyclerAdapter.LogItemHolder, position: Int) {
        val item = logLines[position]
        holder.bindItem(item)

    }
}

