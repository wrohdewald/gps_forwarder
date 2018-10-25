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

fun MainActivity.loggerPreferenceChanged() {
    logThis = get_logThis()
}

fun MainActivity.setupLogger(logView:RecyclerView) {
    class MySpanSizeLookup: GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) = logSpans[position % logColumns]

        // we can optimize this because we know all rows have the same number of items
        override fun getSpanIndex(position: Int, spanCount: Int) =
                logSpans.subList(0, (position % logColumns)).sum()

    }
    val gridLayoutManager = GridLayoutManager(this, logSpans.sum())
    logView.layoutManager = gridLayoutManager
    gridLayoutManager.spanSizeLookup = MySpanSizeLookup()
    logAdapter = LogRecyclerAdapter(logItems)
    logView.adapter = logAdapter

    // add a scroll listener
    logView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView?, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            val totalItemCount = recyclerView!!.layoutManager.itemCount
            val lastVisibleItemPosition = gridLayoutManager.findLastVisibleItemPosition()
            scrollToEnd = totalItemCount == lastVisibleItemPosition + 1
        }
    })
}

fun MainActivity.logStartStop(msg: String) = log(LogType.StartStop,msg)
fun MainActivity.logGpsFix(msg: String) = log(LogType.GPS_Fix,msg)
fun MainActivity.logSend(msg: String) = log(LogType.Send,msg)
fun MainActivity.logError(msg: String) = log(LogType.Error,msg)


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
        fun from(values: Set<String>) : Set<LogType> {
            return values.filter { it[0] !in "0123456789"}.map { LogType.from(it) }.toSet()
        }
    }
}


// The rest is internal

// this must correspond to what bindRow() does
internal val logSpans = listOf(1,4)
internal val logColumns = logSpans.size

internal val logItems = mutableListOf<LogItem>()

internal var scrollToEnd = true

internal lateinit var logAdapter: LogRecyclerAdapter

internal fun MainActivity.log(type: LogType, msg: String) {
    if (type in logThis) {
        logItems.add(LogItem(type, msg))
        logAdapter.notifyItemRangeInserted(logItems.size * logColumns - logColumns, logColumns)
        if (scrollToEnd)
            logView.scrollToPosition(logAdapter.itemCount - 1)
    }
}

internal fun MainActivity.get_logThis() : Set<LogType> {
    var foundSettings = prefs.getStringSet("pref_key_log", HashSet<String>())
    return LogType.from(foundSettings)
}

internal open class LogItem(val type: LogType, val msg: String) {
    val time = Date()
    val tid = android.os.Process.myTid()
}

internal var logThis = setOf<LogType>()

internal class LogRecyclerAdapter(private val logLines: List<LogItem>) : RecyclerView.Adapter<LogRecyclerAdapter.LogItemHolder>() {

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

