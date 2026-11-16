package dev.pschmitt.tagesschau.update

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** How far along the Shizuku install path is. */
enum class ShizukuState {
    /** No Shizuku (or Sui) on the device. */
    UNAVAILABLE,
    /** Shizuku is installed but its service is not running. */
    NOT_RUNNING,
    /** The service runs; this app has not been allowed to use it yet. */
    PERMISSION_NEEDED,
    /** The user denied the permission; only Shizuku can undo it. */
    PERMISSION_DENIED,
    /** Updates install silently through Shizuku. */
    READY,
}

/**
 * Installs updates as the shell user through Shizuku, so they never need a confirmation. Ported
 * from stricknani: the system's `package` binder, wrapped in a [ShizukuBinderWrapper] so calls run
 * with Shizuku's uid, then `IPackageInstaller` and its session binder wrapped the same way. These
 * are hidden APIs, used through reflection after lifting the hidden-API restrictions.
 */
class ShizukuInstaller private constructor(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var deniedByUser = false

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            deniedByUser = result != PackageManager.PERMISSION_GRANTED
            refresh()
        }
        refresh()
    }

    fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted =
            alive &&
                runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                    .getOrDefault(false)
        if (granted) deniedByUser = false
        val denied =
            alive &&
                !granted &&
                (deniedByUser ||
                    runCatching { Shizuku.shouldShowRequestPermissionRationale() }
                        .getOrDefault(false))
        val supported = !alive || runCatching { !Shizuku.isPreV11() }.getOrDefault(true)
        _state.value =
            when {
                alive && !supported -> ShizukuState.UNAVAILABLE
                alive && granted -> ShizukuState.READY
                alive && denied -> ShizukuState.PERMISSION_DENIED
                alive -> ShizukuState.PERMISSION_NEEDED
                managerInstalled() -> ShizukuState.NOT_RUNNING
                else -> ShizukuState.UNAVAILABLE
            }
    }

    fun requestPermission() {
        runCatching {
                if (Shizuku.pingBinder() && !Shizuku.isPreV11())
                    Shizuku.requestPermission(REQUEST_CODE)
            }
            .onFailure { Log.w(TAG, "Shizuku permission request failed", it) }
    }

    fun managerLaunchIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)

    private fun managerInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0) }.isSuccess

    fun install(file: File, resultSender: IntentSender) {
        check(_state.value == ShizukuState.READY) { "Shizuku is not ready" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            HiddenApiBypass.addHiddenApiExemptions("")
        val installerBinder = privileged(packageInstallerService())
        val iInstaller = asInterface("android.content.pm.IPackageInstaller", installerBinder)
        val packageInstaller = newPackageInstaller(iInstaller)
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                addInstallFlags(this, INSTALL_REPLACE_EXISTING)
            }
        val sessionId = packageInstaller.createSession(params)
        try {
            val iSession = call(iInstaller, "openSession", sessionId)
            val sessionBinder = privileged((iSession as IInterface).asBinder())
            val session =
                newSession(
                    asInterface("android.content.pm.IPackageInstallerSession", sessionBinder)
                )
            session.use {
                file.inputStream().use { input ->
                    it.openWrite("update.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        it.fsync(output)
                    }
                }
                it.commit(resultSender)
            }
        } catch (e: Throwable) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw e
        }
    }

    private fun addInstallFlags(params: PackageInstaller.SessionParams, flags: Int) {
        runCatching {
                val field =
                    PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
                field.isAccessible = true
                field.setInt(params, field.getInt(params) or flags)
            }
            .onFailure { Log.w(TAG, "Could not set install flags", it) }
    }

    private fun packageInstallerService(): IBinder {
        val pm =
            asInterface(
                "android.content.pm.IPackageManager",
                privileged(SystemServiceHelper.getSystemService("package")),
            )
        return (call(pm, "getPackageInstaller") as IInterface).asBinder()
    }

    private fun privileged(binder: IBinder): IBinder = ShizukuBinderWrapper(binder)

    private fun newPackageInstaller(iInstaller: Any): PackageInstaller {
        val iface = Class.forName("android.content.pm.IPackageInstaller")
        val constructor =
            PackageInstaller::class.java.declaredConstructors.first {
                it.parameterTypes.firstOrNull() == iface
            }
        var strings = 0
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    iface -> iInstaller
                    String::class.java -> if (strings++ == 0) installerName() else null
                    Int::class.javaPrimitiveType -> Process.myUserHandle().hashCode()
                    else -> error("Unexpected PackageInstaller constructor parameter $type")
                }
            }
        constructor.isAccessible = true
        return unwrap { constructor.newInstance(*args.toTypedArray()) as PackageInstaller }
    }

    private fun newSession(iSession: Any): PackageInstaller.Session {
        val iface = Class.forName("android.content.pm.IPackageInstallerSession")
        val constructor =
            PackageInstaller.Session::class.java.getDeclaredConstructor(iface).apply {
                isAccessible = true
            }
        return unwrap { constructor.newInstance(iSession) }
    }

    private fun installerName(): String =
        if (runCatching { Shizuku.getUid() }.getOrDefault(SHELL_UID) == 0) "root"
        else "com.android.shell"

    private fun asInterface(className: String, binder: IBinder): Any {
        val stub = Class.forName("$className\$Stub")
        return unwrap { stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!! }
    }

    private fun call(target: Any, name: String, vararg args: Any): Any {
        val method =
            target.javaClass.methods.first {
                it.name == name && it.parameterTypes.size == args.size
            }
        return unwrap { method.invoke(target, *args)!! }
    }

    private inline fun <T> unwrap(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }

    companion object {
        private const val TAG = "ShizukuInstaller"
        private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 0x5112
        private const val SHELL_UID = 2000
        private const val INSTALL_REPLACE_EXISTING = 0x00000002

        @Volatile private var instance: ShizukuInstaller? = null

        fun get(context: Context): ShizukuInstaller =
            instance
                ?: synchronized(this) {
                    instance ?: ShizukuInstaller(context.applicationContext).also { instance = it }
                }
    }
}
