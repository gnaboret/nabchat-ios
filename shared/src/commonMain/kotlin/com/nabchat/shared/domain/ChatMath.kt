package com.nabchat.shared.domain

enum class RugRateTrend(val symbol: String) { UP("↑"), STEADY("→"), DOWN("↓") }

data class RugActivity(val messagesPerMinute: Int, val trend: RugRateTrend)

fun adaptiveMessageDelayMillis(pendingCount: Int): Long = when {
    pendingCount > 50 -> 28L
    pendingCount > 20 -> 48L
    pendingCount > 8 -> 85L
    pendingCount > 3 -> 125L
    else -> 180L
}

fun rugMessageRatePerMinute(timestamps: List<Long>, nowMillis: Long): Float {
    val recent = timestamps.filter { nowMillis - it in 0..120_000L }.sortedDescending().take(30)
    return when {
        recent.size >= 2 -> ((recent.size - 1) * 60_000f / (recent.first() - recent.last()).coerceAtLeast(1_000L))
        recent.size == 1 -> 4f
        else -> 1.5f
    }
}

fun rugActivity(timestamps: List<Long>, nowMillis: Long): RugActivity {
    val current = timestamps.count { nowMillis - it in 0 until 60_000L }
    val previous = timestamps.count { nowMillis - it in 60_000L until 120_000L }
    val meaningfulChange = maxOf(2, (previous * .15f).toInt())
    val trend = when {
        current >= previous + meaningfulChange -> RugRateTrend.UP
        previous >= current + meaningfulChange -> RugRateTrend.DOWN
        else -> RugRateTrend.STEADY
    }
    return RugActivity(current, trend)
}
