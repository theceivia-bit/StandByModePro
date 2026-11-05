package com.custom.standbymodepro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BATTERY_CHANGED
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var currentLux by mutableFloatStateOf(100f)

    private var portraitEnterTime = 0L

    // Принудительно закрывает окно и полностью выгружает задачу из списка недавних (Recents)
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            closeAndRemoveFromRecents()
        }
    }

    private fun closeAndRemoveFromRecents() {
        try {
            finishAndRemoveTask()
        } catch (_: Exception) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)

        try {
            val serviceIntent = Intent(this, StandByService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Слушаем как команду закрытия от сервиса, так и системное событие разблокировки смартфона
        try {
            val filter = IntentFilter("com.custom.standbymodepro.ACTION_CLOSE_STANDBY").apply {
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this,
                closeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000))
            ) {
                StandByScreen(
                    ambientLux = currentLux,
                    onPollLightSensor = { pollLightSensorOnce() },
                    onRequestOverlayPermission = {
                        try {
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        } catch (_: Exception) {}
                    }
                )
            }
        }

        window.decorView.post {
            hideSystemUI()
        }
    }

    private suspend fun pollLightSensorOnce() {
        val sm = sensorManager ?: return
        val ls = lightSensor ?: return
        val sampledLux = withTimeoutOrNull(1500L) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event != null && event.values.isNotEmpty()) {
                            sm.unregisterListener(this)
                            if (cont.isActive) cont.resume(event.values[0])
                        }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, ls, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
        if (sampledLux != null) {
            val crossedNightThreshold = (sampledLux < 8f) != (currentLux < 8f)
            if (crossedNightThreshold || abs(sampledLux - currentLux) >= 5f) {
                currentLux = sampledLux
            }
        }
    }

    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        portraitEnterTime = 0L
        StandByService.isActivityActive = true
        sendBroadcast(Intent(StandByService.ACTION_ACTIVITY_RESUMED).setPackage(packageName))
        window.decorView.post { hideSystemUI() }

        // Экран уже включен, поэтому используем SENSOR_DELAY_UI без буфера задержки —
        // расход энергии не меняется, а реакция на поворот в портрет становится мгновенной
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        StandByService.isActivityActive = false
        sendBroadcast(Intent(StandByService.ACTION_ACTIVITY_PAUSED).setPackage(packageName))
    }

    override fun onStop() {
        super.onStop()
        // Если часы свернулись в фон или пользователь вышел на рабочий стол —
        // немедленно уничтожаем окно, чтобы оно не висело в памяти и не всплывало при поворотах
        closeAndRemoveFromRecents()
    }

    override fun onDestroy() {
        super.onDestroy()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]

            // Более мягкий порог портретного режима (срабатывает даже при наклоне телефона в руке)
            val isPortrait = abs(y) > 5.2f && abs(y) > abs(x) * 1.15f
            val now = System.currentTimeMillis()

            if (isPortrait) {
                if (portraitEnterTime == 0L) {
                    portraitEnterTime = now
                } else if (now - portraitEnterTime > 180L) {
                    closeAndRemoveFromRecents()
                }
            } else {
                portraitEnterTime = 0L
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

enum class DigitalClockStyle(val title: String) {
    FLIP("Контурные"),
    DUO("Крупные"),
    STACKED("2-этажные"),
    TERMINAL("Терминал")
}

// Единая ротация правой панели: 2 календаря и 2 виджета погоды чередуют друг друга.
// Telegram сюда НЕ входит и никогда не участвует в авто-ротации.
enum class RightPanelMode(val title: String) {
    CALENDAR_TOP("Календарь 1"),
    WEATHER_CURRENT("Погода: Сейчас"),
    CALENDAR_BOTTOM("Календарь 2"),
    WEATHER_FORECAST("Погода: Прогноз")
}

enum class BrightnessMode(val title: String) {
    AUTO("АВТО (5%/10%)"),
    DAY_10("ДЕНЬ (10%)"),
    NIGHT_5("НОЧЬ (5%)")
}

data class ColorTheme(
    val name: String,
    val primary: Color,
    val accent: Color
)

val colorThemes = listOf(
    ColorTheme("Белый", Color(0xFFF5F5F7), Color(0xFF32D74B)),
    ColorTheme("Изумруд", Color(0xFF32D74B), Color(0xFFA8FFB5)),
    ColorTheme("Голубой", Color(0xFF5AC8FA), Color(0xFF0A84FF)),
    ColorTheme("Янтарь", Color(0xFFFF9F0A), Color(0xFFFFD60A)),
    ColorTheme("Пурпур", Color(0xFFBF5AF2), Color(0xFFFF375F)),
    ColorTheme("Коралл", Color(0xFFFF453A), Color(0xFFFF9F0A))
)

data class DayForecast(
    val dayName: String,
    val dateShort: String,
    val maxTemp: Int,
    val minTemp: Int,
    val weatherCode: Int
)

data class WeatherData(
    val temp: Int,
    val feelsLike: Int,
    val weatherCode: Int,
    val humidity: Int,
    val windSpeed: Int,
    val pressureMmHg: Int,
    val daily: List<DayForecast>,
    val updatedTime: String
)

private const val PREFS_NAME = "standby_mode_prefs"

@Composable
fun StandByScreen(
    ambientLux: Float,
    onPollLightSensor: suspend () -> Unit,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }

    var clockStyle by remember {
        val saved = prefs.getInt("clock_style", 0).coerceIn(0, DigitalClockStyle.entries.lastIndex)
        mutableStateOf(DigitalClockStyle.entries[saved])
    }
    var rightPanelMode by remember {
        val saved = prefs.getInt("right_panel_mode", 0).coerceIn(0, RightPanelMode.entries.lastIndex)
        mutableStateOf(RightPanelMode.entries[saved])
    }
    var swipeDirection by remember { mutableIntStateOf(1) }

    // Независимое состояние окна Telegram (открывается и закрывается только свайпом влево/вправо)
    var isTelegramOpen by remember { mutableStateOf(false) }
    var horizontalSwipeDir by remember { mutableIntStateOf(1) }

    var autoCycleOled by remember {
        mutableStateOf(prefs.getBoolean("auto_cycle_oled", true))
    }
    var brightnessMode by remember {
        val saved = prefs.getInt("brightness_mode", 0).coerceIn(0, BrightnessMode.entries.lastIndex)
        mutableStateOf(BrightnessMode.entries[saved])
    }
    var themeIndex by remember {
        mutableIntStateOf(prefs.getInt("theme_index", 0).coerceIn(0, colorThemes.lastIndex))
    }
    var showControls by remember { mutableStateOf(false) }
    var showFps by remember { mutableStateOf(prefs.getBoolean("show_fps", false)) }

    var weatherData by remember {
        mutableStateOf(
            prefs.getString("cached_weather_json", null)?.let { parseWeatherJson(it) }
        )
    }
    var isWeatherLoading by remember { mutableStateOf(false) }

    var isAnimating by remember { mutableStateOf(false) }

    // Фоновое обновление погоды для г. Павлово (Нижегородская обл.) раз в 30 минут
    LaunchedEffect(Unit) {
        while (true) {
            isWeatherLoading = true
            val rawJson = fetchPavlovoWeatherRawJson()
            if (rawJson != null) {
                prefs.edit().putString("cached_weather_json", rawJson).apply()
                parseWeatherJson(rawJson)?.let { weatherData = it }
            }
            isWeatherLoading = false
            delay(30 * 60_000L)
        }
    }

    val shouldPollLight = brightnessMode == BrightnessMode.AUTO
    LaunchedEffect(shouldPollLight) {
        if (shouldPollLight) {
            while (true) {
                onPollLightSensor()
                delay(30_000L)
            }
        }
    }

    // Повышаем частоту обновления только во время короткой анимации ИЛИ когда открыт Telegram.
    // Когда Telegram закрыт и анимация завершена — возвращаем 1 FPS для экономии АКБ.
    LaunchedEffect(isAnimating, isTelegramOpen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val params = window.attributes
        val needActiveRefresh = isAnimating || isTelegramOpen

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val modes = activity.display?.supportedModes
            if (!modes.isNullOrEmpty()) {
                val targetMode = if (needActiveRefresh) {
                    modes.filter { it.refreshRate <= 60.1f }.maxByOrNull { it.refreshRate }
                        ?: modes.minByOrNull { it.refreshRate }
                } else {
                    modes.minByOrNull { it.refreshRate }
                }
                if (targetMode != null && params.preferredDisplayModeId != targetMode.modeId) {
                    params.preferredDisplayModeId = targetMode.modeId
                }
            }
        }
        params.preferredRefreshRate = if (isTelegramOpen) 60f else if (isAnimating) 30f else 1f
        window.attributes = params
    }

    LaunchedEffect(clockStyle, rightPanelMode, themeIndex, isTelegramOpen) {
        isAnimating = true
        delay(700L)
        isAnimating = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                prefs.edit()
                    .putInt("clock_style", clockStyle.ordinal)
                    .putInt("right_panel_mode", rightPanelMode.ordinal)
                    .putBoolean("auto_cycle_oled", autoCycleOled)
                    .putInt("brightness_mode", brightnessMode.ordinal)
                    .putInt("theme_index", themeIndex)
                    .putBoolean("show_fps", showFps)
                    .apply()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var burnInOffsetX by remember { mutableIntStateOf(0) }
    var burnInOffsetY by remember { mutableIntStateOf(0) }

    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    val isNight = when (brightnessMode) {
        BrightnessMode.AUTO -> ambientLux < 8f
        BrightnessMode.DAY_10 -> false
        BrightnessMode.NIGHT_5 -> true
    }

    val targetBrightness = if (isNight) 0.05f else 0.10f
    LaunchedEffect(targetBrightness) {
        (context as? Activity)?.window?.let { win ->
            val params = win.attributes
            if (params.screenBrightness != targetBrightness) {
                params.screenBrightness = targetBrightness
                win.attributes = params
            }
        }
    }

    val currentTheme = colorThemes[themeIndex]
    val primaryColor by animateColorAsState(
        targetValue = currentTheme.primary,
        animationSpec = tween(500),
        label = "primaryColor"
    )
    val accentColor by animateColorAsState(
        targetValue = currentTheme.accent,
        animationSpec = tween(500),
        label = "accentColor"
    )
    LaunchedEffect(autoCycleOled) {
        var minutesCounter = 0
        while (true) {
            currentTime = LocalTime.now()
            currentDate = LocalDate.now()

            if (minutesCounter > 0) {
                if (minutesCounter % 3 == 0) {
                    burnInOffsetX = Random.nextInt(-18, 19)
                    burnInOffsetY = Random.nextInt(-14, 15)
                }

                if (autoCycleOled && minutesCounter % 5 == 0) {
                    val otherClocks = DigitalClockStyle.entries.filter { it != clockStyle }
                    clockStyle = otherClocks.random()

                    // Ротация правой панели крутит только календари и погоду
                    swipeDirection = 1
                    rightPanelMode = RightPanelMode.entries[
                        (rightPanelMode.ordinal + 1) % RightPanelMode.entries.size
                    ]
                }
            }
            minutesCounter++

            try {
                val bm = context.registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))
                val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    batteryPct = (level * 100) / scale
                }
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) {}

            val sleepMs = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L
            delay(sleepMs)
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(6000L)
            showControls = false
        }
    }

    var verticalDragSum by remember { mutableFloatStateOf(0f) }
    var verticalSwipeHandled by remember { mutableStateOf(false) }

    var horizontalDragSum by remember { mutableFloatStateOf(0f) }
    var horizontalSwipeHandled by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            // Вертикальный свайп переключает календари/погоду только когда Telegram закрыт
            .pointerInput(isTelegramOpen) {
                if (!isTelegramOpen) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            verticalDragSum = 0f
                            verticalSwipeHandled = false
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (!verticalSwipeHandled) {
                                verticalDragSum += dragAmount
                                if (verticalDragSum < -55f) {
                                    verticalSwipeHandled = true
                                    swipeDirection = 1
                                    rightPanelMode = RightPanelMode.entries[
                                        (rightPanelMode.ordinal + 1) % RightPanelMode.entries.size
                                    ]
                                } else if (verticalDragSum > 55f) {
                                    verticalSwipeHandled = true
                                    swipeDirection = -1
                                    val size = RightPanelMode.entries.size
                                    rightPanelMode = RightPanelMode.entries[
                                        (rightPanelMode.ordinal - 1 + size) % size
                                    ]
                                }
                            }
                        }
                    )
                }
            }
            // Горизонтальный свайп влево/вправо открывает Telegram и возвращает ротацию обратно
            .pointerInput(isTelegramOpen) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        horizontalDragSum = 0f
                        horizontalSwipeHandled = false
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (!horizontalSwipeHandled) {
                            horizontalDragSum += dragAmount
                            if (horizontalDragSum < -65f) {
                                horizontalSwipeHandled = true
                                horizontalSwipeDir = 1
                                isTelegramOpen = !isTelegramOpen
                            } else if (horizontalDragSum > 65f) {
                                horizontalSwipeHandled = true
                                horizontalSwipeDir = -1
                                isTelegramOpen = !isTelegramOpen
                            }
                        }
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Когда открыт Telegram, отключаем сдвиг для четкости текста в мессенджере
                    translationX = if (isTelegramOpen) 0f else burnInOffsetX.toFloat()
                    translationY = if (isTelegramOpen) 0f else burnInOffsetY.toFloat()
                },
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClockContainer(
                clockStyle = clockStyle,
                currentTime = currentTime,
                primaryColor = primaryColor,
                accentColor = accentColor,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )
            WidgetContainer(
                rightPanelMode = rightPanelMode,
                swipeDirection = swipeDirection,
                isTelegramOpen = isTelegramOpen,
                horizontalSwipeDir = horizontalSwipeDir,
                onSwipeToggleTelegram = { dir ->
                    horizontalSwipeDir = dir
                    isTelegramOpen = !isTelegramOpen
                },
                currentDate = currentDate,
                batteryPct = batteryPct,
                isCharging = isCharging,
                weatherData = weatherData,
                isWeatherLoading = isWeatherLoading,
                onRefreshWeather = {
                    if (!isWeatherLoading) {
                        coroutineScope.launch {
                            isWeatherLoading = true
                            val rawJson = fetchPavlovoWeatherRawJson()
                            if (rawJson != null) {
                                prefs.edit().putString("cached_weather_json", rawJson).apply()
                                parseWeatherJson(rawJson)?.let { weatherData = it }
                            }
                            isWeatherLoading = false
                        }
                    }
                },
                primaryColor = primaryColor,
                accentColor = accentColor,
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            )
        }

        if (showFps) {
            FpsMeter(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }

        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF141416))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    clockStyle = DigitalClockStyle.entries[
                        (clockStyle.ordinal + 1) % DigitalClockStyle.entries.size
                    ]
                }) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text("Часы: ${clockStyle.title}", color = Color.White, fontSize = 12.sp)
                }

                TextButton(onClick = {
                    if (isTelegramOpen) {
                        isTelegramOpen = false
                    } else {
                        swipeDirection = 1
                        rightPanelMode = RightPanelMode.entries[
                            (rightPanelMode.ordinal + 1) % RightPanelMode.entries.size
                        ]
                    }
                }) {
                    Icon(Icons.Default.Widgets, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isTelegramOpen) "Панель: Telegram" else "Панель: ${rightPanelMode.title}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                TextButton(onClick = { autoCycleOled = !autoCycleOled }) {
                    Icon(
                        Icons.Default.Autorenew,
                        contentDescription = null,
                        tint = if (autoCycleOled) accentColor else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (autoCycleOled) "Ротация: ВКЛ" else "Ротация: ВЫКЛ",
                        color = if (autoCycleOled) accentColor else Color.LightGray,
                        fontSize = 12.sp
                    )
                }

                TextButton(onClick = {
                    themeIndex = (themeIndex + 1) % colorThemes.size
                }) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = primaryColor)
                    Spacer(Modifier.width(5.dp))
                    Text("Цвет: ${currentTheme.name}", color = primaryColor, fontSize = 12.sp)
                }

                TextButton(onClick = {
                    brightnessMode = BrightnessMode.entries[
                        (brightnessMode.ordinal + 1) % BrightnessMode.entries.size
                    ]
                }) {
                    Icon(Icons.Default.Brightness6, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${brightnessMode.title} [${(targetBrightness * 100).toInt()}%]",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                TextButton(onClick = { showFps = !showFps }) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        tint = if (showFps) Color(0xFF32D74B) else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (showFps) "FPS: ВКЛ" else "FPS: ВЫКЛ",
                        color = if (showFps) Color(0xFF32D74B) else Color.LightGray,
                        fontSize = 12.sp
                    )
                }

                if (!Settings.canDrawOverlays(context)) {
                    TextButton(onClick = onRequestOverlayPermission) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFFFD60A))
                        Spacer(Modifier.width(4.dp))
                        Text("Автозапуск", color = Color(0xFFFFD60A), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Пассивный измеритель FPS через OnFrameMetricsAvailableListener.
 */
@Composable
fun FpsMeter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var fps by remember { mutableIntStateOf(0) }
    val renderedFrames = remember { AtomicInteger(0) }

    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val handler = Handler(Looper.getMainLooper())
        val listener = Window.OnFrameMetricsAvailableListener { _, _, _ ->
            renderedFrames.incrementAndGet()
        }
        if (window != null) {
            try {
                window.addOnFrameMetricsAvailableListener(listener, handler)
            } catch (_: Exception) {}
        }
        onDispose {
            if (window != null) {
                try {
                    window.removeOnFrameMetricsAvailableListener(listener)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            val count = renderedFrames.getAndSet(0)
            val actualFps = if (count <= 1) 1 else count
            if (fps != actualFps) {
                fps = actualFps
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xAA000000))
            .border(1.dp, Color(0xFF32D74B).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$fps FPS",
            color = Color(0xFF32D74B),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ClockContainer(
    clockStyle: DigitalClockStyle,
    currentTime: LocalTime,
    primaryColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = clockStyle,
            transitionSpec = {
                fadeIn(animationSpec = tween(600)) togetherWith
                        fadeOut(animationSpec = tween(600))
            },
            label = "ClockStyleCrossfade"
        ) { targetStyle ->
            when (targetStyle) {
                DigitalClockStyle.FLIP -> FlipClockView(time = currentTime, primaryColor = primaryColor, accentColor = accentColor)
                DigitalClockStyle.DUO -> DigitalDuoClockView(time = currentTime, primaryColor = primaryColor, accentColor = accentColor)
                DigitalClockStyle.STACKED -> StackedThinClockView(time = currentTime, primaryColor = primaryColor, accentColor = accentColor)
                DigitalClockStyle.TERMINAL -> TerminalClockView(time = currentTime, primaryColor = primaryColor, accentColor = accentColor)
            }
        }
    }
}

@Composable
fun WidgetContainer(
    rightPanelMode: RightPanelMode,
    swipeDirection: Int,
    isTelegramOpen: Boolean,
    horizontalSwipeDir: Int,
    onSwipeToggleTelegram: (Int) -> Unit,
    currentDate: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    weatherData: WeatherData?,
    isWeatherLoading: Boolean,
    onRefreshWeather: () -> Unit,
    primaryColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(if (isTelegramOpen) 4.dp else 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Горизонтальное переключение между ротационными виджетами и окном Telegram
        AnimatedContent(
            targetState = isTelegramOpen,
            transitionSpec = {
                val hDir = if (horizontalSwipeDir >= 0) 1 else -1
                (slideInHorizontally(animationSpec = tween(450)) { width -> hDir * width } +
                        fadeIn(animationSpec = tween(450))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(450)) { width -> -hDir * width } +
                                fadeOut(animationSpec = tween(450)))
            },
            label = "TelegramHorizontalSwitch"
        ) { telegramActive ->
            if (telegramActive) {
                TelegramWebPanel(
                    primaryColor = primaryColor,
                    accentColor = accentColor,
                    onSwipeBack = { dir -> onSwipeToggleTelegram(dir) }
                )
            } else {
                // Стандартная вертикальная ротация календарей и погоды
                AnimatedContent(
                    targetState = rightPanelMode,
                    transitionSpec = {
                        val dir = if (swipeDirection >= 0) 1 else -1
                        (slideInVertically(animationSpec = tween(550)) { height -> dir * height / 3 } +
                                fadeIn(animationSpec = tween(550))) togetherWith
                                (slideOutVertically(animationSpec = tween(550)) { height -> -dir * height / 3 } +
                                        fadeOut(animationSpec = tween(550)))
                    },
                    label = "RightPanelCrossfade"
                ) { mode ->
                    when (mode) {
                        RightPanelMode.CALENDAR_TOP -> CalendarLayoutTopView(
                            date = currentDate,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                        RightPanelMode.WEATHER_CURRENT -> WeatherCurrentDetailedView(
                            weather = weatherData,
                            isLoading = isWeatherLoading,
                            onRefresh = onRefreshWeather,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                        RightPanelMode.CALENDAR_BOTTOM -> CalendarLayoutBottomView(
                            date = currentDate,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                        RightPanelMode.WEATHER_FORECAST -> WeatherForecastGridView(
                            weather = weatherData,
                            isLoading = isWeatherLoading,
                            onRefresh = onRefreshWeather,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Окно Telegram с нулевым расходом АКБ в закрытом состоянии.
 * Существует в памяти только тогда, когда открыто пользователем.
 * При свайпе обратно к календарям/погоде полностью останавливает таймеры, сеть и рендер.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TelegramWebPanel(
    primaryColor: Color,
    accentColor: Color,
    onSwipeBack: (Int) -> Unit
) {
    var headerDragSum by remember { mutableFloatStateOf(0f) }
    var headerSwipeHandled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
            .background(Color(0xFF000000))
    ) {
        // Верхняя полоса управления: поддерживает свайп влево/вправо для возврата к календарям/погоде
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121214))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            headerDragSum = 0f
                            headerSwipeHandled = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (!headerSwipeHandled) {
                                headerDragSum += dragAmount
                                if (headerDragSum < -50f) {
                                    headerSwipeHandled = true
                                    onSwipeBack(1)
                                } else if (headerDragSum > 50f) {
                                    headerSwipeHandled = true
                                    onSwipeBack(-1)
                                }
                            }
                        }
                    )
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "TELEGRAM • СВАЙП ВЛЕВО/ВПРАВО",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = "К ВИДЖЕТАМ ✕",
                color = primaryColor.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onSwipeBack(-1) }
            )
        }

AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true // Сохраняет сессию авторизации на диске
                        databaseEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = true
                    }
                    
                    // Исправление: сначала включаем глобальные куки, а затем разрешаем сторонние для WebView
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = WebViewClient()
                    onResume()
                    resumeTimers()
                    loadUrl("https://web.telegram.org/k/")
                }
            },
            onRelease = { webView ->
                // Гарантирует 0% расхода батареи и сети после закрытия окна Telegram
                try {
                    CookieManager.getInstance().flush()
                    webView.stopLoading()
                    webView.onPause()
                    webView.pauseTimers()
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Exception) {}
            }
        )
    }
}
@Composable
fun CalendarLayoutTopView(
    date: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = remember { Locale("ru", "RU") }
    val dayOfWeek = remember(date) { date.dayOfWeek.getDisplayName(TextStyle.FULL, ruLocale).uppercase(ruLocale) }
    val monthName = remember(date) { date.month.getDisplayName(TextStyle.FULL_STANDALONE, ruLocale).uppercase(ruLocale) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(text = dayOfWeek, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(text = "${date.dayOfMonth} $monthName", color = primaryColor, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            BatteryBadge(batteryPct, isCharging, primaryColor, accentColor)
        }

        MonthGridWidget(date, primaryColor, accentColor)
    }
}

@Composable
fun CalendarLayoutBottomView(
    date: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = remember { Locale("ru", "RU") }
    val dayOfWeek = remember(date) { date.dayOfWeek.getDisplayName(TextStyle.FULL, ruLocale).uppercase(ruLocale) }
    val monthName = remember(date) { date.month.getDisplayName(TextStyle.FULL_STANDALONE, ruLocale).uppercase(ruLocale) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            BatteryBadge(batteryPct, isCharging, primaryColor, accentColor)
        }

        MonthGridWidget(date, primaryColor, accentColor)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(text = "${date.dayOfMonth} $monthName", color = primaryColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(text = dayOfWeek, color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
fun MonthGridWidget(
    date: LocalDate,
    primaryColor: Color,
    accentColor: Color
) {
    val yearMonth = remember(date.year, date.monthValue) { YearMonth.from(date) }
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOffset = yearMonth.atDay(1).dayOfWeek.value - 1

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val weekDays = remember { listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС") }
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDays.forEach { d ->
                Text(
                    text = d,
                    color = primaryColor.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val totalCells = firstDayOffset + daysInMonth
        val rows = (totalCells + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val cellIndex = r * 7 + c
                    val dayNum = cellIndex - firstDayOffset + 1
                    if (dayNum in 1..daysInMonth) {
                        val isToday = dayNum == date.dayOfMonth
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .then(
                                    if (isToday) {
                                        Modifier.border(1.5.dp, accentColor, CircleShape)
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayNum.toString(),
                                color = if (isToday) accentColor else primaryColor.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Light
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Виджет погоды №1: Крупная текущая погода в г. Павлово с подробными параметрами
 */
@Composable
fun WeatherCurrentDetailedView(
    weather: WeatherData?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.clickable { onRefresh() }
            ) {
                Text(
                    text = "ПАВЛОВО",
                    color = accentColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = if (isLoading && weather == null) "Загрузка..." else weatherConditionText(weather?.weatherCode ?: 0),
                    color = primaryColor.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            BatteryBadge(batteryPct, isCharging, primaryColor, accentColor)
        }

        if (weather == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoading) "Обновление погоды..." else "Нет данных • Нажмите для повтора",
                    color = primaryColor.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = weatherConditionIcon(weather.weatherCode),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(60.dp)
                    )
                    Text(
                        text = formatTemp(weather.temp),
                        color = primaryColor,
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-2).sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "ОЩУЩАЕТСЯ",
                        color = primaryColor.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = formatTemp(weather.feelsLike),
                        color = accentColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val today = weather.daily.firstOrNull()
                    if (today != null) {
                        Text(
                            text = "Днем ${formatTemp(today.maxTemp)} • Ночью ${formatTemp(today.minTemp)}",
                            color = primaryColor.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, primaryColor.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WeatherMetricItem("ВЕТЕР", "${weather.windSpeed} м/с", primaryColor, accentColor)
                WeatherMetricItem("ВЛАЖНОСТЬ", "${weather.humidity}%", primaryColor, accentColor)
                WeatherMetricItem("ДАВЛЕНИЕ", "${weather.pressureMmHg} мм", primaryColor, accentColor)
            }
        }
    }
}

@Composable
fun WeatherMetricItem(
    label: String,
    value: String,
    primaryColor: Color,
    accentColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = primaryColor.copy(alpha = 0.45f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = primaryColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Виджет погоды №2: Прогноз на 4 дня по г. Павлово
 */
@Composable
fun WeatherForecastGridView(
    weather: WeatherData?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.clickable { onRefresh() }
            ) {
                Icon(
                    imageVector = weatherConditionIcon(weather?.weatherCode ?: 0),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "ПРОГНОЗ • ПАВЛОВО",
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = if (weather != null) {
                            "Сейчас ${formatTemp(weather.temp)} • ${weatherConditionText(weather.weatherCode)}"
                        } else {
                            "Загрузка прогноза..."
                        },
                        color = primaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            BatteryBadge(batteryPct, isCharging, primaryColor, accentColor)
        }

        if (weather == null || weather.daily.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoading) "Загрузка прогноза..." else "Нажмите для обновления прогноза",
                    color = primaryColor.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                weather.daily.take(4).forEachIndexed { index, day ->
                    val isToday = index == 0
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = if (isToday) 1.5.dp else 1.dp,
                                color = if (isToday) accentColor.copy(alpha = 0.75f) else primaryColor.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = if (isToday) "СЕГОДНЯ" else day.dayName,
                            color = if (isToday) accentColor else primaryColor.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = day.dateShort,
                            color = primaryColor.copy(alpha = 0.45f),
                            fontSize = 9.sp
                        )
                        Icon(
                            imageVector = weatherConditionIcon(day.weatherCode),
                            contentDescription = null,
                            tint = if (isToday) accentColor else primaryColor,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = formatTemp(day.maxTemp),
                            color = primaryColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatTemp(day.minTemp),
                            color = primaryColor.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "Свайп влево/вправо — Telegram",
                    color = primaryColor.copy(alpha = 0.35f),
                    fontSize = 10.sp
                )
                Text(
                    text = "Обновлено ${weather.updatedTime}",
                    color = primaryColor.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun formatTemp(temp: Int): String = if (temp > 0) "+$temp°" else "$temp°"

private fun weatherConditionText(code: Int): String = when (code) {
    0 -> "Ясно"
    1 -> "Преим. ясно"
    2 -> "Переменная облачность"
    3 -> "Пасмурно"
    45, 48 -> "Туман"
    51, 53, 55, 56, 57 -> "Морось"
    61, 63, 65, 66, 67 -> "Дождь"
    71, 73, 75, 77 -> "Снег"
    80, 81, 82 -> "Ливень"
    85, 86 -> "Снегопад"
    95, 96, 99 -> "Гроза"
    else -> "Облачно"
}

private fun weatherConditionIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Default.WbSunny
    2, 3 -> Icons.Default.Cloud
    45, 48 -> Icons.Default.Dehaze
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Default.Grain
    71, 73, 75, 77, 85, 86 -> Icons.Default.AcUnit
    95, 96, 99 -> Icons.Default.Thunderstorm
    else -> Icons.Default.CloudQueue
}

private suspend fun fetchPavlovoWeatherRawJson(): String? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=55.9619&longitude=43.0900" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,surface_pressure,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&wind_speed_unit=ms" +
                    "&timezone=Europe%2FMoscow" +
                    "&forecast_days=4"
        )
        connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else null
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun parseWeatherJson(rawJson: String): WeatherData? {
    return try {
        val root = JSONObject(rawJson)
        val current = root.getJSONObject("current")
        val temp = current.optDouble("temperature_2m", 0.0).roundToInt()
        val feelsLike = current.optDouble("apparent_temperature", 0.0).roundToInt()
        val weatherCode = current.optInt("weather_code", 0)
        val humidity = current.optInt("relative_humidity_2m", 0)
        val windSpeed = current.optDouble("wind_speed_10m", 0.0).roundToInt()
        val pressureHpa = current.optDouble("surface_pressure", 1013.25)
        val pressureMmHg = (pressureHpa * 0.750062).roundToInt()

        val dailyObj = root.getJSONObject("daily")
        val times = dailyObj.getJSONArray("time")
        val codes = dailyObj.getJSONArray("weather_code")
        val maxTemps = dailyObj.getJSONArray("temperature_2m_max")
        val minTemps = dailyObj.getJSONArray("temperature_2m_min")

        val ruLocale = Locale("ru", "RU")
        val dailyList = mutableListOf<DayForecast>()
        val count = minOf(4, times.length())
        for (i in 0 until count) {
            val dateStr = times.getString(i)
            val parsedDate = LocalDate.parse(dateStr)
            val dayName = parsedDate.dayOfWeek.getDisplayName(TextStyle.SHORT, ruLocale).uppercase(ruLocale)
            val dateShort = String.format("%02d.%02d", parsedDate.dayOfMonth, parsedDate.monthValue)
            dailyList.add(
                DayForecast(
                    dayName = dayName,
                    dateShort = dateShort,
                    maxTemp = maxTemps.optDouble(i, 0.0).roundToInt(),
                    minTemp = minTemps.optDouble(i, 0.0).roundToInt(),
                    weatherCode = codes.optInt(i, 0)
                )
            )
        }

        val updatedTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        WeatherData(
            temp = temp,
            feelsLike = feelsLike,
            weatherCode = weatherCode,
            humidity = humidity,
            windSpeed = windSpeed,
            pressureMmHg = pressureMmHg,
            daily = dailyList,
            updatedTime = updatedTime
        )
    } catch (_: Exception) {
        null
    }
}

@Composable
fun FlipClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlipCard(hourStr, primaryColor, accentColor, Modifier.weight(1f).fillMaxHeight(0.82f))
        FlipCard(minStr, primaryColor, accentColor, Modifier.weight(1f).fillMaxHeight(0.82f))
    }
}

@Composable
fun FlipCard(text: String, primaryColor: Color, accentColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF000000))
            .border(1.5.dp, primaryColor.copy(alpha = 0.22f), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = primaryColor,
            fontSize = 114.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF000000))
        )
    }
}

@Composable
fun DigitalDuoClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$hourStr:$minStr",
            color = primaryColor,
            fontSize = 124.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-3).sp
        )
        Text(
            text = "STANDBY РЕЖИМ",
            color = accentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp
        )
    }
}

@Composable
fun StackedThinClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = hourStr,
                color = primaryColor,
                fontSize = 104.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 96.sp
            )
            Text(
                text = minStr,
                color = accentColor,
                fontSize = 104.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 96.sp
            )
        }
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "ЧАС",
                color = primaryColor.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(72.dp))
            Text(
                text = "МИН",
                color = accentColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun TerminalClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)

    Column(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "[ DIGITAL STANDBY ]",
            color = accentColor.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "$hourStr:$minStr",
            color = primaryColor,
            fontSize = 96.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
fun BatteryBadge(
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .border(1.dp, primaryColor.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (isCharging) Icons.Default.Bolt else Icons.Default.BatteryStd,
            contentDescription = null,
            tint = if (isCharging) accentColor else primaryColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "$batteryPct%",
            color = primaryColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
