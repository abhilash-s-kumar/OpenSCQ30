package com.oppzippy.openscq30.features.autoconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.util.Log
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.oppzippy.openscq30.features.preferences.Preferences
import com.oppzippy.openscq30.lib.bindings.OpenScq30Session
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AutoConnectService : LifecycleService() {
    companion object {
        private const val TAG = "AutoConnectService"
    }

    @Inject
    lateinit var preferences: Preferences

    @Inject
    lateinit var session: OpenScq30Session

    private lateinit var receiver: BluetoothConnectionReceiver

    override fun onCreate() {
        super.onCreate()
        receiver = BluetoothConnectionReceiver(
            preferences = preferences,
            session = session,
            coroutineScope = lifecycleScope,
        )
        application.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
        )
        connectAlreadyConnectedDevices()
    }

    /**
     * Devices may have connected before the receiver was registered (app process started after the earbuds
     * connected, or the service was restarted). Check audio profiles for devices that are already connected.
     */
    @SuppressLint("MissingPermission")
    private fun connectAlreadyConnectedDevices() {
        if (!preferences.autoConnect) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        for (profileId in profiles) {
            try {
                adapter.getProfileProxy(
                    applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            try {
                                proxy.connectedDevices.forEach { device ->
                                    receiver.connectIfPaired(applicationContext, device.address)
                                }
                            } catch (ex: SecurityException) {
                                Log.w(TAG, "missing permission to list connected devices", ex)
                            } finally {
                                adapter.closeProfileProxy(profile, proxy)
                            }
                        }

                        override fun onServiceDisconnected(profile: Int) {}
                    },
                    profileId,
                )
            } catch (ex: SecurityException) {
                Log.w(TAG, "missing permission to get profile proxy", ex)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        application.unregisterReceiver(receiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }
}
