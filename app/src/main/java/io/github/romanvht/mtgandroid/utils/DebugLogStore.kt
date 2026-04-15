package io.github.romanvht.mtgandroid.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DebugLogStore {
    private const val MAX_LINES = 250
    private val lock = ReentrantLock()
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append("W", tag, buildString {
            append(message)
            throwable?.let { append(" | ${it.javaClass.simpleName}: ${it.message}") }
        })
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, buildString {
            append(message)
            throwable?.let { append(" | ${it.javaClass.simpleName}: ${it.message}") }
        })
    }

    fun export(): String {
        return lock.withLock {
            lines.joinToString("\n")
        }
    }

    fun latestError(): String? {
        return lock.withLock {
            lines.lastOrNull { it.contains(" E/") || it.contains(" W/") }
        }
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = "${formatter.format(Date())} $level/$tag: $message"
        lock.withLock {
            while (lines.size >= MAX_LINES) {
                lines.removeFirst()
            }
            lines.addLast(entry)
        }
    }
}
