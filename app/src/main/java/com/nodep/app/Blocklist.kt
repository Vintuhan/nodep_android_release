package com.nodep.app

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Чёрный список доменов (assets/blocklist.txt) + бренды + package-фрагменты.
 */
object Blocklist {
    private val domains = ConcurrentHashMap.newKeySet<String>()

    fun load(context: Context) {
        domains.clear()
        try {
            context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val d = line.trim().lowercase().removePrefix("www.")
                    if (d.isNotEmpty() && !d.startsWith("#") && '.' in d) domains.add(d)
                }
            }
        } catch (_: Exception) {}
    }

    fun isBlockedPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return PACKAGE_FRAGMENTS.any { p.contains(it) }
    }

    /**
     * @return строка-причина для заглушки или null
     */
    fun matchText(raw: String): String? {
        val t = raw.lowercase().trim()
        if (t.length < 3) return null

        // URL / host
        val host = extractHost(t)
        if (host != null && isBlockedHost(host)) return host

        // ключевые слова в адресной строке / заголовке
        for (w in KEYWORDS) {
            if (t.contains(w)) return w
        }

        val flat = t.replace(Regex("[^a-z0-9а-яё]"), "")
        for (b in BRANDS) {
            if (flat.contains(b)) return b
        }
        return null
    }

    fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.").trimEnd('.')
        if (h.isEmpty()) return false
        if (domains.contains(h)) return true
        val parts = h.split('.')
        for (i in 0 until parts.size - 1) {
            val suffix = parts.subList(i, parts.size).joinToString(".")
            if (domains.contains(suffix)) return true
        }
        val flat = h.replace(Regex("[^a-z0-9]"), "")
        return BRANDS.any { flat.contains(it) }
    }

    private fun extractHost(s: String): String? {
        val m = Regex(
            """(?:https?://)?(?:www\.)?([a-z0-9](?:[a-z0-9\-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?)+)""",
            RegexOption.IGNORE_CASE
        ).find(s) ?: return null
        return m.groupValues[1].lowercase()
    }

    fun size() = domains.size

    private val KEYWORDS = listOf(
        "casino", "казино", "slots", "слоты", "poker", "покер",
        "betting", "букмекер", "1xbet", "mostbet", "melbet",
        "case battle", "casebattle", "stake.com", "vavada"
    )

    private val BRANDS = listOf(
        "1win", "1xbet", "1xstavka", "melbet", "mostbet", "parimatch", "fonbet",
        "winline", "vavada", "pokerdom", "casebattle", "hellcase", "keydrop",
        "csgoempire", "csgoroll", "gamdom", "rollbit", "roobet", "duelbits",
        "bcgame", "stake", "jabka", "musor", "datdrop", "farmskins", "skinclub",
        "pinup", "gizbo", "starda", "dragonmoney", "joycasino", "azino777",
        "leonbet", "betboom", "csgofast", "packdraw", "hypedrop"
    )

    private val PACKAGE_FRAGMENTS = listOf(
        "1xbet", "1xstavka", "melbet", "mostbet", "parimatch", "fonbet", "winline",
        "vavada", "pokerdom", "pinup", "leon", "betcity", "olimp", "baltbet",
        "stake", "rollbit", "roobet", "casino", "betting", "pokerstars",
        "com.casino", "com.poker"
    )
}
