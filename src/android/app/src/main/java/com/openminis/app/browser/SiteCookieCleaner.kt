package com.openminis.app.browser

import android.webkit.CookieManager
import com.openminis.app.logging.AppLogger

/**
 * [ISS-040] Delete one site's cookies from the WebView cookie store.
 *
 * `CookieManager` has no "remove cookies for a domain" call, and the settings
 * sheet used to call `setCookie(domain, "")` — a bare host where a URL is
 * required, and an empty cookie — which WebView ignores, so the delete icon
 * did nothing. The only way to remove a single cookie is to overwrite it with
 * an already-expired one that has the same name, Domain and Path.
 *
 * `getCookie(url)` returns names and values only, not each cookie's Domain or
 * Path, so every plausible combination is written:
 *  - host-only (no Domain attribute) and Domain= the host and each parent
 *    domain (a cookie set by `www.example.com` for `.example.com` is visible
 *    from www and needs `Domain=example.com` to match);
 *  - with and without `Secure`, through https (an insecure write cannot
 *    replace a Secure cookie, and `__Host-` / `__Secure-` names require it);
 *  - Path=/ only. The root URL only reports cookies whose Path is "/", so a
 *    cookie scoped to a deeper path is neither visible nor removable here —
 *    the read-back below then reports it instead of claiming success.
 *
 * Writes that match nothing (or that WebView rejects, such as a Domain that is
 * a public suffix) are no-ops. Afterwards the cookies are read back; anything
 * still present is returned so the caller never fails silently.
 *
 * Blocking: call off the main thread.
 */
object SiteCookieCleaner {

    private const val TAG = "SiteCookieCleaner"
    private const val EXPIRED = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

    data class Result(val host: String, val found: Set<String>, val remaining: Set<String>) {
        val cleared: Boolean get() = remaining.isEmpty()
    }

    /** Cookie names in a `getCookie` header (`"a=1; b=2; flag"`). Pure. */
    internal fun cookieNames(header: String?): Set<String> {
        if (header.isNullOrBlank()) return emptySet()
        return header.split(';')
            .map { it.trim().substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * The hosts a delete for [domain] covers: the host itself, plus the bare
     * host for a `www.` entry (the history records the host the page loaded
     * from, but the site's login cookies often sit on the bare domain). Pure.
     */
    internal fun hostsFor(domain: String): List<String> {
        val host = domain.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return emptyList()
        val bare = host.removePrefix("www.")
        return if (bare != host && bare.contains('.')) listOf(host, bare) else listOf(host)
    }

    /** `Domain=` values that can cover [host]: itself and every parent with at least two labels. Pure. */
    internal fun domainAttributes(host: String): List<String> {
        val labels = host.split('.')
        if (labels.size < 2 || host.all { it.isDigit() || it == '.' }) return listOf(host)
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }

    /** Every expired `Set-Cookie` value written for [name] on [host]. Pure. */
    internal fun expiryCookies(name: String, host: String): List<String> {
        val scopes = listOf("") + domainAttributes(host).map { "; Domain=$it" }
        return scopes.flatMap { scope ->
            listOf(
                "$name=; $EXPIRED; Path=/$scope",
                "$name=; $EXPIRED; Path=/$scope; Secure",
            )
        }
    }

    private fun namesAt(cm: CookieManager, host: String): Set<String> =
        cookieNames(cm.getCookie("https://$host")) + cookieNames(cm.getCookie("http://$host"))

    /** Names of the cookies the root of [domain] (and its bare host) currently sends. */
    fun cookieNamesFor(domain: String, cm: CookieManager = CookieManager.getInstance()): Set<String> =
        hostsFor(domain).flatMap { namesAt(cm, it) }.toSet()

    fun hasCookiesFor(domain: String, cm: CookieManager = CookieManager.getInstance()): Boolean =
        cookieNamesFor(domain, cm).isNotEmpty()

    /**
     * The settings sheet's cookie section: [historyDomains] (the browsing
     * history, still the source of the list) narrowed to the sites that hold
     * cookies right now, so a delete or Clear All visibly removes rows. Pure
     * given [probe].
     */
    internal fun sitesWithCookies(historyDomains: List<String>, probe: (String) -> Boolean): List<String> =
        historyDomains.filter(probe)

    /** What the cookie section shows; read off the main thread. */
    data class SectionState(val hasCookies: Boolean, val domains: List<String>)

    fun loadSection(historyDomains: List<String>, cm: CookieManager = CookieManager.getInstance()): SectionState {
        val hasAny = cm.hasCookies()
        // Nothing stored at all: skip the per-site probes.
        val domains = if (hasAny) sitesWithCookies(historyDomains) { hasCookiesFor(it, cm) } else emptyList()
        return SectionState(hasAny, domains)
    }

    fun clear(domain: String, cm: CookieManager = CookieManager.getInstance()): Result {
        val hosts = hostsFor(domain)
        val found = linkedSetOf<String>()
        for (host in hosts) {
            val names = namesAt(cm, host)
            found += names
            for (name in names) {
                for (value in expiryCookies(name, host)) {
                    cm.setCookie("https://$host", value)
                    cm.setCookie("http://$host", value)
                }
            }
        }
        cm.flush()
        val remaining = cookieNamesFor(domain, cm)
        AppLogger.info(TAG, "clear $domain hosts=$hosts found=${found.size} remaining=${remaining.size} $remaining")
        return Result(domain, found, remaining)
    }
}
