package com.oppzippy.openscq30.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.oppzippy.openscq30.MainActivity
import com.oppzippy.openscq30.R
import com.oppzippy.openscq30.features.fastpair.FastPairBatteryReader
import com.oppzippy.openscq30.features.fastpair.FastPairBatteryState
import com.oppzippy.openscq30.features.fastpair.FastPairBatteryValue
import com.oppzippy.openscq30.features.soundcoredevice.service.ConnectionStatus
import com.oppzippy.openscq30.features.soundcoredevice.service.DeviceService
import com.oppzippy.openscq30.lib.bindings.OpenScq30Device
import com.oppzippy.openscq30.lib.bindings.OpenScq30Session
import com.oppzippy.openscq30.lib.bindings.translateDeviceModel
import com.oppzippy.openscq30.lib.wrapper.PairedDevice
import com.oppzippy.openscq30.lib.wrapper.Setting
import com.oppzippy.openscq30.lib.wrapper.toValue
import com.oppzippy.openscq30.ui.theme.OpenSCQ30GlanceTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BudBattery(val percent: Int, val isCharging: Boolean, val isExact: Boolean)

@Serializable
sealed class BudsWidgetState {
    @Serializable
    data class Disconnected(val pairedDevices: List<PairedDevice>) : BudsWidgetState()

    @Serializable
    data class Connecting(val deviceName: String) : BudsWidgetState()

    @Serializable
    data class Connected(
        val deviceName: String,
        val left: BudBattery?,
        val right: BudBattery?,
        val case: BudBattery?,
        /** Single battery devices (headphones) */
        val single: BudBattery?,
        val soundMode: String?,
        val soundModeOptions: List<String>,
        val soundModeLabels: List<String>,
    ) : BudsWidgetState()
}

/**
 * Glanceable earbuds widget: battery card on top, ambient sound mode tile below that cycles
 * noise canceling -> transparency -> normal when tapped. Needs no configuration.
 */
class BudsWidget : GlanceAppWidget() {
    companion object {
        private const val TAG = "BudsWidget"
        private val STATE_KEY = stringPreferencesKey("budsState")
        const val SOUND_MODE_SETTING_ID = "ambientSoundMode"
    }

    override val sizeMode: SizeMode = SizeMode.Exact

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BudsWidgetHiltEntryPoint {
        fun preferences(): com.oppzippy.openscq30.features.preferences.Preferences
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val applicationPreferences =
            EntryPointAccessors.fromApplication<BudsWidgetHiltEntryPoint>(context.applicationContext).preferences()

        provideContent {
            val state = currentState<Preferences>()[STATE_KEY]?.let {
                try {
                    Json.decodeFromString<BudsWidgetState>(it)
                } catch (ex: Exception) {
                    Log.w(TAG, "error decoding state", ex)
                    null
                }
            } ?: BudsWidgetState.Disconnected(emptyList())
            val prefersDynamicColor by applicationPreferences.dynamicColorFlow.collectAsState()
            OpenSCQ30GlanceTheme(dynamicColor = prefersDynamicColor) {
                BudsContent(context, state)
            }
        }
    }

    suspend fun updateAll(context: Context, session: OpenScq30Session, connectionStatus: ConnectionStatus) {
        val newState = buildState(session, connectionStatus)
        val json = Json.encodeToString<BudsWidgetState>(newState)
        GlanceAppWidgetManager(context).getGlanceIds(javaClass).forEach { glanceId ->
            var changed = false
            updateAppWidgetState(context, glanceId) { prefs ->
                if (prefs[STATE_KEY] != json) {
                    prefs[STATE_KEY] = json
                    changed = true
                }
            }
            if (changed) update(context, glanceId)
        }
    }

