package app.orchard.tv.system

import java.time.LocalDate

/**
 * The lunar date beside the clock, for the 农历 month and day.
 *
 * No library: 31 years of dates are baked in, generated from a checked table and verified against
 * the Spring Festival dates of 2024–2027 (10 Feb, 29 Jan, 17 Feb, 6 Feb). Outside 2020–2050 the
 * label is simply left out — a wrong date is worse than none.
 *
 * Each year holds the epoch day of 正月初一, which month is doubled (0 for none), and a bit per
 * month, set when that month has 30 days rather than 29.
 */
object Lunar {
    private const val FIRST_YEAR = 2020
    private const val LAST_YEAR = 2050

    private val NEW_YEAR = longArrayOf(
        18286, 18670, 19024, 19379, 19763, 20117, 20501, 20855, 21209, 21593, 21948,
        22302, 22686, 23041, 23425, 23779, 24133, 24517, 24871, 25225, 25609, 25964,
        26319, 26703, 27057, 27441, 27795, 28149, 28533, 28887, 29242,
    )

    private val LEAP = intArrayOf(
        4, 0, 0, 2, 0, 6, 0, 0, 5, 0, 0, 3, 0, 11, 0, 0, 6, 0, 0, 5, 0, 0, 2, 0, 7, 0, 0, 5, 0, 0, 3,
    )

    private val MONTHS = intArrayOf(
        5422, 1366, 2741, 5554, 1746, 3749, 1829, 1611, 3223, 3243, 1370, 2774, 2921, 5970, 2898,
        2853, 6731, 2635, 1195, 1371, 1453, 2922, 6994, 3474, 7461, 3365, 2645, 5293, 1206, 1461, 3498,
    )

    private val MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )

    private val DIGITS = "一二三四五六七八九"

    data class When(val year: Int, val month: Int, val day: Int, val leap: Boolean) {
        /** "九月十三", "闰六月初一". */
        val label: String
            get() = (if (leap) "闰" else "") + MONTH_NAMES[month - 1] + dayName(day)
    }

    /** The lunar date for [date], or null when it is outside the baked-in range. */
    fun of(date: LocalDate): When? {
        val days = date.toEpochDay()
        if (days < NEW_YEAR.first() || days >= NEW_YEAR.last() + 400) return null
        var i = NEW_YEAR.size - 1
        while (i > 0 && NEW_YEAR[i] > days) i--
        if (days < NEW_YEAR[i]) return null
        // After the last year's 正月 the table cannot tell which month it is.
        if (i == NEW_YEAR.size - 1 && date.year >= LAST_YEAR + 1) return null

        val leap = LEAP[i]
        val bits = MONTHS[i]
        var rest = days - NEW_YEAR[i]
        var index = 0
        var month = 1
        var isLeap = false
        while (true) {
            val len = if ((bits shr index) and 1 == 1) 30 else 29
            if (rest < len) break
            rest -= len
            if (leap != 0 && month == leap && !isLeap) {
                isLeap = true
            } else {
                isLeap = false
                month++
            }
            index++
            if (month > 12) return null
        }
        return When(FIRST_YEAR + i, month, (rest + 1).toInt(), isLeap)
    }

    private fun dayName(d: Int): String = when {
        d == 10 -> "初十"
        d < 10 -> "初" + DIGITS[d - 1]
        d == 20 -> "二十"
        d < 20 -> "十" + DIGITS[d - 11]
        d == 30 -> "三十"
        else -> "廿" + DIGITS[d - 21]
    }
}
