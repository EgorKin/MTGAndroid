package io.github.romanvht.mtgandroid.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.romanvht.mtgandroid.data.START_ACTION
import io.github.romanvht.mtgandroid.data.STOP_ACTION

object ServiceManager {

    private const val TAG = "ServiceManager"

    fun start(context: Context) {
        Log.i(TAG, "Starting proxy")
        val intent = Intent(context, MtgProxyService::class.java)
        intent.action = START_ACTION
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        Log.i(TAG, "Stopping proxy")
        val intent = Intent(context, MtgProxyService::class.java)
        intent.action = STOP_ACTION
        ContextCompat.startForegroundService(context, intent)
    }
}