package com.oppzippy.openscq30.features.soundcoredevice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oppzippy.openscq30.R
import com.oppzippy.openscq30.features.soundcoredevice.connectionBackends
import com.oppzippy.openscq30.features.statusnotification.storage.FeaturedSettingSlotDao
import com.oppzippy.openscq30.features.statusnotification.storage.QuickPresetSlotDao
import com.oppzippy.openscq30.lib.bindings.OpenScq30Exception
import com.oppzippy.openscq30.lib.bindings.OpenScq30Session
import com.oppzippy.openscq30.lib.bindings.SettingIdValuePair
import com.oppzippy.openscq30.lib.wrapper.Value
import com.oppzippy.openscq30.widget.BudsWidget
import com.oppzippy.openscq30.widget.SettingWidget
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.oppzippy.openscq30.features.autoconnect.AutoConnectService
import com.oppzippy.openscq30.features.fastpair.FastPairBatteryReader

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class DeviceService : LifecycleService() {
    companion object {
        private const val TAG = "DeviceService"

        /** Intent extra for setting mac address when launching service */
        const val INTENT_EXTRA_MAC_ADDRESS = "com.oppzippy.openscq30.macAddress"

        /** Set by auto connect. Auto connections never replace an existing connection, and retry on failure. */
        const val INTENT_EXTRA_AUTO_CONNECT = "com.oppzippy.openscq30.autoConnect"
        private const val AUTO_CONNECT_ATTEMPTS = 4
        private const val AUTO_CONNECT_RETRY_DELAY_MS = 2000L
        private const val MANUAL_CONNECT_ATTEMPTS = 3
        private const val MANUAL_CONNECT_RETRY_DELAY_MS = 1000L

        const val NOTIFICATION_CHANNEL_ID = "com.oppzippy.openscq30.notification.DeviceServiceChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_QUICK_PRESET = "com.oppzippy.openscq30.broadcast.QuickPreset"
        const val ACTION_DISCONNECT = "com.oppzippy.openscq30.broadcast.Disconnect"
        const val ACTION_SEND_NOTIFICATION = "com.oppzippy.openscq30.broadcast.SendNotification"
        const val ACTION_SET_SETTING_VALUE = "com.oppzippy.openscq30.broadcast.SetSettingValue"
        const val ACTION_UPDATE_WIDGET = "com.oppzippy.openscq30.broadcast.UPDATE_WIDGET"
        const val EXTRA_BUDS_WIDGET_ONLY = "com.oppzippy.openscq30.budsWidgetOnly"

        private val ACTIONS = listOf(
            ACTION_DISCONNECT,
            ACTION_QUICK_PRESET,
            ACTION_SEND_NOTIFICATION,
            ACTION_SET_SETTING_VALUE,
            ACTION_UPDATE_WIDGET,
        )

        const val INTENT_EXTRA_PRESET_SLOT_INDEX = "com.oppzippy.openscq30.presetNumber"
        const val INTENT_EXTRA_SETTING_ID = "com.oppzippy.openscq30.settingId"
        const val INTENT_EXTRA_SETTING_VALUE = "com.oppzippy.openscq30.settingValue"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
    }

    @Inject
    lateinit var session: OpenScq30Session

    @Inject
    lateinit var notificationBuilder: NotificationBuilder

    @Inject
    lateinit var quickPresetSlotDao: QuickPresetSlotDao

    @Inject
    lateinit var featuredSettingSlotDao: FeaturedSettingSlotDao

    /** Mac address of the device we are connecting/connected to, used for the Fast Pair battery reader. */
    @Volatile
    private var connectedMacAddress: String? = null

    val connectionStatusFlow: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.AwaitingConnection)

    private var quickPresetNames = connectionStatusFlow.flatMapLatest { connectionStatus ->
        if (connectionStatus is ConnectionStatus.Connected) {
            val device = connectionStatus.deviceManager.device
            quickPresetSlotDao.allNames(device.model())
        } else {
            emptyFlow()
        }
    }.stateIn(lifecycleScope, SharingStarted.Lazily, emptyList())
    private var featuredSettingIds = connectionStatusFlow.flatMapLatest { connectionStatus ->
        if (connectionStatus is ConnectionStatus.Connected) {
            val device = connectionStatus.deviceManager.device
            featuredSettingSlotDao.allSettingIds(device.model())
        } else {
            emptyFlow()
        }
    }.stateIn(lifecycleScope, SharingStarted.Lazily, emptyList())

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DISCONNECT -> {
                    Log.i(TAG, "got disconnect request, initiating stop service")
                    stopSelf()
                }

                ACTION_QUICK_PRESET -> {
                    val presetIndex = intent.getIntExtra(INTENT_EXTRA_PRESET_SLOT_INDEX, Int.MIN_VALUE)
                    if (presetIndex == Int.MIN_VALUE) {
                        Log.e(TAG, "got ACTION_QUICK_PRESET, but INTENT_EXTRA_PRESET_SLOT_INDEX is not specified")
                        return
                    }
                    val presetName = quickPresetNames.value.getOrNull(presetIndex) ?: return
                    lifecycleScope.launch {
                        connectionStatusFlow.value.let {
                            if (it is ConnectionStatus.Connected) {
                                val device = it.deviceManager.device
                                session.quickPresetHandler().use { quickPresetHandler ->
                                    try {
                                        quickPresetHandler.activate(device, presetName)
                                    } catch (ex: OpenScq30Exception) {
                                        Log.e(TAG, "error activating quick preset $presetName", ex)
                                    }
                                }
                            }
                        }
                    }
                }

                ACTION_SEND_NOTIFICATION -> {
                    sendNotification()
                }

                ACTION_SET_SETTING_VALUE -> {
                    lifecycleScope.launch {
                        val settingId = intent.getStringExtra(INTENT_EXTRA_SETTING_ID)
                        val value =
                            IntentCompat.getParcelableExtra(intent, INTENT_EXTRA_SETTING_VALUE, Value::class.java)
                        if (settingId != null && value != null) {
                            connectionStatusFlow.value.let {
                                if (it is ConnectionStatus.Connected) {
                                    val device = it.deviceManager.device
                                    try {
                                        device.setSettingValues(listOf(SettingIdValuePair(settingId, value)))
                                    } catch (ex: IllegalStateException) {
                                        Log.w(TAG, "device was closed, not setting values", ex)
                                    }
                                }
                            }
                        } else {
                            Log.e(TAG, "$ACTION_SET_SETTING_VALUE requires settingId and value to be set")
                        }
                    }
                }

                ACTION_UPDATE_WIDGET -> {
                    // Earbuds widgets need no configuration, so refresh them all on any update request
                    lifecycleScope.launch {
                        BudsWidget().updateAll(applicationContext, session, connectionStatusFlow.value)
                    }
                    val appWidgetId =
                        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                    if (intent.getBooleanExtra(EXTRA_BUDS_WIDGET_ONLY, false)) {
                        // nothing else to do
                    } else if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        val manager = GlanceAppWidgetManager(applicationContext)
                        val glanceId = manager.getGlanceIdBy(appWidgetId)
                        lifecycleScope.launch {
                            SettingWidget().updateConnectionStatus(
                                applicationContext,
                                session,
                                connectionStatusFlow.value,
                                glanceId,
                            )
                        }
                    } else {
                        Log.e(TAG, "tried to update widget without specifying EXTRA_APPWIDGET_ID")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true

        lifecycleScope.launch { quickPresetNames.collectLatest { sendNotification() } }
        lifecycleScope.launch { featuredSettingIds.collectLatest { sendNotification() } }

        ContextCompat.registerReceiver(
            this,
            broadcastReceiver,
            IntentFilter().apply { ACTIONS.forEach { addAction(it) } },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val isAutoConnect = intent?.getBooleanExtra(INTENT_EXTRA_AUTO_CONNECT, false) ?: false
        val requestedMacAddress = intent?.getStringExtra(INTENT_EXTRA_MAC_ADDRESS)
        val currentStatus = connectionStatusFlow.value
        val isBusy = currentStatus is ConnectionStatus.Connecting || currentStatus is ConnectionStatus.Connected
        Log.i(TAG, "start: mac=$requestedMacAddress auto=$isAutoConnect current=$currentStatus")
        if (isAutoConnect && isBusy) {
            // Never let an automatic connection replace the device that is already connected (or connecting).
            Log.i(TAG, "ignoring auto connect to $requestedMacAddress, already busy with $connectedMacAddress")
        } else {
            requestedMacAddress?.let { macAddress -> connect(macAddress, isAutoConnect) }
        }

        // Only register these once, even if the service is started multiple times
        if (!collectorsStarted) {
            collectorsStarted = true
            startCollectors()
        }

        return START_REDELIVER_INTENT
    }

    @Volatile
    private var collectorsStarted = false

    /** True when the device disconnected by itself, as opposed to the user closing the connection. */
    @Volatile
    private var deviceDropped = false

    private fun requestAutoConnectRescan(excludeMacAddress: String?) {
        if (!com.oppzippy.openscq30.features.preferences.Preferences(applicationContext).autoConnect) {
            Log.i(TAG, "handover: auto connect is disabled, not rescanning")
            return
        }
        Log.i(TAG, "handover: requesting rescan excluding $excludeMacAddress")
        try {
            startService(
                Intent(applicationContext, AutoConnectService::class.java).apply {
                    action = AutoConnectService.ACTION_RESCAN
                    putExtra(AutoConnectService.EXTRA_EXCLUDE_MAC_ADDRESS, excludeMacAddress)
                },
            )
        } catch (ex: IllegalStateException) {
            Log.w(TAG, "couldn't request auto connect rescan", ex)
        }
    }

    private fun connect(macAddress: String, isAutoConnect: Boolean) {
        connectedMacAddress = macAddress
        val previous = connectionStatusFlow.value as? ConnectionStatus.Connected
        // Switch state first so the disconnect watcher stops watching the old device before it is closed
        connectionStatusFlow.value = ConnectionStatus.Connecting(macAddress)
        previous?.deviceManager?.close()
        lifecycleScope.launch {
            // Right after the earbuds connect, their control channel often isn't ready yet, so automatic
            // connections retry a few times before giving up.
            // Manual connections retry too: the first RFCOMM socket to a service often fails on Android.
            val attempts = if (isAutoConnect) AUTO_CONNECT_ATTEMPTS else MANUAL_CONNECT_ATTEMPTS
            for (attempt in 1..attempts) {
                try {
                    val device = session.connectWithBackends(
                        connectionBackends(applicationContext, lifecycleScope),
                        macAddress,
                    )
                    connectionStatusFlow.value = ConnectionStatus.Connected(DeviceConnectionManager(device))
                    return@launch
                } catch (ex: OpenScq30Exception) {
                    Log.w(TAG, "error connecting to device (attempt $attempt/$attempts)", ex)
                    if (attempt < attempts) {
                        delay(if (isAutoConnect) AUTO_CONNECT_RETRY_DELAY_MS * attempt else MANUAL_CONNECT_RETRY_DELAY_MS)
                    }
                }
            }
            if (!isAutoConnect) {
                Toast.makeText(applicationContext, R.string.error_connecting, Toast.LENGTH_SHORT).show()
            }
            connectionStatusFlow.value = ConnectionStatus.Disconnected
        }
    }

    private fun startCollectors() {
        // when we are connected to a device that becomes disconnected, update our connection status to disconnected
        lifecycleScope.launch {
            connectionStatusFlow.collectLatest { connectionStatus ->
                if (connectionStatus is ConnectionStatus.Connected) {
                    connectionStatus.deviceManager.connectionStatusFlow.collectLatest { deviceConnectionStatus ->
                        if (deviceConnectionStatus ==
                            com.oppzippy.openscq30.lib.bindings.ConnectionStatus.Disconnected
                        ) {
                            Log.d(TAG, "device disconnected")
                            deviceDropped = true
                            connectionStatusFlow.value = ConnectionStatus.Disconnected
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            connectionStatusFlow.first { it == ConnectionStatus.Disconnected }
            Log.i(TAG, "handover: disconnected, deviceDropped=$deviceDropped, mac=$connectedMacAddress")
            if (deviceDropped) {
                // The earbuds went away on their own (not the user pressing back), so if another paired device is
                // still connected, hand over to it. Its own connect event already fired while this one was active.
                requestAutoConnectRescan(excludeMacAddress = connectedMacAddress)
            }
            stopSelf()
        }
        lifecycleScope.launch {
            connectionStatusFlow.collectLatest {
                if (it is ConnectionStatus.Connected) {
                    it.deviceManager.watchForChangeNotification.debounce(500.milliseconds).collectLatest {
                        sendNotification()
                    }
                }
            }
        }
        lifecycleScope.launch {
            val widget = SettingWidget()
            val budsWidget = BudsWidget()
            connectionStatusFlow.collectLatest { connectionStatus ->
                widget.updateAllConnectionStatus(applicationContext, session, connectionStatus)
                budsWidget.updateAll(applicationContext, session, connectionStatus)
                if (connectionStatus is ConnectionStatus.Connected) {
                    merge(
                        connectionStatus.deviceManager.watchForChangeNotification.map { },
                        FastPairBatteryReader.state.map { },
                    ).collectLatest {
                        widget.updateAllConnectionStatus(applicationContext, session, connectionStatus)
                        budsWidget.updateAll(applicationContext, session, connectionStatus)
                    }
                }
            }
        }

        // Exact battery levels from the Google Fast Pair Message Stream, when it isn't held by Play Services
        lifecycleScope.launch {
            connectionStatusFlow.collectLatest { connectionStatus ->
                val macAddress = connectedMacAddress
                // Only Soundcore devices need this: others (e.g. Nothing) report exact battery over their own protocol,
                // so there's no reason to compete with Play Services for the Fast Pair channel.
                val needsFastPair = connectionStatus is ConnectionStatus.Connected &&
                    connectionStatus.deviceManager.device.model().startsWith("Soundcore")
                if (needsFastPair && macAddress != null) {
                    FastPairBatteryReader.run(applicationContext, macAddress)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "stopping service")
        unregisterReceiver(broadcastReceiver)
        cancelNotification()
        connectionStatusFlow.value.let {
            if (it is ConnectionStatus.Connected) {
                connectionStatusFlow.value = ConnectionStatus.Disconnected
                it.deviceManager.close()
            }
        }
        MainScope().launch {
            SettingWidget().updateAllConnectionStatus(applicationContext, session, ConnectionStatus.Disconnected)
            BudsWidget().updateAll(applicationContext, session, ConnectionStatus.Disconnected)
        }
        _isRunning.value = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Device Connection Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.enableVibration(false)
        channel.enableLights(false)
        channel.setSound(null, null)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun sendNotification() {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun cancelNotification() {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(): Notification = notificationBuilder(
        status = connectionStatusFlow.value,
        quickPresetNames = quickPresetNames.value,
        featuredSettingIds = featuredSettingIds.value,
    )

    private val binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class MyBinder : Binder() {
        fun getService(): DeviceService = this@DeviceService
    }
}
