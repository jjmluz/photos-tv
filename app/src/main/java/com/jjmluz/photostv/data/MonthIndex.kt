package com.jjmluz.photostv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The months that have photos, newest first, for the timeline's month picker.
 *
 * The API has no such summary, so it is built by asking for the single newest item at or before
 * a moment, recording that item's month, and continuing from just before the month started:
 * one small request per month with photos. The result is cached between launches.
 */
class MonthIndex(context: Context, private val api: PhotosApi, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("timeline", Context.MODE_PRIVATE)
    private val _months = MutableStateFlow(cached())
    val months: StateFlow<List<YearMonth>> = _months.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            val cached = _months.value
            val found = ArrayList<YearMonth>()
            var before = System.currentTimeMillis() + 2 * 24 * 60 * 60 * 1000L
            try {
                while (true) {
                    val item = api.timelineAt(before, pageSize = 1).items.firstOrNull() ?: break
                    val month = YearMonth.from(item.localDateTime)
                    found += month
                    // Until the scan finishes, keep showing cached months it hasn't reached yet.
                    _months.value = found + cached.filter { it < month }
                    val monthStart = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    before = monthStart - item.timezoneOffsetMs - 1
                }
                _months.value = found
                prefs.edit().putString("months", found.joinToString(",")).apply()
                Log.i("PhotosTV", "Timeline: ${found.size} months with photos")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PhotosTV", "Timeline month scan failed", e)
            }
        }
    }

    fun clear() {
        job?.cancel()
        _months.value = emptyList()
        prefs.edit().clear().apply()
    }

    private fun cached(): List<YearMonth> =
        prefs.getString("months", null)?.split(",")?.mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }.orEmpty()
}
