package io.github.elija7580.publit.core

import java.util.Locale

/**
 * Localized text, keyed by the English source text exactly as in the iOS app
 * (`L10n.t("Nearby")`, `L10n.f("%lld min", n)`). Translations come from the iOS string catalog,
 * converted by tools/strings.py into assets/strings/<lang>.json (Java placeholders: %s, %d).
 */
object L10n {
    /** key → translation (String) or plural forms (Map of "one"/"few"/"other" → String). */
    @Volatile private var table: Map<String, Any> = emptyMap()
    @Volatile var locale: Locale = Locale.getDefault()
        private set
    /** Plural category ("one", "few", "many", "other") of a number in the current language. */
    @Volatile private var plural: (Long) -> String = { if (it == 1L) "one" else "other" }

    fun install(table: Map<String, Any>, locale: Locale, plural: (Long) -> String) {
        this.table = table
        this.locale = locale
        this.plural = plural
    }

    private val placeholderLL = Regex("%(\\d+\\$)?ll?d")
    private val placeholderAt = Regex("%(\\d+\\$)?@")

    /** iOS placeholders to Java ones, for keys without a translation. */
    fun javaFormat(s: String): String =
        s.replace(placeholderLL) { "%" + (it.groupValues[1]) + "d" }
            .replace(placeholderAt) { "%" + (it.groupValues[1]) + "s" }

    fun t(key: String): String = when (val v = table[key]) {
        is String -> v
        is Map<*, *> -> (v["other"] as? String) ?: javaFormat(key)
        else -> javaFormat(key)
    }

    fun f(key: String, vararg args: Any): String {
        val format = when (val v = table[key]) {
            is String -> v
            is Map<*, *> -> {
                val n = args.firstOrNull { it is Int || it is Long }?.let { (it as Number).toLong() }
                (n?.let { v[plural(it)] } ?: v["other"]) as? String ?: javaFormat(key)
            }
            else -> javaFormat(key)
        }
        return try { String.format(locale, format, *args) } catch (e: Exception) { format }
    }
}
