package com.openminis.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.View
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import java.util.Locale

/**
 * Pre-Tiramisu locale override.
 *
 * On Android 13+ (`LocaleManager.applicationLocales`) the system propagates
 * the app-level locale to every Activity/Service Resources, including the
 * framework strings the system TextView uses to label its selection
 * `ActionMode` ("Cut" / "Copy" / "Paste" / "Select All"). On Android 12
 * and earlier — e.g. a MIUI 13 device from a field report —
 * there is no such system plumbing, so the text-selection ActionMode
 * stays in the device's system language regardless of the user's pick in
 * Settings → Appearance → Language.
 *
 * The fix is to apply the saved language to the base Configuration of
 * every user-facing Activity in `attachBaseContext`, which is what every
 * pre-Tiramisu "in-app language switch" library (e.g. YariKsiyev/AndroidLocalizer)
 * does. We don't need a library — one helper is enough.
 *
 * On Tiramisu+ this is a no-op so we don't double-apply.
 */
object LocaleWrap {

    /**
     * Returns a Context whose Configuration.locales contains the saved
     * language as the primary locale. Safe to call from
     * `Activity.attachBaseContext` (uses raw SharedPreferences on the
     * provided base context — must not depend on Application state).
     *
     * When the saved language is empty (= "Follow system") or we're on
     * API 33+, the language is left alone and only [forceLtr] applies.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return forceLtr(base)
        val code = base
            .getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "")
            .orEmpty()
        if (code.isEmpty()) return forceLtr(base)
        val locale = parseLocale(code) ?: return forceLtr(base)
        val config = Configuration(base.resources.configuration)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = LocaleList(locale)
            LocaleList.setDefault(list)
            config.setLocales(list)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        // [T-fa-locale] setLocales() just derived the layout direction from
        // the locale; keep it left-to-right (see forceLtr).
        config.setLayoutDirection(Locale.ENGLISH)
        return base.createConfigurationContext(config)
    }

    /**
     * [T-fa-locale] Keep the UI laid out left-to-right in every language.
     *
     * Persian is the app's first right-to-left language, and with
     * `supportsRtl="true"` Android and Compose would mirror every screen for
     * it. The screens were never designed or checked mirrored, so a Persian
     * user gets Persian text in today's layout instead. Each paragraph still
     * picks its own text direction from its content, so Persian sentences read
     * right to left inside their boxes.
     *
     * One place covers everything: every Activity wraps its base context
     * here, the Configuration's layout direction is what Views resolve
     * against, and Compose's LocalLayoutDirection is read from it.
     *
     * Only an RTL configuration is touched, so other languages get [base]
     * back unchanged. The override is otherwise empty (Configuration() is
     * fully unset; fontScale is cleared explicitly), so it cannot pin font
     * size, orientation, night mode or screen size at their attach-time
     * values.
     */
    private fun forceLtr(base: Context): Context {
        if (base.resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL) return base
        val ltr = Configuration().apply {
            fontScale = 0f
            setLayoutDirection(Locale.ENGLISH)
        }
        return base.createConfigurationContext(ltr)
    }

    /**
     * Parse a BCP-47-ish language tag the way our settings screen stores
     * it (just `en`, `zh`, `ja`, etc). For `zh-Hant` style tags we fall
     * back to `Locale.forLanguageTag`.
     */
    private fun parseLocale(code: String): Locale? {
        if (code.isBlank()) return null
        return if ('-' in code || '_' in code) {
            Locale.forLanguageTag(code.replace('_', '-'))
        } else {
            Locale(code)
        }
    }
}
