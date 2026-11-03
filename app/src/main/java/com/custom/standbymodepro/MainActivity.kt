package com.custom.standbymodepro

import android.app.KeyguardManager
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var currentLux by mutableFloatStateOf(100f)

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                keyguardManager?.requestDismissKeyguard(this, null)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

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

        // Вызываем скрытие статус-бара ТОЛЬКО после того, как окно создано в setContent
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
        window.decorView.post { hideSystemUI() }
        lightSensor?.let {
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
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

enum class ClockStyle { FLIP, DIGITAL_DUO, ANALOG }
enum class RightWidget { CALENDAR, POMODORO_TIMER, BATTERY_INFO }

@Composable
fun StandByScreen(
    ambientLux: Float,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }

    var clockStyle by remember { mutableStateOf(ClockStyle.FLIP) }
    var rightWidget by remember { mutableStateOf(RightWidget.CALENDAR) }
    var autoNightMode by remember { mutableStateOf(true) }
    var manualNightMode by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    var burnInOffsetX by remember { mutableIntStateOf(0) }
    var burnInOffsetY by remember { mutableIntStateOf(0) }

    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    val isNightActive = manualNightMode || (autoNightMode && ambientLux < 8f)

    val primaryColor by animateColorAsState(
        targetValue = if (isNightActive) Color(0xFFFF2A2A) else Color(0xFFF5F5F7),
        label = "primaryColor"
    )
    val accentColor by animateColorAsState(
        targetValue = if (isNightActive) Color(0xFF990000) else Color(0xFF32D74B),
        label = "accentColor"
    )
    val cardBgColor by animateColorAsState(
        targetValue = if (isNightActive) Color(0xFF1A0505) else Color(0xFF1C1C1E),
        label = "cardBg"
    )

    LaunchedEffect(Unit) {
        var lastMinute = -1
        while (true) {
            val now = LocalTime.now()
            currentTime = now
            currentDate = LocalDate.now()

            if (now.minute != lastMinute) {
                lastMinute = now.minute
                burnInOffsetX = Random.nextInt(-12, 13)
                burnInOffsetY = Random.nextInt(-12, 13)

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
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(burnInOffsetX, burnInOffsetY) },
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                when (clockStyle) {
                    ClockStyle.FLIP -> FlipClockView(
                        time = currentTime,
                        primaryColor = primaryColor,
                        cardBgColor = cardBgColor
                    )
                    ClockStyle.DIGITAL_DUO -> DigitalDuoClockView(
                        time = currentTime,
                        primaryColor = primaryColor,
                        accentColor = accentColor
                    )
                    ClockStyle.ANALOG -> AnalogClockView(
                        time = currentTime,
                        primaryColor = primaryColor,
                        accentColor = accentColor,
                        cardBgColor = cardBgColor
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(32.dp))
                    .background(cardBgColor.copy(alpha = 0.7f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when (rightWidget) {
                    RightWidget.CALENDAR -> CalendarWidgetView(
                        date = currentDate,
                        primaryColor = primaryColor,
                        accentColor = accentColor
                    )
                    RightWidget.POMODORO_TIMER -> TimerWidgetView(
                        primaryColor = primaryColor,
                        accentColor = accentColor
                    )
                    RightWidget.BATTERY_INFO -> BatteryWidgetView(
                        batteryPct = batteryPct,
                        isCharging = isCharging,
                        ambientLux = ambientLux,
                        primaryColor = primaryColor,
                        accentColor = accentColor
                    )
                }
            }
        }

        if (showControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xDD2C2C2E))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    clockStyle = ClockStyle.entries[(clockStyle.ordinal + 1) % ClockStyle.entries.size]
                }) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Часы: ${clockStyle.name}", color = Color.White, fontSize = 13.sp)
                }

                TextButton(onClick = {
                    rightWidget = RightWidget.entries[(rightWidget.ordinal + 1) % RightWidget.entries.size]
                }) {
                    Icon(Icons.Default.Widgets, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Виджет: ${rightWidget.name}", color = Color.White, fontSize = 13.sp)
                }

                TextButton(onClick = {
                    manualNightMode = !manualNightMode
                }) {
                    Icon(
                        Icons.Default.NightlightRound,
                        contentDescription = null,
                        tint = if (isNightActive) Color.Red else Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isNightActive) "Ночной (ВКЛ)" else "Ночной (АВТО)",
                        color = if (isNightActive) Color(0xFFFF5555) else Color.White,
                        fontSize = 13.sp
                    )
                }

                if (!Settings.canDrawOverlays(context)) {
                    TextButton(onClick = onRequestOverlayPermission) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFFFD60A))
                        Spacer(Modifier.width(4.dp))
                        Text("Автозапуск", color = Color(0xFFFFD60A), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun FlipClockView(
    time: LocalTime,
    primaryColor: Color,
    cardBgColor: Color
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
            cardBgColor = cardBgColor,
            modifier = Modifier.weight(1f).fillMaxHeight(0.85f)
        )
        FlipCard(
            text = minStr,
            subtitle = secStr,
            primaryColor = primaryColor,
            cardBgColor = cardBgColor,
            modifier = Modifier.weight(1f).fillMaxHeight(0.85f)
        )
    }
}

