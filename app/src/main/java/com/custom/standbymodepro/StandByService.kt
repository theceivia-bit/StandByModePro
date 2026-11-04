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
    private var lastSensorEventTime = 0L

    // Более редкий опрос акселерометра (≈ 2 раза в секунду вместо 5)
    private val SENSOR_SAMPLING_US = 500_000  // 500 мс

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
                    } else {
                        stopSensorMonitoring()
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

        // Запускаем мониторинг только если экран выключен или заблокирован
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
        lastSensorEventTime = System.currentTimeMillis()

        // Короткий WakeLock (40 минут). Если мониторинг всё ещё нужен — перезапросим.
        if (wakeLock == null) {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StandByModePro::AodSensorLock"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(40 * 60 * 1000L) // 40 минут вместо 12 часов
        }

        accelerometer?.let {
            sensorManager?.registerListener(this, it, SENSOR_SAMPLING_US)
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

        val now = System.currentTimeMillis()
        // Дополнительная защита от слишком частых событий
        if (now - lastSensorEventTime < 400) return
        lastSensorEventTime = now

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Чуть мягче пороги — лучше работает в подставках
        val isLandscape = abs(x) > 6.2f && abs(y) < 4.8f && abs(z) < 8.0f
        val isPortrait = abs(y) > 6.5f && abs(x) < 4.5f

        if (isLandscape) {
            if (landscapeEnterTime == 0L) {
                landscapeEnterTime = now
            } else if (now - landscapeEnterTime > 800L && !standbyLaunchedInCurrentCycle) {
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
            screenWake?.acquire(2500L)

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
