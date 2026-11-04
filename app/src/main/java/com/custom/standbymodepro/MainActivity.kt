package com.custom.standbymodepro

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.Intent.ACTION_BATTERY_CHANGED
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var currentLux by mutableFloatStateOf(100f)

    // Триггер для плавного 2-секундного проявления при каждом выходе из AOD
    private var launchCounter by mutableIntStateOf(0)
    private var portraitEnterTime = 0L

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Убираем стандартную резкую системную анимацию открытия окна
        overridePendingTransition(0, 0)

        // ГАРАНТИЯ 100% TRUE BLACK OLED (#000000) НА УРОВНЕ ОКНА ANDROID:
        // Отключаем серую подложку темы, размытие фона и системный dim-эффект
        try {
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.attributes.blurBehindRadius = 0
            }
        } catch (_: Exception) {}

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

        try {
            ContextCompat.registerReceiver(
                this,
                closeReceiver,
                IntentFilter("com.custom.standbymodepro.ACTION_CLOSE_STANDBY"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        setContent {
            StandByScreen(
                ambientLux = currentLux,
                launchTrigger = launchCounter,
                onRequestOverlayPermission = {
                    try {
                        if (!Settings.canDrawOverlays(this)) {
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

        window.decorView.post {
            hideSystemUI()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchCounter++
    }

    override fun onResume() {
        super.onResume()
        portraitEnterTime = 0L
        launchCounter++ // Запускает плавное 2.2-секундное проявление из темноты AOD
        window.decorView.post { hideSystemUI() }
        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_LIGHT -> {
                currentLux = event.values[0]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]

                val isPortrait = abs(y) > 6.5f && abs(x) < 4.5f
                val now = System.currentTimeMillis()

                if (isPortrait) {
                    if (portraitEnterTime == 0L) {
                        portraitEnterTime = now
                    } else if (now - portraitEnterTime > 600L) {
                        finish()
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                } else {
                    portraitEnterTime = 0L
                }
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

// Все 3 режима показывают ПОЛНЫЙ месяц, но меняют местами верх/низ и геометрию сетки
enum class CalendarLayoutStyle(val title: String) {
    TOP_HEADER_GRID("Дата сверху + Сетка"),
    BOTTOM_HERO_GRID("Сетка сверху + Дата снизу"),
    SIDE_SPLIT_GRID("Дата сбоку + Моно-сетка")
}

enum class RightWidget(val title: String) {
    CALENDAR("Календарь"),
    POMODORO_TIMER("Таймер"),
    BATTERY_INFO("Питание")
}

enum class BrightnessMode(val title: String) {
    AUTO("АВТО (15%/30%)"),
    DAY_30("ДЕНЬ (30%)"),
    NIGHT_15("НОЧЬ (15%)")
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

// Единая 2-секундная (2000 мс) плавная анимация для всех переходов
private const val TRANSITION_DURATION_MS = 2000

@Composable
fun StandByScreen(
    ambientLux: Float,
    launchTrigger: Int,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }

    var clockStyle by remember { mutableStateOf(DigitalClockStyle.FLIP) }
    var calendarStyle by remember { mutableStateOf(CalendarLayoutStyle.TOP_HEADER_GRID) }
    var swapPanels by remember { mutableStateOf(false) }
    var autoCycleOled by remember { mutableStateOf(true) }
    var rightWidget by remember { mutableStateOf(RightWidget.CALENDAR) }
    var brightnessMode by remember { mutableStateOf(BrightnessMode.AUTO) }
    var themeIndex by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(false) }

    var burnInOffsetX by remember { mutableIntStateOf(0) }
    var burnInOffsetY by remember { mutableIntStateOf(0) }

    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    // Плавное 2.2-секундное проявление из темноты AOD при повороте телефона
    var aodRevealVisible by remember { mutableStateOf(false) }
    LaunchedEffect(launchTrigger) {
        aodRevealVisible = false
        delay(60L)
        aodRevealVisible = true
    }

    val screenAlpha by animateFloatAsState(
        targetValue = if (aodRevealVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
        label = "aodFadeIn"
    )
    val screenScale by animateFloatAsState(
        targetValue = if (aodRevealVisible) 1f else 0.94f,
        animationSpec = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
        label = "aodScaleIn"
    )

    val isNight = when (brightnessMode) {
        BrightnessMode.AUTO -> ambientLux < 8f
        BrightnessMode.DAY_30 -> false
        BrightnessMode.NIGHT_15 -> true
    }

    val targetBrightness = if (isNight) 0.15f else 0.30f
    LaunchedEffect(targetBrightness) {
        (context as? Activity)?.window?.let { win ->
            val params = win.attributes
            params.screenBrightness = targetBrightness
            win.attributes = params
        }
    }

    val currentTheme = colorThemes[themeIndex]
    val primaryColor by animateColorAsState(
        targetValue = currentTheme.primary,
        animationSpec = tween(1200),
        label = "primaryColor"
    )
    val accentColor by animateColorAsState(
        targetValue = currentTheme.accent,
        animationSpec = tween(1200),
        label = "accentColor"
    )

    // Цикл защиты OLED:
    // - Каждую минуту: микросдвиг координат
    // - Каждые 3 минуты: 2-секундная плавная смена часов и перестановка верха/низа календаря
    // - Каждые 6 минут: 2-секундная плавная смена левой и правой стороны местами
    LaunchedEffect(autoCycleOled) {
        var lastMinute = -1
        var minutesCounter = 0
        while (true) {
            val now = LocalTime.now()
            currentTime = now
            currentDate = LocalDate.now()

            if (now.minute != lastMinute) {
                lastMinute = now.minute
                minutesCounter++

                burnInOffsetX = Random.nextInt(-18, 19)
                burnInOffsetY = Random.nextInt(-14, 15)

                if (autoCycleOled && minutesCounter > 1) {
                    if (minutesCounter % 3 == 0) {
                        clockStyle = DigitalClockStyle.entries[
                            (clockStyle.ordinal + 1) % DigitalClockStyle.entries.size
                        ]
                        calendarStyle = CalendarLayoutStyle.entries[
                            (calendarStyle.ordinal + 1) % CalendarLayoutStyle.entries.size
                        ]
                    }
                    if (minutesCounter % 6 == 0) {
                        swapPanels = !swapPanels
                    }
                }

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
            }
            delay(1000L)
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(6000L)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Чистый #000000
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
            .padding(24.dp)
            .graphicsLayer {
                alpha = screenAlpha
                scaleX = screenScale
                scaleY = screenScale
            }
    ) {
        // 2-секундная плавная анимация смены сторон (лево <-> право)
        AnimatedContent(
            targetState = swapPanels,
            transitionSpec = {
                (fadeIn(animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)) +
                        scaleIn(initialScale = 0.94f, animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)) +
                                scaleOut(targetScale = 0.94f, animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing))
                    )
            },
            label = "SwapPanelsSlowTransition"
        ) { isSwapped ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(burnInOffsetX, burnInOffsetY) },
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isSwapped) {
                    ClockContainer(
                        clockStyle = clockStyle,
                        currentTime = currentTime,
                        primaryColor = primaryColor,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1.08f).fillMaxHeight()
                    )
                    WidgetContainer(
                        rightWidget = rightWidget,
                        calendarStyle = calendarStyle,
                        currentDate = currentDate,
                        batteryPct = batteryPct,
                        isCharging = isCharging,
                        ambientLux = ambientLux,
                        targetBrightnessPct = (targetBrightness * 100).toInt(),
                        primaryColor = primaryColor,
                        accentColor = accentColor,
                        modifier = Modifier.weight(0.92f).fillMaxHeight()
                    )
                } else {
                    WidgetContainer(
                        rightWidget = rightWidget,
                        calendarStyle = calendarStyle,
                        currentDate = currentDate,
                        batteryPct = batteryPct,
                        isCharging = isCharging,
                        ambientLux = ambientLux,
                        targetBrightnessPct = (targetBrightness * 100).toInt(),
                        primaryColor = primaryColor,
                        accentColor = accentColor,
                        modifier = Modifier.weight(0.92f).fillMaxHeight()
                    )
                    ClockContainer(
                        clockStyle = clockStyle,
                        currentTime = currentTime,
                        primaryColor = primaryColor,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1.08f).fillMaxHeight()
                    )
                }
            }
        }

        // Верхняя панель настроек (вызывается по касанию экрана)
        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xEE121214))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
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
                    calendarStyle = CalendarLayoutStyle.entries[
                        (calendarStyle.ordinal + 1) % CalendarLayoutStyle.entries.size
                    ]
                }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text("Макет месяца: ${calendarStyle.title}", color = Color.White, fontSize = 12.sp)
                }

                TextButton(onClick = { swapPanels = !swapPanels }) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("Стороны", color = Color.White, fontSize = 12.sp)
                }

                TextButton(onClick = { autoCycleOled = !autoCycleOled }) {
                    Icon(
                        Icons.Default.Autorenew,
                        contentDescription = null,
                        tint = if (autoCycleOled) accentColor else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (autoCycleOled) "OLED-динамика: ВКЛ" else "OLED-динамика: ВЫКЛ",
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

                TextButton(onClick = {
                    rightWidget = RightWidget.entries[
                        (rightWidget.ordinal + 1) % RightWidget.entries.size
                    ]
                }) {
                    Icon(Icons.Default.Widgets, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text("Виджет: ${rightWidget.title}", color = Color.White, fontSize = 12.sp)
                }

                if (!Settings.canDrawOverlays(context)) {
                    TextButton(onClick = onRequestOverlayPermission) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFFFD60A))
                        Spacer(Modifier.width(4.dp))
                        Text("Разрешить автозапуск", color = Color(0xFFFFD60A), fontSize = 12.sp)
                    }
                }
            }
        }
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
        // 2-секундная плавная анимация смены цифровых циферблатов
        AnimatedContent(
            targetState = clockStyle,
            transitionSpec = {
                (fadeIn(animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)) +
                        scaleIn(initialScale = 0.92f, animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)) +
                                scaleOut(targetScale = 0.92f, animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing))
                    )
            },
            label = "ClockStyleSlowCrossfade"
        ) { targetStyle ->
            when (targetStyle) {
                DigitalClockStyle.FLIP -> FlipClockView(
                    time = currentTime,
                    primaryColor = primaryColor,
                    accentColor = accentColor
                )
                DigitalClockStyle.DUO -> DigitalDuoClockView(
                    time = currentTime,
                    primaryColor = primaryColor,
                    accentColor = accentColor
                )
                DigitalClockStyle.STACKED -> StackedThinClockView(
                    time = currentTime,
                    primaryColor = primaryColor,
                    accentColor = accentColor
                )
                DigitalClockStyle.TERMINAL -> TerminalClockView(
                    time = currentTime,
                    primaryColor = primaryColor,
                    accentColor = accentColor
                )
            }
        }
    }
}

