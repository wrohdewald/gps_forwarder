package de.rohdewald.gps_forwarder

import android.content.Intent
import android.graphics.Color
import android.graphics.Color.rgb
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.Date
import java.time.LocalDateTime
import kotlinx.android.synthetic.main.log_row.view.*
import kotlinx.android.synthetic.main.activity_main.*
import android.support.v7.widget.GridLayoutManager

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

// this must correspond to what bindRow() does
val logSpans = listOf(1,4)
val logColumns = logSpans.size

internal val logItems = mutableListOf<LogItem>()

fun MainActivity.log(type: LogType, msg: String) {
    if (type in this.logThis) {
        logItems.add(LogItem(type, msg))
        this.logAdapter.notifyItemRangeInserted(logItems.size * logColumns - logColumns, logColumns)
        // TODO: activate autoscroll to end of list if the scrollbar is at end. Disable otherwise.
        // https://stackoverflow.com/questions/26543131/how-to-implement-endless-list-with-recyclerview

 //       this.logView.scrollToPosition(this.logAdapter.itemCount - 1)
    }
}

fun MainActivity.logStartStop(msg: String) = log(LogType.StartStop,msg)
fun MainActivity.logGpsFix(msg: String) = log(LogType.GPS_Fix,msg)
fun MainActivity.logSend(msg: String) = log(LogType.Send,msg)
fun MainActivity.logError(msg: String) = log(LogType.Error,msg)

open class LogItem(val type: LogType, val msg: String) {
    val time = Date()
    val tid = android.os.Process.myTid()
}

class LogRecyclerAdapter(private val logLines: List<LogItem>) : RecyclerView.Adapter<LogRecyclerAdapter.LogItemHolder>() {

    class LogItemHolder(v: View) : RecyclerView.ViewHolder(v), View.OnClickListener {
        private var view: View = v
        private var item: LogItem? = null

        init {
            v.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            // because it is abstract
        }

        fun bindRow(item: LogItem, position: Int) {
            val color = when (item.type) {
                LogType.Error -> Color.RED
                LogType.GPS_Fix -> Color.BLUE
                LogType.Send -> rgb(0,87,74) // 0x00574a
                LogType.StartStop -> Color.BLACK
            }
            view.itemColumn.setTextColor(color)
            val column = position % logColumns
            if (column == 0) {
                view.itemColumn.text = item.time.toLog()
            } else if (column == 1) {
                view.itemColumn.text = item.msg
            } else {
                view.itemColumn.text = "Column $column"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogRecyclerAdapter.LogItemHolder {
        val inflatedView = parent.inflate(R.layout.log_row, false)
        return LogItemHolder(inflatedView)
    }

    override fun getItemCount() = logLines.size * logColumns

    override fun onBindViewHolder(holder: LogRecyclerAdapter.LogItemHolder, position: Int) {
        val item = logLines[position / logColumns]
        holder.bindRow(item, position)

    }
}

