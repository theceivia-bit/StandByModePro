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
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.math.abs

class StandByService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    // Энергоэффективные аппаратные датчики Sensor Hub (работают в Deep Sleep без WakeLock)
    private var tiltDetectorSensor: Sensor? = null
    private var deviceOrientationSensor: Sensor? = null
    private var significantMotionSensor: Sensor? = null

    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var isAccelerometerActive = false
    private var landscapeEnterTime = 0L
    private var standbyLaunchedInCurrentCycle = false

    // Переводит сервис из активной проверки акселерометра в глубокий сон (Deep Sleep)
    private val releaseActiveWindowRunnable = Runnable {
        if (isMonitoring && hasHardwareWakeSensors()) {
            stopActiveAccelerometerWindow()
        }
    }

    private val sigMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!isMonitoring || isActivityActive) return
            // Перевооружаем триггер движения и включаем короткое окно проверки ориентации (8 сек)
            significantMotionSensor?.let {
                sensorManager?.requestTriggerSensor(this, it)
            }
            startActiveAccelerometerWindow(8_000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    standbyLaunchedInCurrentCycle = false
                    if (!isActivityActive) startSensorMonitoring()
                }
                Intent.ACTION_USER_PRESENT -> {
                    stopSensorMonitoring()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (keyguardManager?.isKeyguardLocked == true && !isActivityActive) {
                        startSensorMonitoring()
                    }
                }
                ACTION_ACTIVITY_RESUMED -> {
                    isActivityActive = true
                    stopSensorMonitoring()
                }
                ACTION_ACTIVITY_PAUSED -> {
                    isActivityActive = false
                    if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
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

        // Ищем аппаратные wake-up датчики наклона/поворота на сопроцессоре Sensor Hub:
        // TYPE_TILT_DETECTOR = 22, TYPE_DEVICE_ORIENTATION = 27
        tiltDetectorSensor = sensorManager?.getDefaultSensor(22, true)
            ?: sensorManager?.getDefaultSensor(22)
        deviceOrientationSensor = sensorManager?.getDefaultSensor(27, true)
            ?: sensorManager?.getDefaultSensor(27)
        significantMotionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        startForegroundWithNotification()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_ACTIVITY_RESUMED)
            addAction(ACTION_ACTIVITY_PAUSED)
        }
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if ((powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) && !isActivityActive) {
            startSensorMonitoring()
        }
    }

    private fun hasHardwareWakeSensors(): Boolean {
        return tiltDetectorSensor != null || deviceOrientationSensor != null
    }

    private fun startForegroundWithNotification() {
        val channelId = "standby_aod_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "StandBy AOD Автозапуск",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Отслеживает горизонтальный поворот в режиме блокировки"
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
            .setContentText("Поверните заблокированный телефон горизонтально")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        startForeground(101, notification)
    }

    private fun startSensorMonitoring() {
        if (isActivityActive) return
        if (!isMonitoring) {
            isMonitoring = true
            landscapeEnterTime = 0L

            // Регистрируем низкопотребляющие триггеры Sensor Hub (работают без PARTIAL_WAKE_LOCK)
            tiltDetectorSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            deviceOrientationSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            significantMotionSensor?.let {
                sensorManager?.requestTriggerSensor(sigMotionListener, it)
            }
        }

        // Включаем активное окно акселерометра на 15 секунд сразу после блокировки экрана
        startActiveAccelerometerWindow(15_000L)
    }

    private fun startActiveAccelerometerWindow(durationMs: Long) {
        if (!isMonitoring || isActivityActive) return
        mainHandler.removeCallbacks(releaseActiveWindowRunnable)

        if (wakeLock == null) {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StandByModePro::AodSensorLock"
            )?.apply {
                setReferenceCounted(false)
            }
        }

        if (hasHardwareWakeSensors()) {
            // Держим WakeLock ровно на время короткого окна проверки (8–15 сек), после чего CPU уходит в Deep Sleep
            wakeLock?.acquire(durationMs + 500L)
            mainHandler.postDelayed(releaseActiveWindowRunnable, durationMs)
        } else {
            // Резервный режим только для редких устройств без аппаратного датчика наклона
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        }

        if (!isAccelerometerActive) {
            isAccelerometerActive = true
            accelerometer?.let {
                sensorManager?.registerListener(this, it, 600_000, 1_200_000)
            }
        }
    }

    private fun stopActiveAccelerometerWindow() {
        mainHandler.removeCallbacks(releaseActiveWindowRunnable)
        if (isAccelerometerActive) {
            isAccelerometerActive = false
            accelerometer?.let { sensorManager?.unregisterListener(this, it) }
        }
        landscapeEnterTime = 0L
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (_: Exception) {}
        }
    }

    private fun stopSensorMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        stopActiveAccelerometerWindow()
        sensorManager?.unregisterListener(this)
        significantMotionSensor?.let {
            try {
                sensorManager?.cancelTriggerSensor(sigMotionListener, it)
            } catch (_: Exception) {}
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorType = event?.sensor?.type ?: return

        // Сработал аппаратный датчик наклона (22) или смены ориентации (27) из глубокого сна
        if (sensorType == 22 || sensorType == 27) {
            startActiveAccelerometerWindow(8_000L)
            return
        }

        if (sensorType != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val isLandscape = abs(x) > 7.0f && abs(y) < 4.0f && abs(z) < 7.5f
        val isPortrait = abs(y) > 6.5f && abs(x) < 4.5f

        val now = System.currentTimeMillis()

        if (isLandscape) {
            if (landscapeEnterTime == 0L) {
                landscapeEnterTime = now
            } else if (now - landscapeEnterTime > 600L && !standbyLaunchedInCurrentCycle) {
                if (Settings.canDrawOverlays(this)) {
                    standbyLaunchedInCurrentCycle = true
                    stopSensorMonitoring()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        stopSensorMonitoring()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ACTIVITY_RESUMED = "com.custom.standbymodepro.ACTIVITY_RESUMED"
        const val ACTION_ACTIVITY_PAUSED = "com.custom.standbymodepro.ACTIVITY_PAUSED"
        @Volatile
        var isActivityActive = false
    }
}
