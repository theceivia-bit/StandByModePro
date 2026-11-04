package com.custom.standbymodepro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                startStandByService(context)
            }
            Intent.ACTION_POWER_CONNECTED -> {
                startStandByService(context)
                if (Settings.canDrawOverlays(context)) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(launchIntent)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val closeIntent = Intent("com.custom.standbymodepro.ACTION_CLOSE_STANDBY").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(closeIntent)
            }
        }
    }

    private fun startStandByService(context: Context) {
        try {
            val serviceIntent = Intent(context, StandByService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }
}