@Composable
fun FlipCard(
    text: String,
    subtitle: String,
    primaryColor: Color,
    cardBgColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(cardBgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = primaryColor,
            fontSize = 110.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.Black.copy(alpha = 0.85f))
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                color = primaryColor.copy(alpha = 0.55f),
                fontSize = 20.sp,
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
            fontSize = 120.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-4).sp
        )
        Text(
            text = String.format("СЕКУНДЫ • %02d", time.second),
            color = accentColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp
        )
    }
}

@Composable
fun AnalogClockView(
    time: LocalTime,
    primaryColor: Color,
    accentColor: Color,
    cardBgColor: Color
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxHeight(0.85f)
            .clip(CircleShape)
            .background(cardBgColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30).toDouble() - 90)
                val startR = radius * 0.84f
                val endR = radius * 0.95f
                drawLine(
                    color = primaryColor.copy(alpha = 0.6f),
                    start = Offset(
                        center.x + (startR * cos(angle)).toFloat(),
                        center.y + (startR * sin(angle)).toFloat()
                    ),
                    end = Offset(
                        center.x + (endR * cos(angle)).toFloat(),
                        center.y + (endR * sin(angle)).toFloat()
                    ),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
            }

            val hourAngle = Math.toRadians(((time.hour % 12) * 30 + time.minute * 0.5) - 90)
            drawLine(
                color = primaryColor,
                start = center,
                end = Offset(
                    center.x + (radius * 0.5f * cos(hourAngle)).toFloat(),
                    center.y + (radius * 0.5f * sin(hourAngle)).toFloat()
                ),
                strokeWidth = 14f,
                cap = StrokeCap.Round
            )

            val minAngle = Math.toRadians((time.minute * 6.0) - 90)
            drawLine(
                color = primaryColor,
                start = center,
                end = Offset(
                    center.x + (radius * 0.76f * cos(minAngle)).toFloat(),
                    center.y + (radius * 0.76f * sin(minAngle)).toFloat()
                ),
                strokeWidth = 10f,
                cap = StrokeCap.Round
            )

            val secAngle = Math.toRadians((time.second * 6.0) - 90)
            drawLine(
                color = accentColor,
                start = center,
                end = Offset(
                    center.x + (radius * 0.86f * cos(secAngle)).toFloat(),
                    center.y + (radius * 0.86f * sin(secAngle)).toFloat()
                ),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )

            drawCircle(color = accentColor, radius = 10f, center = center)
        }
    }
}

@Composable
fun CalendarWidgetView(
    date: LocalDate,
    primaryColor: Color,
    accentColor: Color
) {
    val ruLocale = Locale("ru", "RU")
    val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, ruLocale).uppercase(ruLocale)
    val monthName = date.month.getDisplayName(TextStyle.FULL_STANDALONE, ruLocale).uppercase(ruLocale)
    val yearMonth = YearMonth.from(date)
    val daysInMonth = yearMonth.lengthOfMonth()
    val firstDayOffset = yearMonth.atDay(1).dayOfWeek.value - 1

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = dayOfWeek,
                color = accentColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "${date.dayOfMonth} $monthName",
                color = primaryColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val weekDays = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
            Row(modifier = Modifier.fillMaxWidth()) {
                weekDays.forEach { d ->
                    Text(
                        text = d,
                        color = primaryColor.copy(alpha = 0.45f),
                        fontSize = 11.sp,
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
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isToday) accentColor else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayNum.toString(),
                                    color = if (isToday) Color.Black else primaryColor,
                                    fontSize = 13.sp,
                                    fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Medium
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
            fontWeight = FontWeight.Bold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { isRunning = !isRunning },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
            ) {
                Text(
                    if (isRunning) "Пауза" else "Старт",
                    color = Color.Black,
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
            modifier = Modifier.size(56.dp)
        )

        Text(
            text = "$batteryPct%",
            color = primaryColor,
            fontSize = 60.sp,
            fontWeight = FontWeight.ExtraBold
        )

        Text(
            text = if (isCharging) "ИДЕТ ЗАРЯДКА • STANDBY АКТИВЕН" else "РАБОТА ОТ БАТАРЕИ",
            color = primaryColor.copy(alpha = 0.7f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Датчик света: ${ambientLux.toInt()} лк (Ночной < 8 лк)",
            color = primaryColor.copy(alpha = 0.45f),
            fontSize = 12.sp
        )
    }
}
