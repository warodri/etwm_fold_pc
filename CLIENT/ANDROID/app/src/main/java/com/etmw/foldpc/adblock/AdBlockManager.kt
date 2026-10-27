package com.etmw.foldpc.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AdBlockManager {

    private const val TAG = "AdBlockManager"
    private const val CACHE_VERSION = "etmw-adblock-3"
    private const val CACHE_FILE = "adblock_rules_v3.txt"
    private const val MAX_GLOBAL_SELECTORS = 3000
    private const val MAX_DOMAIN_SELECTORS = 450
    private const val MAX_DOMAIN_PATH_RULES = 120

    private val filterSources = listOf(
        "AdGuard Base" to "https://filters.adtidy.org/extension/chromium/filters/2.txt",
        "AdGuard Mobile Ads" to "https://filters.adtidy.org/extension/chromium/filters/11.txt",
        "AdGuard Annoyances" to "https://filters.adtidy.org/extension/chromium/filters/14.txt",
        "EasyList" to "https://easylist.to/easylist/easylist.txt",
        "EasyPrivacy" to "https://easylist.to/easylist/easyprivacy.txt"
    )

    private val builtInHosts = setOf(
        "2mdn.net",
        "adnxs.com",
        "adsafeprotected.com",
        "adsrvr.org",
        "adservice.google.com",
        "amazon-adsystem.com",
        "bluekai.com",
        "casalemedia.com",
        "criteo.com",
        "criteo.net",
        "demdex.net",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "hotjar.com",
        "inmobi.com",
        "krxd.net",
        "moatads.com",
        "mookie1.com",
        "outbrain.com",
        "pubmatic.com",
        "quantserve.com",
        "rubiconproject.com",
        "scorecardresearch.com",
        "serving-sys.com",
        "smartadserver.com",
        "taboola.com",
        "zedo.com"
    )

    private val builtInSelectors = listOf(
        "ins.adsbygoogle",
        "[id^=\"google_ads_\"]",
        "[id^=\"div-gpt-ad\"]",
        "[id^=\"aswift_\"]",
        "[id^=\"ad-slot-\"]",
        "[data-ad-slot]",
        "[data-google-query-id]",
        "[class~=\"adsbygoogle\"]",
        "[class~=\"advertisement\"]",
        "[class~=\"ad-slot\"]",
        "[class~=\"taboola\"]",
        "[class~=\"outbrain\"]",
        "[aria-label=\"Advertisement\"]",
        "iframe[src*=\"doubleclick.net\"]",
        "iframe[src*=\"googlesyndication.com\"]",
        "iframe[src*=\"adservice.google\"]"
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "etmw-adblock").apply { isDaemon = true }
    }

    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var enabled = true

    @Volatile
    private var initialized = false

    @Volatile
    private var state = RuleState(
        hosts = builtInHosts,
        exceptions = emptySet(),
        pathRules = emptyMap(),
        cosmeticGlobal = builtInSelectors,
        cosmeticDomains = emptyMap(),
        generatedAt = 0L,
        count = builtInHosts.size
    )

    private lateinit var appContext: Context

    fun initialize(context: Context, enabled: Boolean) {
        this.enabled = enabled
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        executor.execute {
            val cached = readCache()
            if (cached != null) {
                state = cached
                if (System.currentTimeMillis() - cached.generatedAt > CACHE_MAX_AGE_MS) {
                    refreshNow()
                }
            } else {
                refreshNow()
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun updateFilters() {
        if (!initialized) return
        executor.execute { refreshNow() }
    }

    fun filterCount(): Int = state.count

    fun shouldIntercept(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!enabled) return null
        if (request.isForMainFrame) return null
        return if (shouldBlock(request.url)) emptyResponse() else null
    }

    fun inject(webView: WebView, url: String?) {
        if (!enabled) return
        val css = cosmeticCssFor(url) ?: return
        val script = "(function(){" +
            "var id='etmw-engine-css';" +
            "var s=document.getElementById(id);" +
            "if(!s){s=document.createElement('style');s.id=id;" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${JSONObject.quote(css)};" +
            "})()"
        webView.evaluateJavascript(script, null)
    }

    private fun shouldBlock(uri: Uri): Boolean {
        val snapshot = state
        if (snapshot.hosts.isEmpty()) return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty()) return false
        if (matchesDomain(host, snapshot.exceptions)) return false
        if (matchesDomain(host, snapshot.hosts)) return true

        val path = buildString {
            append(uri.encodedPath ?: "/")
            uri.encodedQuery?.let {
                append('?')
                append(it)
            }
        }
        var suffix = host
        while (true) {
            val rules = snapshot.pathRules[suffix]
            if (rules != null && rules.any { path.startsWith(it) }) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        return false
    }

    private fun cosmeticCssFor(url: String?): String? {
        val snapshot = state
        val selectors = LinkedHashSet<String>()
        snapshot.cosmeticGlobal.forEach { selectors += it }
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        if (!host.isNullOrEmpty()) {
            var suffix: String = host!!
            while (true) {
                snapshot.cosmeticDomains[suffix]?.forEach { selector ->
                    if (selectors.size < MAX_GLOBAL_SELECTORS + 900) selectors += selector
                }
                val dot = suffix.indexOf('.')
                if (dot < 0) break
                suffix = suffix.substring(dot + 1)
            }
        }
        if (selectors.isEmpty()) return null
        return selectors.joinToString(",\n") + "{display:none !important;}"
    }

    private fun matchesDomain(host: String, domains: Set<String>): Boolean {
        var suffix = host
        while (true) {
            if (domains.contains(suffix)) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) return false
            suffix = suffix.substring(dot + 1)
        }
    }

    private fun refreshNow() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val collector = Collector()
            filterSources.forEach { (name, url) ->
                try {
                    downloadAndParse(name, url, collector)
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not update $name", t)
                }
            }
            val hosts = Collections.unmodifiableSet(collector.hosts + builtInHosts)
            val exceptions = Collections.unmodifiableSet(collector.exceptions.toSet())
            val paths = Collector.freezeMutablePaths(collector.pathRules)
            val globals = (builtInSelectors + collector.globalSelectors)
                .distinct()
                .take(MAX_GLOBAL_SELECTORS)
            val domains = HashMap<String, List<String>>()
            collector.domainSelectors.forEach { (domain, values) ->
                if (values.isNotEmpty()) {
                    domains[domain] = values.distinct().take(MAX_DOMAIN_SELECTORS)
                }
            }
            val fresh = RuleState(
                hosts = hosts,
                exceptions = exceptions,
                pathRules = paths,
                cosmeticGlobal = globals,
                cosmeticDomains = Collections.unmodifiableMap(domains),
                generatedAt = System.currentTimeMillis(),
                count = hosts.size + paths.values.sumOf { it.size }
            )
            state = fresh
            writeCache(fresh)
            Log.i(TAG, "Ad filter update complete: ${fresh.count} network rules")
        } catch (t: Throwable) {
            Log.e(TAG, "Ad filter refresh failed", t)
        } finally {
            refreshing.set(false)
        }
    }

    private fun downloadAndParse(name: String, source: String, collector: Collector) {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15) ETMWFoldPC/1.0"
            )
            setRequestProperty("Accept", "text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..399) {
                throw IllegalStateException("HTTP $code for $name")
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    parseLine(line, collector)
                    line = reader.readLine()
                }
            }
            Log.i(TAG, "$name downloaded")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLine(raw: String, collector: Collector) {
        val line = raw.trim()
        if (line.isEmpty()) return
        if (line.startsWith("!") || line.startsWith("[") || line.startsWith(";")) return
        if (line.startsWith("#") && !line.contains("##")) return

        if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#") ||
            line.contains("#%#") || line.contains("#@?#")
        ) {
            return
        }
        val cosmeticIndex = line.indexOf("##")
        if (cosmeticIndex >= 0) {
            parseCosmetic(line, cosmeticIndex, collector)
            return
        }

        parseHostsLine(line, collector)
        parseNetworkRule(line, collector)
    }

    private fun parseCosmetic(line: String, separator: Int, collector: Collector) {
        val domainPart = line.substring(0, separator).trim()
        val selector = line.substring(separator + 2).trim()
        if (selector.isEmpty() || selector.length > 300) return
        if (selector.contains(":contains", true) ||
            selector.contains(":xpath", true) ||
            selector.contains(":style", true) ||
            selector.contains(":remove", true) ||
            selector.contains(":matches-css", true) ||
            selector.contains(":has-text", true) ||
            selector.contains(":upward", true) ||
            selector.contains(":watch-attr", true) ||
            selector.contains("[-ext-", true) ||
            selector.contains("{")
        ) {
            return
        }
        if (domainPart.isEmpty()) {
            if (collector.globalSelectors.size < MAX_GLOBAL_SELECTORS) {
                collector.globalSelectors += selector
            }
            return
        }
        domainPart.split(',').forEach { rawDomain ->
            val domain = rawDomain.trim().lowercase().removePrefix(".")
            if (domain.isEmpty() || domain.startsWith("~") || domain.contains("*")) return@forEach
            if (!isValidDomain(domain)) return@forEach
            val selectors = collector.domainSelectors.getOrPut(domain) { LinkedHashSet() }
            if (selectors.size < MAX_DOMAIN_SELECTORS) selectors += selector
        }
    }

    private fun parseHostsLine(line: String, collector: Collector) {
        if (line.startsWith("||") || line.startsWith("@@") || line.startsWith("|")) return
        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 2 &&
            (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1")
        ) {
            parts.drop(1).forEach { candidate ->
                val domain = candidate.lowercase().trim('.')
                if (isValidDomain(domain)) collector.hosts += domain
            }
        }
    }

    private fun parseNetworkRule(rawLine: String, collector: Collector) {
        var line = rawLine
        val exception = line.startsWith("@@")
        if (exception) line = line.substring(2)
        val optionIndex = line.indexOf('$')
        if (optionIndex >= 0) line = line.substring(0, optionIndex)
        line = line.trim()
        if (line.isEmpty()) return
        if (line.startsWith("/") && line.endsWith("/")) return

        if (line.startsWith("||")) {
            val rest = line.substring(2)
            val separator = rest.indexOfFirst { it == '^' || it == '/' || it == '|' }
            var domain = if (separator >= 0) rest.substring(0, separator) else rest
            domain = domain.trim().lowercase().removePrefix("*.").trim('.')
            if (!isValidDomain(domain)) return
            val suffix = if (separator >= 0) rest.substring(separator) else ""
            if (exception) {
                collector.exceptions += domain
                return
            }
            if (suffix.startsWith("/")) {
                val path = suffix.trimEnd('|').substringBefore('^')
                if (path.length in 1..200 && !path.contains('*') && !path.contains("$")) {
                    val rules = collector.pathRules.getOrPut(domain) { LinkedHashSet() }
                    if (rules.size < MAX_DOMAIN_PATH_RULES) rules += path
                    return
                }
            }
            collector.hosts += domain
            return
        }

        if (line.startsWith("|http://") || line.startsWith("|https://")) {
            val value = line.substring(1).trimEnd('|').substringBefore('^')
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
            val host = uri.host?.lowercase() ?: return
            if (!isValidDomain(host)) return
            if (exception) {
                collector.exceptions += host
                return
            }
            collector.hosts += host
            return
        }

        if (!line.contains("/") && !line.contains("*") && !line.contains("^") &&
            !line.contains("?") && !line.contains(":") && !line.contains("|")
        ) {
            val domain = line.lowercase().trim('.')
            if (isValidDomain(domain) && domain.contains('.')) {
                if (exception) collector.exceptions += domain else collector.hosts += domain
            }
        }
    }

    private fun isValidDomain(value: String): Boolean {
        if (value.isEmpty() || value.length > 253) return false
        if (!value.contains('.')) return false
        if (value.startsWith(".") || value.endsWith(".")) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    private fun emptyResponse(): WebResourceResponse {
        val response = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
        response.setStatusCodeAndReasonPhrase(204, "No Content")
        response.setResponseHeaders(mapOf("Cache-Control" to "no-store"))
        return response
    }

    private fun writeCache(snapshot: RuleState) {
        if (!::appContext.isInitialized) return
        val target = File(appContext.filesDir, CACHE_FILE)
        val temp = File(appContext.filesDir, "$CACHE_FILE.tmp")
        try {
            BufferedWriter(temp.writer(Charsets.UTF_8)).use { writer ->
                writer.write(CACHE_VERSION)
                writer.newLine()
                snapshot.hosts.forEach {
                    writer.write("H ")
                    writer.write(it)
                    writer.newLine()
                }
                snapshot.exceptions.forEach {
                    writer.write("E ")
                    writer.write(it)
                    writer.newLine()
                }
                snapshot.pathRules.forEach { (domain, rules) ->
                    rules.forEach { path ->
                        writer.write("P ")
                        writer.write(domain)
                        writer.write('\t'.code)
                        writer.write(path)
                        writer.newLine()
                    }
                }
                snapshot.cosmeticGlobal.forEach {
                    writer.write("G ")
                    writer.write(it)
                    writer.newLine()
                }
                snapshot.cosmeticDomains.forEach { (domain, selectors) ->
                    selectors.forEach {
                        writer.write("C ")
                        writer.write(domain)
                        writer.write('\t'.code)
                        writer.write(it)
                        writer.newLine()
                    }
                }
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not write ad filter cache", t)
        }
    }

    private fun readCache(): RuleState? {
        if (!::appContext.isInitialized) return null
        val file = File(appContext.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return try {
            val hosts = HashSet<String>(builtInHosts)
            val exceptions = HashSet<String>()
            val paths = HashMap<String, MutableSet<String>>()
            val globalSelectors = ArrayList<String>(builtInSelectors)
            val domainSelectors = HashMap<String, MutableSet<String>>()
            BufferedReader(file.reader(Charsets.UTF_8)).use { reader ->
                val header = reader.readLine() ?: return null
                if (header != CACHE_VERSION) return null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.length < 3) continue
                    when (line[0]) {
                        'H' -> if (line[1] == ' ') hosts += line.substring(2)
                        'E' -> if (line[1] == ' ') exceptions += line.substring(2)
                        'P' -> {
                            val tab = line.indexOf('\t')
                            if (tab > 2) {
                                val domain = line.substring(2, tab)
                                val path = line.substring(tab + 1)
                                if (path.length in 1..200) {
                                    paths.getOrPut(domain) { LinkedHashSet() } += path
                                }
                            }
                        }
                        'G' -> if (line[1] == ' ') {
                            if (globalSelectors.size < MAX_GLOBAL_SELECTORS) {
                                globalSelectors += line.substring(2)
                            }
                        }
                        'C' -> {
                            val tab = line.indexOf('\t')
                            if (tab > 2) {
                                val domain = line.substring(2, tab)
                                val selector = line.substring(tab + 1)
                                if (selector.length <= 300) {
                                    domainSelectors.getOrPut(domain) { LinkedHashSet() } += selector
                                }
                            }
                        }
                    }
                }
            }
            val frozenPaths = Collector.freezeMutablePaths(paths)
            val frozenDomains = HashMap<String, List<String>>()
            domainSelectors.forEach { (domain, values) ->
                if (values.isNotEmpty()) {
                    frozenDomains[domain] = values.distinct().take(MAX_DOMAIN_SELECTORS)
                }
            }
            RuleState(
                hosts = Collections.unmodifiableSet(hosts),
                exceptions = Collections.unmodifiableSet(exceptions),
                pathRules = frozenPaths,
                cosmeticGlobal = globalSelectors.distinct().take(MAX_GLOBAL_SELECTORS),
                cosmeticDomains = Collections.unmodifiableMap(frozenDomains),
                generatedAt = file.lastModified(),
                count = hosts.size + frozenPaths.values.sumOf { it.size }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read ad filter cache", t)
            null
        }
    }

    private data class RuleState(
        val hosts: Set<String>,
        val exceptions: Set<String>,
        val pathRules: Map<String, List<String>>,
        val cosmeticGlobal: List<String>,
        val cosmeticDomains: Map<String, List<String>>,
        val generatedAt: Long,
        val count: Int
    )

    private class Collector {
        val hosts = HashSet<String>()
        val exceptions = HashSet<String>()
        val pathRules = HashMap<String, MutableSet<String>>()
        val globalSelectors = LinkedHashSet<String>()
        val domainSelectors = HashMap<String, MutableSet<String>>()

        companion object {
            fun freezeMutablePaths(input: Map<String, MutableSet<String>>): Map<String, List<String>> {
                val result = HashMap<String, List<String>>(input.size)
                input.forEach { (domain, values) ->
                    if (values.isNotEmpty()) result[domain] = values.toList()
                }
                return Collections.unmodifiableMap(result)
            }
        }
    }

    private const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
}
