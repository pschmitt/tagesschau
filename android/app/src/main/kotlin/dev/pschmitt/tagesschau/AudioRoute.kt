package dev.pschmitt.tagesschau

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.AttributionSource
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import dev.pschmitt.tagesschau.update.ShizukuInstaller
import dev.pschmitt.tagesschau.update.ShizukuState
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Sends the media audio to the Bluetooth device that started auto-play, also when another one
 * (headphones, a speaker) is connected and still holds the audio. First through [MediaRouter2]
 * (public API, as the system's output switcher does; Android lets apps holding BLUETOOTH_CONNECT
 * and BLUETOOTH_SCAN move their audio to a system Bluetooth route), then, if Shizuku is set up, by
 * making the device the active audio device through the Bluetooth service as the shell user.
 */
object AudioRoute {
    private const val TAG = "AudioRoute"
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"
    /** `BluetoothAdapter.ACTIVE_DEVICE_AUDIO`. */
    private const val ACTIVE_DEVICE_AUDIO = 0
    private const val ATTEMPTS = 10
    private const val INTERVAL_MS = 2000L
    /** Router attempts before Shizuku is tried as well. */
    private const val ROUTER_ONLY_ATTEMPTS = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Moves media to [address] (named [name]). The audio profile connects a moment after the device
     * itself, so this tries again until Android routes media to it, for up to 20 seconds.
     */
    fun preferBluetooth(context: Context, address: String, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val app = context.applicationContext
        val shizuku = ShizukuInstaller.get(app)
        scope.launch {
            // System Bluetooth routes for apps (and the route types) come with Android 14.
            val router = if (Build.VERSION.SDK_INT >= 34) Router(app) else null
            try {
                repeat(ATTEMPTS) { attempt ->
                    if (isOutput(app, address)) {
                        Log.i(TAG, "media plays on $address")
                        return@launch
                    }
                    val transferred =
                        runCatching {
                                Build.VERSION.SDK_INT >= 34 &&
                                    router?.transferTo(address, name) == true
                            }
                            .onFailure { Log.w(TAG, "MediaRouter2 transfer failed", it) }
                            .getOrDefault(false)
                    Log.i(TAG, "MediaRouter2 transfer to $address #$attempt: $transferred")
                    if (router == null || attempt >= ROUTER_ONLY_ATTEMPTS) {
                        shizuku.refresh()
                        if (shizuku.state.value == ShizukuState.READY) {
                            runCatching {
                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        setActive(app, address)
                                    }
                                }
                                .onSuccess {
                                    Log.i(TAG, "setActiveDevice($address) #$attempt: $it")
                                }
                                .onFailure { Log.w(TAG, "setActiveDevice failed", it) }
                        }
                    }
                    delay(INTERVAL_MS)
                }
                Log.w(TAG, "media still not on $address")
            } finally {
                if (Build.VERSION.SDK_INT >= 34) router?.close()
            }
        }
    }

    /** The app's own [MediaRouter2], discovering system audio routes while it is open. */
    @androidx.annotation.RequiresApi(34)
    private class Router(context: Context) {
        private val router = MediaRouter2.getInstance(context)
        private val callback = object : MediaRouter2.RouteCallback() {}

        init {
            router.registerRouteCallback(
                context.mainExecutor,
                callback,
                RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false)
                    .build(),
            )
        }

        /** True if a route for the device exists and the transfer was requested (or done). */
        fun transferTo(address: String, name: String): Boolean {
            val system = router.systemController
            val candidates = router.routes + system.selectableRoutes + system.selectedRoutes
            val route = candidates.firstOrNull { matches(it, address, name) } ?: return false
            if (system.selectedRoutes.any { it.id == route.id }) return true
            router.transferTo(route)
            return true
        }

        fun close() = runCatching { router.unregisterRouteCallback(callback) }

        private fun matches(route: MediaRoute2Info, address: String, name: String): Boolean {
            if (!route.isSystemRoute || route.type !in BLUETOOTH_TYPES) return false
            // The route id carries the address on most versions; the address getter is hidden.
            if (route.id.contains(address, ignoreCase = true)) return true
            val hidden =
                runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            HiddenApiBypass.addHiddenApiExemptions("")
                        MediaRoute2Info::class.java.getMethod("getAddress").invoke(route) as? String
                    }
                    .getOrNull()
            if (hidden != null) return hidden.equals(address, ignoreCase = true)
            return name.isNotEmpty() && route.name.toString() == name
        }

        companion object {
            private val BLUETOOTH_TYPES =
                setOf(
                    MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
                    MediaRoute2Info.TYPE_HEARING_AID,
                    MediaRoute2Info.TYPE_BLE_HEADSET,
                )
        }
    }

    @androidx.annotation.RequiresApi(28)
    /** True if Android sends media to [address] (only the active A2DP device is an output). */
    private fun isOutput(context: Context, address: String): Boolean =
        runCatching {
                context
                    .getSystemService(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                            it.address.equals(address, ignoreCase = true)
                    }
            }
            .getOrDefault(false)

    /**
     * `IBluetooth.setActiveDevice(device, ACTIVE_DEVICE_AUDIO, attributionSource)` on the app's
     * Bluetooth service binder, wrapped so the call runs as the shell user (which holds
     * BLUETOOTH_PRIVILEGED). Android 13 adds a result receiver as the last argument.
     */
    @androidx.annotation.RequiresApi(31)
    @android.annotation.SuppressLint("MissingPermission")
    private fun setActive(context: Context, address: String): Any? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            HiddenApiBypass.addHiddenApiExemptions("")
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("no Bluetooth adapter")
        val device = adapter.getRemoteDevice(address)
        val service =
            BluetoothAdapter::class.java.getDeclaredField("mService").let {
                it.isAccessible = true
                it.get(adapter) as? IInterface ?: error("Bluetooth service not bound")
            }
        val binder: IBinder = ShizukuBinderWrapper(service.asBinder())
        val stub = Class.forName("android.bluetooth.IBluetooth\$Stub")
        val privileged = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method =
            Class.forName("android.bluetooth.IBluetooth").methods.firstOrNull {
                it.name == "setActiveDevice"
            } ?: error("no IBluetooth.setActiveDevice")
        val shell = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        val args =
            method.parameterTypes
                .map { type ->
                    when {
                        type == android.bluetooth.BluetoothDevice::class.java -> device
                        type == Int::class.javaPrimitiveType -> ACTIVE_DEVICE_AUDIO
                        type == AttributionSource::class.java -> shell
                        type.name.endsWith("SynchronousResultReceiver") ->
                            type.getMethod("get").invoke(null)
                        else -> error("unexpected parameter $type")
                    }
                }
                .toTypedArray()
        return try {
            method.invoke(privileged, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
