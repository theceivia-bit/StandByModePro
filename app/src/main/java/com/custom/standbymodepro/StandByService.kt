package com.custom.standbymodepro

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.abs

class StandByService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isMonitoring = false
    private var landscapeEnterTime = 0L
    private var standbyLaunchedInCurrentCycle = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    standbyLaunchedInCurrentCycle = false
                    startSensorMonitoring()
                }
                Intent.ACTION_USER_PRESENT -> {
                    stopSensorMonitoring()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (keyguardManager?.isKeyguardLocked == true) {
                        startSensorMonitoring()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        startForegroundWithNotification()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            startSensorMonitoring()
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "standby_aod_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "StandBy AOD Автозапуск",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Отслеживает горизонтальный поворот в режиме AOD"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("StandBy Mode Pro активен")
            .setContentText("Поверните выключенный телефон горизонтально для запуска")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        startForeground(101, notification)
    }

    private fun startSensorMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        landscapeEnterTime = 0L

        if (wakeLock == null) {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StandByModePro::AodSensorLock"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        }

        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopSensorMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        sensorManager?.unregisterListener(this)
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (_: Exception) {}
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val isLandscape = abs(x) > 7.0f && abs(y) < 4.0f && abs(z) < 7.5f
        val isPortrait = abs(y) > 6.5f && abs(x) < 4.5f

        val now = System.currentTimeMillis()

        if (isLandscape) {
            if (landscapeEnterTime == 0L) {
                landscapeEnterTime = now
            } else if (now - landscapeEnterTime > 900L && !standbyLaunchedInCurrentCycle) {
                if (Settings.canDrawOverlays(this)) {
                    standbyLaunchedInCurrentCycle = true
                    wakeUpAndLaunchStandBy()
                }
            }
        } else {
            landscapeEnterTime = 0L
            if (isPortrait) {
                standbyLaunchedInCurrentCycle = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpAndLaunchStandBy() {
        try {
            val screenWake = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "StandByModePro::WakeUpScreen"
            )
            screenWake?.acquire(3000L)

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(launchIntent)
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensorMonitoring()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