@Composable
fun WidgetContainer(
    rightWidget: RightWidget,
    calendarStyle: CalendarLayoutStyle,
    currentDate: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    ambientLux: Float,
    targetBrightnessPct: Int,
    primaryColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(Color.Black) // 100% True Black
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        when (rightWidget) {
            RightWidget.CALENDAR -> {
                // 2-секундная плавная смена макетов полного месяца
                AnimatedContent(
                    targetState = calendarStyle,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing)) +
                                        scaleOut(targetScale = 0.92f, animationSpec = tween(TRANSITION_DURATION_MS, easing = FastOutSlowInEasing))
                            )
                    },
                    label = "CalendarStyleSlowCrossfade"
                ) { style ->
                    when (style) {
                        CalendarLayoutStyle.TOP_HEADER_GRID -> CalendarTopHeaderMonthView(
                            date = currentDate,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                        CalendarLayoutStyle.BOTTOM_HERO_GRID -> CalendarBottomHeroMonthView(
                            date = currentDate,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                        CalendarLayoutStyle.SIDE_SPLIT_GRID -> CalendarSideSplitMonthView(
                            date = currentDate,
                            batteryPct = batteryPct,
                            isCharging = isCharging,
                            primaryColor = primaryColor,
                            accentColor = accentColor
                        )
                    }
                }
            }
            RightWidget.POMODORO_TIMER -> TimerWidgetView(
                primaryColor = primaryColor,
                accentColor = accentColor
            )
            RightWidget.BATTERY_INFO -> BatteryWidgetView(
                batteryPct = batteryPct,
                isCharging = isCharging,
                ambientLux = ambientLux,
                targetBrightnessPct = targetBrightnessPct,
                primaryColor = primaryColor,
                accentColor = accentColor
            )
        }
    }
}

