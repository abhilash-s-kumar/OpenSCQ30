package com.oppzippy.openscq30.features.fastpair

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** One battery value from the Fast Pair Message Stream. [percent] is null when the device reports "unknown". */
@Serializable
data class FastPairBatteryValue(val percent: Int, val isCharging: Boolean)

@Serializable
data class FastPairBattery(
    val left: FastPairBatteryValue?,
    val right: FastPairBatteryValue?,
    val case: FastPairBatteryValue?,
)

@Serializable
sealed class FastPairBatteryState {
    /** Not connected to a device, or not tried yet. */
    @Serializable
    data object Inactive : FastPairBatteryState()

    /** Trying to open the Message Stream. */
    @Serializable
    data object Connecting : FastPairBatteryState()

    /**
     * The Message Stream could not be opened. Usually Google Play Services already holds it
     * (only one connection per channel is allowed), or the device doesn't support Fast Pair.
     */
    @Serializable
    data object Busy : FastPairBatteryState()

    /** Message Stream is open. [battery] is null until the first battery message arrives. */
    @Serializable
    data class Active(val battery: FastPairBattery?) : FastPairBatteryState()
}

/**
 * Reads exact (1%) battery levels from the Google Fast Pair Message Stream, an RFCOMM channel
 * that Fast Pair capable earbuds expose. Battery messages are group 0x03, code 0x03, with one
 * byte per component (left, right, case): bit 7 = charging, bits 0-6 = percent, 0x7F = unknown.
 */
object FastPairBatteryReader {
    private const val TAG = "FastPairBatteryReader"
    val MESSAGE_STREAM_UUID: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")

    private const val GROUP_DEVICE_INFORMATION = 0x03
    private const val CODE_BATTERY_UPDATED = 0x03
    private const val MAX_MESSAGE_LENGTH = 1024

    // Each connection attempt makes the earbuds drop whoever currently holds the stream (usually Play Services),
    // even when our attempt then fails. Retrying often just knocks Play Services off without gaining anything, so
    // retry rarely. A fresh attempt also happens on every new earbud connection, since this runs per connection.
    private val RETRY_INTERVAL = 5.minutes

    private val _state = MutableStateFlow<FastPairBatteryState>(FastPairBatteryState.Inactive)
    val state: StateFlow<FastPairBatteryState> = _state.asStateFlow()

    private val runMutex = Mutex()

    /**
     * Keeps the Message Stream open for [macAddress] until the calling coroutine is cancelled,
     * retrying periodically if it is busy. Only one instance runs at a time; extra calls return immediately.
     */
    @SuppressLint("MissingPermission")
    suspend fun run(context: Context, macAddress: String) {
        if (!runMutex.tryLock()) return
        val currentSocket = AtomicReference<BluetoothSocket?>(null)
        // BluetoothSocket.connect() and reads block and ignore coroutine cancellation, so close the socket to unblock them.
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion {
            try {
                currentSocket.get()?.close()
            } catch (_: IOException) {
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null) {
                    _state.value = FastPairBatteryState.Busy
                    return@withContext
                }
                val device = try {
                    adapter.getRemoteDevice(macAddress)
                } catch (ex: IllegalArgumentException) {
                    Log.w(TAG, "invalid mac address", ex)
                    _state.value = FastPairBatteryState.Busy
                    return@withContext
                }

                while (isActive) {
                    _state.value = FastPairBatteryState.Connecting
                    val socket = try {
                        device.createRfcommSocketToServiceRecord(MESSAGE_STREAM_UUID).also {
                            currentSocket.set(it)
                            it.connect()
                        }
                    } catch (ex: IOException) {
                        Log.i(TAG, "message stream unavailable (probably held by Play Services)", ex)
                        null
                    } catch (ex: SecurityException) {
                        Log.w(TAG, "missing bluetooth permission", ex)
                        null
                    }

                    if (socket == null) {
                        currentSocket.getAndSet(null)?.closeQuietly()
                        _state.value = FastPairBatteryState.Busy
                        delay(RETRY_INTERVAL)
                        continue
                    }

                    Log.i(TAG, "message stream opened")
                    _state.value = FastPairBatteryState.Active(null)
                    try {
                        readLoop(socket)
                    } catch (ex: IOException) {
                        Log.i(TAG, "message stream closed", ex)
                    } finally {
                        currentSocket.getAndSet(null)?.closeQuietly()
                    }
                    // Usually means another app (Play Services) took the stream over. Don't fight for it.
                    if (isActive) {
                        _state.value = FastPairBatteryState.Busy
                        delay(RETRY_INTERVAL)
                    }
                }
            }
        } finally {
            cancelHandle.dispose()
            currentSocket.getAndSet(null)?.closeQuietly()
            _state.value = FastPairBatteryState.Inactive
            runMutex.unlock()
        }
    }

    private fun readLoop(socket: BluetoothSocket) {
        val input = DataInputStream(socket.inputStream)
        while (true) {
            val group = input.readUnsignedByte()
            val code = input.readUnsignedByte()
            val length = input.readUnsignedShort()
            if (length > MAX_MESSAGE_LENGTH) throw IOException("message too long: $length")
            val data = ByteArray(length)
            input.readFully(data)

            if (group == GROUP_DEVICE_INFORMATION && code == CODE_BATTERY_UPDATED) {
                val battery = parseBattery(data)
                if (battery != null) {
                    Log.d(TAG, "battery: $battery")
                    _state.value = FastPairBatteryState.Active(battery)
                }
            }
        }
    }

    /** Visible for testing. */
    fun parseBattery(data: ByteArray): FastPairBattery? = when (data.size) {
        1 -> FastPairBattery(left = parseValue(data[0]), right = null, case = null)
        3 -> FastPairBattery(left = parseValue(data[0]), right = parseValue(data[1]), case = parseValue(data[2]))
        else -> null
    }

    private fun parseValue(byte: Byte): FastPairBatteryValue? {
        val value = byte.toInt() and 0xFF
        val percent = value and 0x7F
        if (percent > 100) return null // 0x7F means unknown
        return FastPairBatteryValue(percent = percent, isCharging = (value and 0x80) != 0)
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }
}
