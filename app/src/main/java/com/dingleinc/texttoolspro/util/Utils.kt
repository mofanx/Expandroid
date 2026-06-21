package com.dingleinc.texttoolspro.util

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Utils {
    fun getTheRealFormat(format: String): String {
        val now = LocalDateTime.now()
        var result = format
        result = result.replace("%Y", "yyyy")
        result = result.replace("%m", "MM")
        result = result.replace("%B", "MMMM")
        result = result.replace("%b", "MMM")
        result = result.replace("%h", "MMM")
        result = result.replace("%d", "dd")
        result = result.replace("%e", "d")
        result = result.replace("%A", "dddd")
        result = result.replace("%a", "ddd")
        result = result.replace("%j", now.dayOfYear.toString())
        result = result.replace("%w", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
        result = result.replace("%u", (now.dayOfWeek.value).toString())
        result = result.replace("%D", "MM/dd/yyyy")
        result = result.replace("%F", "yyyy/MM/dd")
        result = result.replace("%H", "HH")
        result = result.replace("%I", "hh")
        result = result.replace("%p", "tt")
        result = result.replace("%M", "mm")
        result = result.replace("%S", "ss")
        result = result.replace("%R", "HH:mm")
        result = result.replace("%T", "HH:mm:ss")
        result = result.replace("%r", "hh:mm:ss tt")
        return result
    }

    fun getOriginalFormat(format: String): String {
        var result = format
        result = result.replace("yyyy", "%Y")
        result = result.replace("MMMM", "%B")
        result = result.replace("MMM", "%b")
        result = result.replace("MM", "%m")
        result = result.replace("dddd", "%A")
        result = result.replace("ddd", "%a")
        result = result.replace("dd", "%d")
        result = result.replace("d", "%e")
        result = result.replace("MM/dd/yyyy", "%D")
        result = result.replace("yyyy/MM/dd", "%F")
        result = result.replace("HH:mm:ss", "%T")
        result = result.replace("HH:mm", "%R")
        result = result.replace("hh:mm:ss tt", "%r")
        result = result.replace("HH", "%H")
        result = result.replace("hh", "%I")
        result = result.replace("tt", "%p")
        result = result.replace("mm", "%M")
        result = result.replace("ss", "%S")
        return result
    }
}