// =========================================================================
// ЦИФРОВЫЕ ЧАСЫ (4 СТИЛЯ НА ЧИСТОМ ЧЕРНОМ ФОНЕ)
// =========================================================================

@Composable
fun FlipClockView(
    time: LocalTime,
    primaryColor: Color,
    accentColor: Color
) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)
    val secStr = String.format("%02d", time.second)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlipCard(
            text = hourStr,
            subtitle = "",
            primaryColor = primaryColor,
            accentColor = accentColor,
            modifier = Modifier.weight(1f).fillMaxHeight(0.82f)
        )
        FlipCard(
            text = minStr,
            subtitle = secStr,
            primaryColor = primaryColor,
            accentColor = accentColor,
            modifier = Modifier.weight(1f).fillMaxHeight(0.82f)
        )
    }
}

@Composable
fun FlipCard(
    text: String,
    subtitle: String,
    primaryColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black)
            .border(1.5.dp, primaryColor.copy(alpha = 0.22f), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = primaryColor,
            fontSize = 106.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.Black)
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                color = accentColor.copy(alpha = 0.85f),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
            )
        }
    }
}

@Composable
fun DigitalDuoClockView(
    time: LocalTime,
    primaryColor: Color,
    accentColor: Color
) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$hourStr:$minStr",
            color = primaryColor,
            fontSize = 112.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-3).sp
        )
        Text(
            text = String.format("СЕКУНДЫ • %02d", time.second),
            color = accentColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp
        )
    }
}

