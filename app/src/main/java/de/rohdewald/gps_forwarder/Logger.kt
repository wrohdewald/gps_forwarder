package de.rohdewald.gps_forwarder

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Color.rgb
import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import java.util.Date
import kotlinx.android.synthetic.main.log_row.view.*
import kotlinx.android.synthetic.main.activity_main.*
import android.support.v7.widget.GridLayoutManager
import android.util.DisplayMetrics

fun MainActivity.loggerPreferenceChanged() {
    logThis = get_logThis()
    val newCellTextSize =  prefs.getString("pref_key_fontsize", "12").toFloat()
    val arrayId = when (newCellTextSize.toInt()) {
        8 -> R.array.logSpans8
        10 -> R.array.logSpans10
        14 -> R.array.logSpans14
        16 -> R.array.logSpans16
        else -> R.array.logSpans12
    }
    val newSpans = resources.getIntArray(arrayId).toMutableList()
    newSpans.add(100 - newSpans.sum())

    if (newCellTextSize != cellTextSize || newSpans != logSpans) {
        cellTextSize = newCellTextSize
        logSpans = newSpans
        logColumns = logSpans.size
        invalidateView(logView)
        logMetrics()
    }
}

private fun MainActivity.logMetrics() {
    if (logView != null) {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val widthDp = resources.configuration.screenWidthDp
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        logStartStop("Text size $cellTextSize, ${if (portrait) "Portrait" else "Landscape"} width ${widthDp}dp, using column spans $logSpans")
    }
}

fun MainActivity.setupLogger(logView:RecyclerView) {
    class MySpanSizeLookup: GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) = logSpans[position % logColumns]

        // we can optimize this because we know all rows have the same number of items
        override fun getSpanIndex(position: Int, spanCount: Int) =
                logSpans.subList(0, (position % logColumns)).sum()

    }
    currentLogView = logView
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
    logMetrics()
}

fun logStartStop(msg: String) = log(LogType.StartStop,msg)
fun logGpsFix(msg: String) = log(LogType.GPS_Fix,msg)
fun logSend(msg: String) = log(LogType.Send,msg)
fun logError(msg: String) = log(LogType.Error,msg)


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
                    val type_array = LogType.values()
                    type_array[findValue.toInt()]
                } catch (e: Exception) {
                    LogType.Error
                }
            }
        }
        fun from(values: Set<String>) : Set<LogType> {
            return values.map { LogType.from(it) }.toSet()
        }
    }
}


// The rest is internal

// this must correspond to what bindRow() does
internal var logSpans : List<Int> = listOf()
internal var logColumns = 2

internal val logItems = mutableListOf<LogItem>()

internal var currentLogView: RecyclerView? = null

var scrollToEnd = true

internal lateinit var logAdapter: LogRecyclerAdapter

internal fun invalidateView(view:RecyclerView?) =
        view?.apply {
            recycledViewPool.clear()
            invalidate()
            this.adapter.notifyDataSetChanged()
        }

internal fun log(type: LogType, msg: String) {
    if (type in logThis) {
        logItems.add(LogItem(type, msg))
        logAdapter.notifyItemRangeInserted(logItems.size * logColumns - logColumns, logColumns)
        if (scrollToEnd)
            currentLogView?.scrollToPosition(logAdapter.itemCount - 1)
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
internal var cellTextSize = 0f

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
            view.itemColumn.setTextSize(cellTextSize)
            val column = position % logColumns
            if (column == 0) {
                view.itemColumn.text = item.time.toLogString()
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

