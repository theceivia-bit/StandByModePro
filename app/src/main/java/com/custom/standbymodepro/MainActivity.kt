package com.custom.standbymodepro

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.Intent.ACTION_BATTERY_CHANGED
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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

    private var portraitEnterTime = 0L

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Плавное появление окна без вспышек и мигания
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

        try {
            ContextCompat.registerReceiver(
                this,
                closeReceiver,
                IntentFilter("com.custom.standbymodepro.ACTION_CLOSE_STANDBY"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        setContent {
            // Абсолютно черный холст без серых системных подложек
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000))
            ) {
                StandByScreen(
                    ambientLux = currentLux,
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

    override fun onResume() {
        super.onResume()
        portraitEnterTime = 0L
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
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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

// 3 варианта календаря (везде сетка месяца, но разное расположение элементов)
enum class CalendarLayoutStyle(val title: String) {
    LAYOUT_TOP("Дата сверху"),
    LAYOUT_BOTTOM("Дата снизу"),
    LAYOUT_SPLIT("Дата сбоку")
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

@Composable
fun StandByScreen(
    ambientLux: Float,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }

    var clockStyle by remember { mutableStateOf(DigitalClockStyle.FLIP) }
    var calendarStyle by remember { mutableStateOf(CalendarLayoutStyle.LAYOUT_TOP) }
    var swapPanels by remember { mutableStateOf(false) }
    var autoCycleOled by remember { mutableStateOf(true) }
    var rightWidget by remember { mutableStateOf(RightWidget.CALENDAR) }
    var brightnessMode by remember { mutableStateOf(BrightnessMode.AUTO) }
    var themeIndex by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(true) }

    var burnInOffsetX by remember { mutableIntStateOf(0) }
    var burnInOffsetY by remember { mutableIntStateOf(0) }

    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

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
        animationSpec = tween(1000),
        label = "primaryColor"
    )
    val accentColor by animateColorAsState(
        targetValue = currentTheme.accent,
        animationSpec = tween(1000),
        label = "accentColor"
    )

    // Антивыгорание OLED с медленной плавной сменой каждые 3–6 минут
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
            .background(Color(0xFF000000)) // Абсолютно черный OLED фон
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
            .padding(24.dp)
    ) {
        // Плавная 2-секундная смена сторон (лево <-> право)
        AnimatedContent(
            targetState = swapPanels,
            transitionSpec = {
                fadeIn(animationSpec = tween(2000)) togetherWith
                        fadeOut(animationSpec = tween(2000))
            },
            label = "SwapPanelsCrossfade"
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
                        modifier = Modifier.weight(1.1f).fillMaxHeight()
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
                        modifier = Modifier.weight(0.9f).fillMaxHeight()
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
                        modifier = Modifier.weight(0.9f).fillMaxHeight()
                    )
                    ClockContainer(
                        clockStyle = clockStyle,
                        currentTime = currentTime,
                        primaryColor = primaryColor,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1.1f).fillMaxHeight()
                    )
                }
            }
        }

        // Верхняя панель управления
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
                    calendarStyle = CalendarLayoutStyle.entries[
                        (calendarStyle.ordinal + 1) % CalendarLayoutStyle.entries.size
                    ]
                }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text("Календарь: ${calendarStyle.title}", color = Color.White, fontSize = 12.sp)
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
                        if (autoCycleOled) "OLED-защита: ВКЛ" else "OLED-защита: ВЫКЛ",
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
                        Text("Автозапуск", color = Color(0xFFFFD60A), fontSize = 12.sp)
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
        // Медленная 2-секундная плавная анимация смены циферблатов
        AnimatedContent(
            targetState = clockStyle,
            transitionSpec = {
                fadeIn(animationSpec = tween(2000)) togetherWith
                        fadeOut(animationSpec = tween(2000))
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
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when (rightWidget) {
            RightWidget.CALENDAR -> {
                // Медленная 2-секундная плавная анимация смены макетов календаря
                AnimatedContent(
                    targetState = calendarStyle,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(2000)) togetherWith
                                fadeOut(animationSpec = tween(2000))
                    },
                    label = "CalendarStyleCrossfade"
                ) { style ->
                    when (style) {
                        CalendarLayoutStyle.LAYOUT_TOP -> CalendarLayoutTopView(
                            date = currentDate, batteryPct = batteryPct, isCharging = isCharging, primaryColor = primaryColor, accentColor = accentColor
                        )
                        CalendarLayoutStyle.LAYOUT_BOTTOM -> CalendarLayoutBottomView(
                            date = currentDate, batteryPct = batteryPct, isCharging = isCharging, primaryColor = primaryColor, accentColor = accentColor
                        )
                        CalendarLayoutStyle.LAYOUT_SPLIT -> CalendarLayoutSplitView(
                            date = currentDate, batteryPct = batteryPct, isCharging = isCharging, primaryColor = primaryColor, accentColor = accentColor
                        )
                    }
                }
            }
            RightWidget.POMODORO_TIMER -> TimerWidgetView(primaryColor = primaryColor, accentColor = accentColor)
            RightWidget.BATTERY_INFO -> BatteryWidgetView(
                batteryPct = batteryPct, isCharging = isCharging, ambientLux = ambientLux, targetBrightnessPct = targetBrightnessPct, primaryColor = primaryColor, accentColor = accentColor
            )
        }
    }
}