@Composable
fun StackedThinClockView(
    time: LocalTime,
    primaryColor: Color,
    accentColor: Color
) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)
    val secStr = String.format("%02d", time.second)

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
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                lineHeight = 90.sp
            )
            Text(
                text = minStr,
                color = accentColor,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                lineHeight = 90.sp
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
            Spacer(modifier = Modifier.height(64.dp))
            Text(
                text = "МИН",
                color = accentColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = ":$secStr",
                color = primaryColor.copy(alpha = 0.7f),
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun TerminalClockView(
    time: LocalTime,
    primaryColor: Color,
    accentColor: Color
) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)
    val secStr = String.format("%02d", time.second)

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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$hourStr:$minStr",
                color = primaryColor,
                fontSize = 84.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light
            )
            Text(
                text = ":$secStr",
                color = accentColor,
                fontSize = 32.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
            )
        }
    }
}

// =========================================================================
// КАЛЕНДАРИ ПОЛНОГО МЕСЯЦА (3 ДИЗАЙНА С ПЕРЕСТАНОВКОЙ ВЕРХА И НИЗА)
// =========================================================================

// МАКЕТ 1: Крупная дата и текст месяца СВЕРХУ, полная сетка месяца СНИЗУ
@Composable
fun CalendarTopHeaderMonthView(
    date: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = Locale("ru", "RU")
    val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, ruLocale).uppercase(ruLocale)
    val monthName = date.month.getDisplayName(TextStyle.FULL_STANDALONE, ruLocale).uppercase(ruLocale)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ВЕРХ: Крупное число + месяц и день недели + батарея
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = String.format("%02d", date.dayOfMonth),
                    color = accentColor,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 44.sp
                )
                Column {
                    Text(
                        text = monthName,
                        color = primaryColor,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = dayOfWeek,
                        color = primaryColor.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }

            BatteryBadge(
                batteryPct = batteryPct,
                isCharging = isCharging,
                primaryColor = primaryColor,
                accentColor = accentColor
            )
        }

        // НИЗ: Полная сетка месяца (стиль 1 - тонкие круглые контуры)
        MonthGridTable(
            date = date,
            primaryColor = primaryColor,
            accentColor = accentColor,
            highlightStyle = GridHighlightStyle.CIRCLE_OUTLINE,
            fontFamily = FontFamily.SansSerif
        )
    }
}

// МАКЕТ 2 (МЕНЯЮТСЯ МЕСТАМИ): Полная сетка месяца СВЕРХУ, а крупная дата и месяц СНИЗУ
@Composable
fun CalendarBottomHeroMonthView(
    date: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = Locale("ru", "RU")
    val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, ruLocale).uppercase(ruLocale)
    val monthGenitive = date.month.getDisplayName(TextStyle.FULL, ruLocale).uppercase(ruLocale)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ВЕРХ: Полная сетка месяца (стиль 2 - геометрическая рамка)
        MonthGridTable(
            date = date,
            primaryColor = primaryColor,
            accentColor = accentColor,
            highlightStyle = GridHighlightStyle.ROUNDED_BOX,
            fontFamily = FontFamily.SansSerif
        )

        // НИЗ: Крупная дата числом, месяц и батарея внизу
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            BatteryBadge(
                batteryPct = batteryPct,
                isCharging = isCharging,
                primaryColor = primaryColor,
                accentColor = accentColor
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = dayOfWeek,
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = monthGenitive,
                        color = primaryColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = String.format("%02d", date.dayOfMonth),
                    color = primaryColor,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Thin,
                    lineHeight = 46.sp
                )
            }
        }
    }
}

