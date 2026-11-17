package com.ahmed.rakaattracker

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ahmed.rakaattracker.data.SettingsStore
import com.ahmed.rakaattracker.data.TriggerButtonMode
import com.ahmed.rakaattracker.data.VibrationTiming
import com.ahmed.rakaattracker.prayer.PrayerTimeCalculator
import com.ahmed.rakaattracker.receiver.PrayerWindowReceiver
import com.ahmed.rakaattracker.service.PrayerTrackerService
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        PrayerWindowReceiver.scheduleNextPrayerAlarms(this)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0F766E))) {
                MainAppScaffold()
            }
        }
    }
}

@Composable
fun MainAppScaffold() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("🕌", fontSize = 18.sp) },
                    label = { Text("الرئيسية") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("⚙️", fontSize = 18.sp) },
                    label = { Text("الإعدادات") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                RakaatTrackerHomeScreen()
            } else {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun RakaatTrackerHomeScreen() {
    val context = LocalContext.current
    val state by PrayerTrackerService.uiState.collectAsState()
    val settings by SettingsStore.settings.collectAsState()
    val prayers = remember { PrayerTimeCalculator.calculateToday() }
    val (inWindow, activePrayer) = remember(settings.prayerWindowMinutes) {
        PrayerTimeCalculator.isWithinPrayerWindow(windowMinutesAfterAdhan = settings.prayerWindowMinutes)
    }

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    var hasDndAccess by remember { mutableStateOf(nm.isNotificationPolicyAccessGranted) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "متابع الركعات الذكي",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (inWindow && activePrayer != null)
                    "أنت الآن ضمن نافذة صلاة ${activePrayer.nameAr}"
                else
                    "مواقيت بني سويف (الهيئة المصرية العامة للمساحة)",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isPrayerActive) Color(0xFFCCFBF1) else Color(0xFFF1F5F9)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("الركعة الحالية", fontSize = 15.sp, color = Color(0xFF334155))
                    Text(
                        text = "${state.rakahCount}",
                        fontSize = 56.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F766E)
                    )
                    Text(
                        text = "الوضعية: ${state.posture.labelAr} (${state.tiltDegrees.toInt()}°)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = state.trackingMode.labelAr,
                        fontSize = 13.sp,
                        color = Color(0xFF0F766E),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.lastStatusNote,
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val action = if (state.isPrayerActive)
                                    PrayerTrackerService.ACTION_STOP_PRAYER
                                else
                                    PrayerTrackerService.ACTION_START_PRAYER
                                context.startForegroundService(
                                    Intent(context, PrayerTrackerService::class.java).apply {
                                        this.action = action
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (state.isPrayerActive) "إنهاء الصلاة" else "دخول وضع الصلاة الآن")
                        }
                        if (state.isPrayerActive) {
                            OutlinedButton(
                                onClick = {
                                    context.startForegroundService(
                                        Intent(context, PrayerTrackerService::class.java).apply {
                                            action = PrayerTrackerService.ACTION_UNDO_RAKAH
                                        }
                                    )
                                }
                            ) {
                                Text("إلغاء العدة")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    context.startService(
                                        Intent(context, PrayerTrackerService::class.java).apply {
                                            action = PrayerTrackerService.ACTION_TEST_VIBRATION
                                        }
                                    )
                                }
                            ) {
                                Text("تجربة الهزاز")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الصلاحيات المطلوبة", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("كتم اهتزاز الإشعارات (DND)", fontWeight = FontWeight.Medium)
                            Text(
                                if (hasDndAccess) "مفعّل — سيتم كتم الإشعارات تلقائياً" else "مطلوب لمنع تداخل اهتزاز الإشعارات",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            hasDndAccess = nm.isNotificationPolicyAccessGranted
                        }) {
                            Text(if (hasDndAccess) "مفعّل" else "تفعيل")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("أزرار الصوت (إمكانية الوصول)", fontWeight = FontWeight.Medium)
                            Text(
                                "مفعّل لـ: ${settings.triggerMode.labelAr}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text("الإعدادات")
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("مواقيت الصلاة اليوم", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    prayers.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(p.nameAr, fontSize = 15.sp)
                            Text(p.formatTime(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings by SettingsStore.settings.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("إعدادات التحكم", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("تحكم في طريقة الدخول للصلاة والحماية التلقائية", fontSize = 13.sp, color = Color.Gray)
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("توقيت تفعيل وضع الصلاة", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("التشغيل في مواقيت الصلاة فقط", fontWeight = FontWeight.Medium)
                            Text(
                                if (settings.onlyDuringPrayerTimes)
                                    "الـ 3 ضغطات تشتغل فقط بعد الأذان بـ ${settings.prayerWindowMinutes} دقيقة"
                                else
                                    "الـ 3 ضغطات تشتغل في أي وقت (حتى للسنن أو التجربة)",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = settings.onlyDuringPrayerTimes,
                            onCheckedChange = { checked ->
                                SettingsStore.update(context) { it.copy(onlyDuringPrayerTimes = checked) }
                            }
                        )
                    }

                    if (settings.onlyDuringPrayerTimes) {
                        Text(
                            "مدة السماح بعد الأذان: ${settings.prayerWindowMinutes} دقيقة",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = settings.prayerWindowMinutes.toFloat(),
                            onValueChange = { valMin ->
                                SettingsStore.update(context) {
                                    it.copy(prayerWindowMinutes = valMin.roundToInt())
                                }
                            },
                            valueRange = 15f..90f,
                            steps = 4
                        )
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختيار زرار الـ 3 ضغطات", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TriggerButtonMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SettingsStore.update(context) { it.copy(triggerMode = mode) }
                                    if (mode == TriggerButtonMode.POWER_SCREEN_3X) {
                                        context.startForegroundService(
                                            Intent(context, PrayerTrackerService::class.java).apply {
                                                action = PrayerTrackerService.ACTION_START_STANDBY
                                            }
                                        )
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.triggerMode == mode,
                                onClick = {
                                    SettingsStore.update(context) { it.copy(triggerMode = mode) }
                                }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(mode.labelAr, fontWeight = FontWeight.Medium)
                                Text(mode.descriptionAr, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("وقت تنبيه الهز في الركعة", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    VibrationTiming.entries.forEach { timing ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SettingsStore.update(context) { it.copy(vibrationTiming = timing) }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.vibrationTiming == timing,
                                onClick = {
                                    SettingsStore.update(context) { it.copy(vibrationTiming = timing) }
                                }
                            )
                            Text(
                                text = timing.labelAr,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("وضع الأرض (مستشعر التقارب)", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("التحويل التلقائي لوضع الأرض", fontWeight = FontWeight.Medium)
                            Text(
                                "عند وضع التليفون ثابتاً ومكشوفاً على الأرض، يلقط السجود بمستشعر التقارب",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = settings.autoGroundModeEnabled,
                            onCheckedChange = { checked ->
                                SettingsStore.update(context) { it.copy(autoGroundModeEnabled = checked) }
                            }
                        )
                    }

                    Text(
                        "قوة اهتزاز وضع الأرض: ${(settings.groundVibrationStrength * 100 / 255)}% (هادئ)",
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = settings.groundVibrationStrength.toFloat(),
                        onValueChange = { v ->
                            SettingsStore.update(context) { it.copy(groundVibrationStrength = v.roundToInt()) }
                        },
                        valueRange = 40f..200f
                    )
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("إعدادات الحماية والحساسات", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    Text(
                        "الحماية التلقائية — أقل مدة للركعة: ${settings.minRakahSeconds} ثانية",
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = settings.minRakahSeconds.toFloat(),
                        onValueChange = { v ->
                            SettingsStore.update(context) { it.copy(minRakahSeconds = v.roundToInt()) }
                        },
                        valueRange = 8f..20f,
                        steps = 11
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("وضع جيب البنطلون", fontWeight = FontWeight.Medium)
                            Text(
                                if (settings.pocketMode) "مفعّل (أفضل لجيب البنطلون)" else "معطّل (مناسب لجيب القميص)",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = settings.pocketMode,
                            onCheckedChange = { checked ->
                                SettingsStore.update(context) { it.copy(pocketMode = checked) }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الحماية اليدوية بأزرار الصوت", fontWeight = FontWeight.Medium)
                            Text(
                                "إلغاء العدة الخاطئة بضغطة رفع أو خفض الصوت",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = settings.manualUndoEnabled,
                            onCheckedChange = { checked ->
                                SettingsStore.update(context) { it.copy(manualUndoEnabled = checked) }
                            }
                        )
                    }
                }
            }
        }
    }
}