// 1. МАКЕТ КАЛЕНДАРЯ: ДАТА СВЕРХУ, СЕТКА СНИЗУ
@Composable
fun CalendarLayoutTopView(
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

// 2. МАКЕТ КАЛЕНДАРЯ: СЕТКА СВЕРХУ, ДАТА СНИЗУ
@Composable
fun CalendarLayoutBottomView(
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

// 3. МАКЕТ КАЛЕНДАРЯ: ДАТА СБОКУ, СЕТКА СПРАВА
@Composable
fun CalendarLayoutSplitView(
    date: LocalDate,
    batteryPct: Int,
    isCharging: Boolean,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = Locale("ru", "RU")
    val monthName = date.month.getDisplayName(TextStyle.FULL_STANDALONE, ruLocale).uppercase(ruLocale)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$monthName • ВСЯ СЕТКА", color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            BatteryBadge(batteryPct, isCharging, primaryColor, accentColor)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterVertically,
            verticalArrangement = Arrangement.Center
        ) {
            // Крупное число слева
            Column(
                modifier = Modifier.weight(0.4f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = date.dayOfMonth.toString(), color = primaryColor, fontSize = 56.sp, fontWeight = FontWeight.Thin)
                Text(text = "ЧИСЛО", color = accentColor.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }

            // Сетка месяца справа
            Box(modifier = Modifier.weight(0.6f)) {
                MonthGridWidget(date, primaryColor, accentColor)
            }
        }
    }
}

@Composable
fun MonthGridWidget(
    date: LocalDate,
    primaryColor: Color,
    accentColor: Color
) {
    val yearMonth = YearMonth.from(date)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOffset = yearMonth.atDay(1).dayOfWeek.value - 1

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val weekDays = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
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

@Composable
fun FlipClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)
    val secStr = String.format("%02d", time.second)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlipCard(hourStr, "", primaryColor, accentColor, Modifier.weight(1f).fillMaxHeight(0.82f))
        FlipCard(minStr, secStr, primaryColor, accentColor, Modifier.weight(1f).fillMaxHeight(0.82f))
    }
}

@Composable
fun FlipCard(text: String, subtitle: String, primaryColor: Color, accentColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF000000))
            .border(1.5.dp, primaryColor.copy(alpha = 0.22f), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = primaryColor, fontSize = 108.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF000000)))
        if (subtitle.isNotEmpty()) {
            Text(text = subtitle, color = accentColor.copy(alpha = 0.8f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp))
        }
    }
}

@Composable
fun DigitalDuoClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "$hourStr:$minStr", color = primaryColor, fontSize = 114.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp)
        Text(text = String.format("СЕКУНДЫ • %02d", time.second), color = accentColor, fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
    }
}

@Composable
fun StackedThinClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)
    val secStr = String.format("%02d", time.second)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Text(text = hourStr, color = primaryColor, fontSize = 96.sp, fontWeight = FontWeight.Light, lineHeight = 90.sp)
            Text(text = minStr, color = accentColor, fontSize = 96.sp, fontWeight = FontWeight.Light, lineHeight = 90.sp)
        }
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
            Text(text = "ЧАС", color = primaryColor.copy(alpha = 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(64.dp))
            Text(text = "МИН", color = accentColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = ":$secStr", color = primaryColor.copy(alpha = 0.7f), fontSize = 26.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun TerminalClockView(time: LocalTime, primaryColor: Color, accentColor: Color) {
    val hourStr = String.format("%02d", time.hour)
    val minStr = String.format("%02d", time.minute)
    val secStr = String.format("%02d", time.second)

    Column(modifier = Modifier.fillMaxWidth(0.95f).padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "[ DIGITAL STANDBY ]", color = accentColor.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = "$hourStr:$minStr", color = primaryColor, fontSize = 86.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light)
            Text(text = ":$secStr", color = accentColor, fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, modifier = Modifier.padding(bottom = 12.dp, start = 4.dp))
        }
    }
}

@Composable
fun BatteryBadge(batteryPct: Int, isCharging: Boolean, primaryColor: Color, accentColor: Color) {
    Row(
        modifier = Modifier.border(1.dp, primaryColor.copy(alpha = 0.28f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = if (isCharging) Icons.Default.Bolt else Icons.Default.BatteryStd, contentDescription = null, tint = if (isCharging) accentColor else primaryColor, modifier = Modifier.size(16.dp))
        Text(text = "$batteryPct%", color = primaryColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TimerWidgetView(primaryColor: Color, accentColor: Color) {
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

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Text(text = "ФОКУС-ТАЙМЕР", color = accentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(text = String.format("%02d:%02d", mins, secs), color = primaryColor, fontSize = 60.sp, fontWeight = FontWeight.Light)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { isRunning = !isRunning }) {
                Text(if (isRunning) "Пауза" else "Старт", color = accentColor, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { isRunning = false; remainingSeconds = 25 * 60 }) {
                Text("Сброс", color = primaryColor)
            }
        }
    }
}

@Composable
fun BatteryWidgetView(batteryPct: Int, isCharging: Boolean, ambientLux: Float, targetBrightnessPct: Int, primaryColor: Color, accentColor: Color) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Icon(imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd, contentDescription = null, tint = accentColor, modifier = Modifier.size(52.dp))
        Text(text = "$batteryPct%", color = primaryColor, fontSize = 58.sp, fontWeight = FontWeight.Light)
        Text(text = if (isCharging) "ИДЕТ ЗАРЯДКА • STANDBY" else "РАБОТА ОТ БАТАРЕИ", color = primaryColor.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(text = "Свет: ${ambientLux.toInt()} лк • Яркость: $targetBrightnessPct%", color = primaryColor.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}