    suspend fun updatePairedDevices(context: Context, pairedDevices: List<PairedDevice>) {
        val json = Json.encodeToString<BudsWidgetState>(BudsWidgetState.Disconnected(pairedDevices))
        GlanceAppWidgetManager(context).getGlanceIds(javaClass).forEach { glanceId ->
            var changed = false
            updateAppWidgetState(context, glanceId) { prefs ->
                val current = prefs[STATE_KEY]?.let {
                    try {
                        Json.decodeFromString<BudsWidgetState>(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (current == null || current is BudsWidgetState.Disconnected) {
                    if (prefs[STATE_KEY] != json) {
                        prefs[STATE_KEY] = json
                        changed = true
                    }
                }
            }
            if (changed) update(context, glanceId)
        }
    }

    private fun buildState(session: OpenScq30Session, connectionStatus: ConnectionStatus): BudsWidgetState =
        when (connectionStatus) {
            ConnectionStatus.AwaitingConnection, ConnectionStatus.Disconnected ->
                BudsWidgetState.Disconnected(session.pairedDevices())

            is ConnectionStatus.Connecting -> BudsWidgetState.Connecting(connectionStatus.macAddress)

            is ConnectionStatus.Connected -> try {
                val device = connectionStatus.deviceManager.device
                buildConnected(device)
            } catch (ex: IllegalStateException) {
                Log.w(TAG, "device was closed, assuming disconnected", ex)
                BudsWidgetState.Disconnected(emptyList())
            }
        }

    private fun buildConnected(device: OpenScq30Device): BudsWidgetState.Connected {
        val fastPair = (FastPairBatteryReader.state.value as? FastPairBatteryState.Active)?.battery

        fun soundcore(levelId: String, chargingId: String?): BudBattery? {
            val level = (device.setting(levelId) as? Setting.InformationSetting)?.value ?: return null
            val percent = parseFraction(level) ?: return null
            val charging = chargingId?.let { (device.setting(it) as? Setting.InformationSetting)?.value }
                ?.let { it.equals("yes", ignoreCase = true) || it.equals("true", ignoreCase = true) } ?: false
            return BudBattery(percent, charging, isExact = false)
        }

        fun FastPairBatteryValue.toBud() = BudBattery(percent, isCharging, isExact = true)

        val left = fastPair?.left?.toBud() ?: soundcore("batteryLevelLeft", "isChargingLeft")
        val right = fastPair?.right?.toBud() ?: soundcore("batteryLevelRight", "isChargingRight")
        val case = fastPair?.case?.toBud() ?: soundcore("caseBatteryLevel", null)
        val single = if (left == null && right == null) soundcore("batteryLevel", "isCharging") else null

        val soundMode = device.setting(SOUND_MODE_SETTING_ID) as? Setting.SelectSetting
        return BudsWidgetState.Connected(
            deviceName = translateDeviceModel(device.model()),
            left = left,
            right = right,
            case = case,
            single = single,
            soundMode = soundMode?.value,
            soundModeOptions = soundMode?.setting?.options ?: emptyList(),
            soundModeLabels = soundMode?.setting?.localizedOptions ?: emptyList(),
        )
    }
}

class BudsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudsWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // When a widget is added while the device service is already running, ask it for the current state.
        // If the service isn't running nobody receives this, and the widget correctly shows disconnected.
        if (DeviceService.isRunning.value) {
            context.sendBroadcast(
                Intent(DeviceService.ACTION_UPDATE_WIDGET).apply {
                    `package` = context.packageName
                    putExtra(DeviceService.EXTRA_BUDS_WIDGET_ONLY, true)
                },
            )
        }
    }
}

/** "3/5" -> 60 */
private fun parseFraction(value: String): Int? {
    val parts = value.split("/")
    if (parts.size != 2) return null
    val level = parts[0].trim().toIntOrNull() ?: return null
    val max = parts[1].trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    return ((level * 100) / max).coerceIn(0, 100)
}

private val CHARGING_GREEN = ColorProvider(Color(0xFF30A46C))
private val LOW_RED = ColorProvider(Color(0xFFE5484D))
private val LOW_ORANGE = ColorProvider(Color(0xFFF5A524))

@Composable
private fun BudsContent(context: Context, state: BudsWidgetState) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(8.dp),
    ) {
        // Top card: batteries
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(16.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            contentAlignment = Alignment.CenterStart,
        ) {
            when (state) {
                is BudsWidgetState.Connected -> BatteryCard(state)
                is BudsWidgetState.Connecting -> StatusText("Connecting…")
                is BudsWidgetState.Disconnected -> StatusText("Disconnected")
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        // Bottom tile: sound mode toggle (or connect button)
        when (state) {
            is BudsWidgetState.Connected -> SoundModeTile(context, state)
            is BudsWidgetState.Connecting -> Tile(
                icon = R.drawable.bluetooth_24px,
                label = null,
                background = GlanceTheme.colors.surface,
                foreground = GlanceTheme.colors.onSurface,
                action = null,
            )

            is BudsWidgetState.Disconnected -> {
                val device = state.pairedDevices.firstOrNull()
                Tile(
                    icon = R.drawable.bluetooth_24px,
                    label = if (device != null) "Connect" else null,
                    background = GlanceTheme.colors.surface,
                    foreground = GlanceTheme.colors.onSurface,
                    action = device?.let { connectAction(context, it.macAddress) }
                        ?: actionStartActivity(Intent(context, MainActivity::class.java)),
                )
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        maxLines = 1,
    )
}

@Composable
private fun BatteryCard(state: BudsWidgetState.Connected) {
    Column(GlanceModifier.fillMaxWidth()) {
        if (state.single != null) {
            BatteryRow(R.drawable.headphones_24px, state.single)
        } else {
            state.left?.let { BatteryRow(R.drawable.earbud_left_24px, it) }
            state.right?.let { BatteryRow(R.drawable.earbud_right_24px, it) }
            state.case?.let { BatteryRow(R.drawable.earbud_case_24px, it) }
        }
    }
}

@Composable
private fun BatteryRow(icon: Int, battery: BudBattery) {
    val barColor = when {
        battery.percent <= 20 -> LOW_RED
        battery.percent <= 40 -> LOW_ORANGE
        else -> GlanceTheme.colors.onSurface
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(20.dp), contentAlignment = Alignment.BottomEnd) {
            Image(
                modifier = GlanceModifier.fillMaxSize(),
                provider = ImageProvider(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            )
            if (battery.isCharging) {
                Box(
                    GlanceModifier.size(8.dp).cornerRadius(4.dp).background(GlanceTheme.colors.surface).padding(1.dp),
                ) {
                    Spacer(GlanceModifier.fillMaxSize().cornerRadius(3.dp).background(CHARGING_GREEN))
                }
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        LinearProgressIndicator(
            progress = battery.percent / 100f,
            modifier = GlanceModifier.defaultWeight().height(5.dp).cornerRadius(3.dp),
            color = barColor,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = (if (battery.isExact) "" else "≈") + "${battery.percent}",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            ),
            modifier = GlanceModifier.width(30.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SoundModeTile(context: Context, state: BudsWidgetState.Connected) {
    val options = state.soundModeOptions
    val current = state.soundMode
    if (options.isEmpty() || current == null) {
        // Device has no ambient sound mode (or it isn't available right now)
        Tile(
            icon = R.drawable.headphones_24px,
            label = state.deviceName,
            background = GlanceTheme.colors.surface,
            foreground = GlanceTheme.colors.onSurface,
            action = actionStartActivity(Intent(context, MainActivity::class.java)),
        )
        return
    }
    val index = options.indexOf(current).coerceAtLeast(0)
    val next = options[(index + 1) % options.size]
    val label = state.soundModeLabels.getOrNull(index) ?: current

    val (icon, background, foreground) = when (current) {
        "NoiseCanceling" -> Triple(R.drawable.sound_mode_anc, GlanceTheme.colors.primary, GlanceTheme.colors.onPrimary)
        "Transparency" -> Triple(
            R.drawable.sound_mode_transparency,
            GlanceTheme.colors.tertiaryContainer,
            GlanceTheme.colors.onTertiaryContainer,
        )

        else -> Triple(R.drawable.sound_mode_off, GlanceTheme.colors.surface, GlanceTheme.colors.onSurface)
    }
    Tile(
        icon = icon,
        label = label,
        background = background,
        foreground = foreground,
        action = setSettingAction(context, BudsWidget.SOUND_MODE_SETTING_ID, next),
    )
}

@Composable
private fun Tile(icon: Int, label: String?, background: ColorProvider, foreground: ColorProvider, action: Action?) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .defaultWeight()
            .background(background)
            .cornerRadius(16.dp)
            .let { if (action != null) it.clickable(action) else it },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                modifier = GlanceModifier.size(30.dp),
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(foreground),
            )
            if (label != null) {
                Text(
                    text = label,
                    style = TextStyle(color = foreground, fontSize = 11.sp, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun setSettingAction(context: Context, settingId: String, value: String): Action = actionSendBroadcast(
    Intent().apply {
        `package` = context.packageName
        action = DeviceService.ACTION_SET_SETTING_VALUE
        putExtra(DeviceService.INTENT_EXTRA_SETTING_ID, settingId)
        putExtra(DeviceService.INTENT_EXTRA_SETTING_VALUE, value.toValue())
    },
)

private fun connectAction(context: Context, macAddress: String): Action = actionStartService(
    Intent(context, DeviceService::class.java).apply {
        putExtra(DeviceService.INTENT_EXTRA_MAC_ADDRESS, macAddress)
    },
    isForegroundService = true,
)