// МАКЕТ 3 (БОКОВОЙ СПЛИТ): Крупная дата и батарея в колонке СЛЕВА, а моно-сетка всего месяца СПРАВА
@Composable
fun CalendarSideSplitMonthView(
    date: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = Locale("ru", "RU")
    val dayShort = date.dayOfWeek.getDisplayName(TextStyle.SHORT, ruLocale).uppercase(ruLocale)
    val monthName = date.month.getDisplayName(TextStyle.FULL_STANDALONE, ruLocale).uppercase(ruLocale)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Левая узкая колонка с крупным числом, месяцем и зарядом
        Column(
            modifier = Modifier
                .weight(0.36f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            BatteryBadge(
                batteryPct = batteryPct,
                isCharging = isCharging,
                primaryColor = primaryColor,
                accentColor = accentColor
            )

            Column {
                Text(
                    text = String.format("%02d", date.dayOfMonth),
                    color = accentColor,
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 52.sp
                )
                Text(
                    text = monthName,
                    color = primaryColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "$dayShort • ${date.year}",
                    color = primaryColor.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Правая часть: полная сетка месяца моноширинным шрифтом с нижним подчеркиванием даты
        Box(
            modifier = Modifier
                .weight(0.64f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            MonthGridTable(
                date = date,
                primaryColor = primaryColor,
                accentColor = accentColor,
                highlightStyle = GridHighlightStyle.UNDERLINE_DOT,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

enum class GridHighlightStyle {
    CIRCLE_OUTLINE,
    ROUNDED_BOX,
    UNDERLINE_DOT
}

@Composable
fun MonthGridTable(
    date: LocalDate,
    primaryColor: Color,
    accentColor: Color,
    highlightStyle: GridHighlightStyle,
    fontFamily: FontFamily
) {
    val yearMonth = YearMonth.from(date)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOffset = yearMonth.atDay(1).dayOfWeek.value - 1

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val weekDays = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDays.forEachIndexed { index, d ->
                val isWeekendHeader = index >= 5
                Text(
                    text = d,
                    color = if (isWeekendHeader) accentColor.copy(alpha = 0.65f) else primaryColor.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
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
                        val isWeekend = c >= 5

                        val cellModifier = when {
                            !isToday -> Modifier
                            highlightStyle == GridHighlightStyle.CIRCLE_OUTLINE ->
                                Modifier.border(1.5.dp, accentColor, CircleShape)
                            highlightStyle == GridHighlightStyle.ROUNDED_BOX ->
                                Modifier.border(1.5.dp, accentColor, RoundedCornerShape(6.dp))
                            else -> Modifier
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .then(cellModifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = dayNum.toString(),
                                    color = when {
                                        isToday -> accentColor
                                        isWeekend -> primaryColor.copy(alpha = 0.55f)
                                        else -> primaryColor.copy(alpha = 0.85f)
                                    },
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily,
                                    fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Light
                                )
                                if (isToday && highlightStyle == GridHighlightStyle.UNDERLINE_DOT) {
                                    Box(
                                        modifier = Modifier
                                            .width(12.dp)
                                            .height(1.5.dp)
                                            .background(accentColor)
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
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
            .border(1.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (isCharging) Icons.Default.Bolt else Icons.Default.BatteryStd,
            contentDescription = null,
            tint = if (isCharging) accentColor else primaryColor,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = "$batteryPct%",
            color = primaryColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TimerWidgetView(
    primaryColor: Color,
    accentColor: Color
) {
    var remainingSeconds by remember { mutableIntStateOf(25 * 60) }
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (isRunning && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (remainingSeconds == 0) isRunning = false
    }

    val mins = remainingSeconds / 60
    val secs = remainingSeconds % 60

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text(
            text = "ФОКУС-ТАЙМЕР",
            color = accentColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Text(
            text = String.format("%02d:%02d", mins, secs),
            color = primaryColor,
            fontSize = 60.sp,
            fontWeight = FontWeight.Light
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = { isRunning = !isRunning }
            ) {
                Text(
                    if (isRunning) "Пауза" else "Старт",
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = {
                    isRunning = false
                    remainingSeconds = 25 * 60
                }
            ) {
                Text("Сброс", color = primaryColor)
            }
        }
    }
}

@Composable
fun BatteryWidgetView(
    batteryPct: Int,
    isCharging: Boolean,
    ambientLux: Float,
    targetBrightnessPct: Int,
    primaryColor: Color,
    accentColor: Color
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Icon(
            imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(52.dp)
        )

        Text(
            text = "$batteryPct%",
            color = primaryColor,
            fontSize = 58.sp,
            fontWeight = FontWeight.Light
        )

        Text(
            text = if (isCharging) "ИДЕТ ЗАРЯДКА • STANDBY" else "РАБОТА ОТ БАТАРЕИ",
            color = primaryColor.copy(alpha = 0.7f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Свет: ${ambientLux.toInt()} лк • Яркость: $targetBrightnessPct%",
            color = primaryColor.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}
