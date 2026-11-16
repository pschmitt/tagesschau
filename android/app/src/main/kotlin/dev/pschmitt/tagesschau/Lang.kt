package dev.pschmitt.tagesschau

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The app's language: the system's (default) or German, English, French. Android 13+ keeps the
 * choice itself (it also shows in the system's per-app language settings); older versions get it
 * from [wrap] in each activity's attachBaseContext.
 */
object Lang {
    /** "" is the system language. */
    val CODES = listOf("", "de", "en", "fr")

    private fun prefs(context: Context) = context.getSharedPreferences("lang", Context.MODE_PRIVATE)

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) "" else locales[0].language.takeIf { it in CODES }.orEmpty()
        } else prefs(context).getString("code", "").orEmpty()

    fun set(activity: Activity, code: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (code.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(code)
        } else {
            prefs(activity).edit { putString("code", code) }
            activity.recreate()
        }
    }

    /** Before Android 13: the chosen language for an activity's resources. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val code = prefs(base).getString("code", "").orEmpty()
        if (code.isEmpty()) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}
