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

    private var tiltDetectorSensor: Sensor? = null
    private var deviceOrientationSensor: Sensor? = null
    private var significantMotionSensor: Sensor? = null
    private var pickUpSensor: Sensor? = null

    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var isAccelerometerActive = false
    private var landscapeEnterTime = 0L
    private var standbyLaunchedInCurrentCycle = false

    // Разрешает автозапуск ТОЛЬКО после выключения экрана (ACTION_SCREEN_OFF).
    // Сбрасывается сразу после закрытия часов при включенном экране или при разблокировке.
    private var isLockedSessionActive = false

    private val releaseActiveWindowRunnable = Runnable {
        if (isMonitoring && hasHardwareWakeSensors()) {
            stopActiveAccelerometerWindow()
        }
    }

    private val sigMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!isMonitoring || isActivityActive || !isLockedSessionActive) return
            if (!isPhoneReallyLocked()) {
                isLockedSessionActive = false
                stopSensorMonitoring()
                return
            }
            significantMotionSensor?.let {
                sensorManager?.requestTriggerSensor(this, it)
            }
            startActiveAccelerometerWindow(30_000L)
        }
    }

    private val pickUpListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!isMonitoring || isActivityActive || !isLockedSessionActive) return
            if (!isPhoneReallyLocked()) {
                isLockedSessionActive = false
                stopSensorMonitoring()
                return
            }
            pickUpSensor?.let {
                sensorManager?.requestTriggerSensor(this, it)
            }
            startActiveAccelerometerWindow(30_000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Экран погас — начинаем новую сессию ожидания поворота
                    isLockedSessionActive = true
                    standbyLaunchedInCurrentCycle = false
                    if (!isActivityActive) {
                        startSensorMonitoring()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    isLockedSessionActive = false
                    standbyLaunchedInCurrentCycle = false
                    stopSensorMonitoring()
                    sendForceCloseToActivity()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (!isPhoneReallyLocked()) {
                        isLockedSessionActive = false
                        stopSensorMonitoring()
                        sendForceCloseToActivity()
                    } else if (isLockedSessionActive && !isActivityActive) {
                        startSensorMonitoring()
                    }
                }
                ACTION_ACTIVITY_RESUMED -> {
                    isActivityActive = true
                    stopSensorMonitoring()
                }
                ACTION_ACTIVITY_PAUSED -> {
                    isActivityActive = false
                    // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ:
                    // Когда пользователь повернул телефон вертикально и закрыл часы, пока экран горит (interactive == true),
                    // мы полностью завершаем сессию до следующего выключения экрана кнопкой блокировки (ACTION_SCREEN_OFF).
                    // Благодаря этому после свайпа разблокировки часы НИКОГДА не откроются поверх YouTube!
                    val isScreenOff = powerManager?.isInteractive == false
                    if (isScreenOff && isLockedSessionActive) {
                        startSensorMonitoring()
                    } else {
                        isLockedSessionActive = false
                        standbyLaunchedInCurrentCycle = false
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

        tiltDetectorSensor = sensorManager?.getDefaultSensor(22, true)
            ?: sensorManager?.getDefaultSensor(22)
        pickUpSensor = sensorManager?.getDefaultSensor(25, true)
            ?: sensorManager?.getDefaultSensor(25)
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

        if (isPhoneReallyLocked()) {
            isLockedSessionActive = true
            if (!isActivityActive) startSensorMonitoring()
        }
    }

    private fun sendForceCloseToActivity() {
        try {
            val closeIntent = Intent("com.custom.standbymodepro.ACTION_CLOSE_STANDBY").apply {
                setPackage(packageName)
            }
            sendBroadcast(closeIntent)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun isPhoneReallyLocked(): Boolean {
        val interactive = powerManager?.isInteractive ?: true
        if (!interactive) return true // Экран полностью погашен

        // Если экран включен, проверяем все системные флаги экрана блокировки
        val km = keyguardManager ?: return false
        return km.isKeyguardLocked || km.isDeviceLocked || km.inKeyguardRestrictedInputMode()
    }

    private fun hasHardwareWakeSensors(): Boolean {
        return tiltDetectorSensor != null || deviceOrientationSensor != null || pickUpSensor != null
    }

    private fun startForegroundWithNotification() {
        val channelId = "standby_aod_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "StandBy AOD Автозапуск",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Отслеживает горизонтальный поворот только на заблокированном экране"
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
            .setContentText("Работает только на заблокированном экране")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        startForeground(101, notification)
    }

    private fun startSensorMonitoring() {
        if (isActivityActive || !isLockedSessionActive || !isPhoneReallyLocked()) {
            stopSensorMonitoring()
            return
        }
        if (!isMonitoring) {
            isMonitoring = true
            landscapeEnterTime = 0L

            tiltDetectorSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            deviceOrientationSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            significantMotionSensor?.let {
                sensorManager?.requestTriggerSensor(sigMotionListener, it)
            }
            pickUpSensor?.let {
                sensorManager?.requestTriggerSensor(pickUpListener, it)
            }
        }

        startActiveAccelerometerWindow(45_000L)
    }

    private fun startActiveAccelerometerWindow(durationMs: Long) {
        if (!isMonitoring || isActivityActive || !isLockedSessionActive || !isPhoneReallyLocked()) {
            stopSensorMonitoring()
            return
        }
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
            wakeLock?.acquire(durationMs + 500L)
            mainHandler.postDelayed(releaseActiveWindowRunnable, durationMs)
        } else {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        }

        if (!isAccelerometerActive) {
            isAccelerometerActive = true
            accelerometer?.let {
                sensorManager?.registerListener(this, it, 250_000, 0)
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
        isMonitoring = false
        stopActiveAccelerometerWindow()
        sensorManager?.unregisterListener(this)
        significantMotionSensor?.let {
            try {
                sensorManager?.cancelTriggerSensor(sigMotionListener, it)
            } catch (_: Exception) {}
        }
        pickUpSensor?.let {
            try {
                sensorManager?.cancelTriggerSensor(pickUpListener, it)
            } catch (_: Exception) {}
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLockedSessionActive || !isPhoneReallyLocked()) {
            isLockedSessionActive = false
            stopSensorMonitoring()
            return
        }

        val sensorType = event?.sensor?.type ?: return

        if (sensorType == 27 && event.values.isNotEmpty()) {
            val orientation = event.values[0].toInt()
            if (orientation == 1 || orientation == 3) {
                if (!standbyLaunchedInCurrentCycle && Settings.canDrawOverlays(this) && isPhoneReallyLocked()) {
                    standbyLaunchedInCurrentCycle = true
                    // Сразу гасим сессию после первого запуска, чтобы повторно без блокировки экрана она не сработала
                    isLockedSessionActive = false
                    stopSensorMonitoring()
                    wakeUpAndLaunchStandBy()
                    return
                }
            } else if (orientation == 0 || orientation == 2) {
                standbyLaunchedInCurrentCycle = false
            }
            startActiveAccelerometerWindow(30_000L)
            return
        }

        if (sensorType == 22) {
            startActiveAccelerometerWindow(30_000L)
            return
        }

        if (sensorType != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val isLandscape = abs(x) > 5.8f && abs(y) < 4.5f && abs(z) < 8.4f && abs(x) > abs(y) * 1.3f
        val isPortrait = abs(y) > 5.2f && abs(y) > abs(x) * 1.15f

        val now = System.currentTimeMillis()

        if (isLandscape) {
            if (landscapeEnterTime == 0L) {
                landscapeEnterTime = now
            } else if (now - landscapeEnterTime >= 250L && !standbyLaunchedInCurrentCycle) {
                if (Settings.canDrawOverlays(this) && isLockedSessionActive && isPhoneReallyLocked()) {
                    standbyLaunchedInCurrentCycle = true
                    // Сразу сбрасываем сессию: 1 блокировка экрана = максимум 1 автозапуск
                    isLockedSessionActive = false
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
        if (!isPhoneReallyLocked()) return

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
