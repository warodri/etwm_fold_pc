package com.etmw.foldpc.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.etmw.foldpc.R
import com.etmw.foldpc.SettingsRepository
import com.etmw.foldpc.adblock.AdBlockManager
import com.etmw.foldpc.ui.dp
import com.etmw.foldpc.ui.iconView
import com.etmw.foldpc.ui.textView
import org.json.JSONObject

class BrowserController(
    private val activity: Activity,
    private val webStack: FrameLayout,
    private val settings: SettingsRepository
) {

    interface Listener {
        fun onTabChanged(tab: BrowserTab)
        fun onTabsChanged(count: Int)
        fun onNavigationChanged(canGoBack: Boolean, canGoForward: Boolean)
        fun onProgressChanged(progress: Int, loading: Boolean)
        fun onWebInteraction()
        fun onEditableFocused()
        fun onFullscreenChanged(entering: Boolean, view: View?)
        fun onShowFileChooser(
            webView: WebView,
            callback: android.webkit.ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean
    }

    var listener: Listener? = null

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTab: BrowserTab? = null
    private var nextId = 1L
    private var tabOverlay: View? = null

    private val webHost = webStack.parent as? FrameLayout ?: webStack
    private val defaultUserAgent: String = WebSettings.getDefaultUserAgent(activity)
    private val chromeVersion: String =
        Regex("Chrome/([0-9.]+)").find(defaultUserAgent)?.groupValues?.get(1) ?: "124.0.0.0"
    private val desktopUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var siteTextZoom = 100

    val isTabSwitcherVisible: Boolean
        get() = tabOverlay != null

    fun tabCount(): Int = tabs.size

    fun tabs(): List<BrowserTab> = tabs.toList()

    fun currentTab(): BrowserTab? = currentTab

    fun currentUrl(): String = currentTab?.url.orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(url: String? = null, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, NoImeWebView(activity))
        tab.webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        tab.webView.visibility = View.GONE
        tab.webView.setBackgroundColor(Color.rgb(9, 11, 14))
        tab.webView.overScrollMode = View.OVER_SCROLL_NEVER
        tab.webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        tab.webView.isFocusable = true
        tab.webView.isFocusableInTouchMode = true

        tab.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            safeBrowsingEnabled = true
            userAgentString = if (settings.desktopMode) desktopUserAgent else defaultUserAgent
            textZoom = siteTextZoom
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, true)
        tab.webView.addJavascriptInterface(NativeBridge(), "EtmwNative")

        tab.webView.webViewClient = buildWebViewClient(tab)
        tab.webView.webChromeClient = buildChromeClient(tab)
        tab.webView.setDownloadListener(buildDownloadListener())

        tab.webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                listener?.onWebInteraction()
            }
            false
        }

        tabs += tab
        webStack.addView(tab.webView)
        if (select) selectTab(tab)
        if (url != null) {
            loadUserInput(tab, url)
        }
        listener?.onTabsChanged(tabs.size)
        return tab
    }

    fun selectTab(tab: BrowserTab) {
        if (!tabs.contains(tab)) return
        currentTab?.webView?.visibility = View.GONE
        currentTab = tab
        tab.webView.visibility = View.VISIBLE
        tab.webView.requestFocus()
        listener?.onTabChanged(tab)
        listener?.onNavigationChanged(canGoBack(), canGoForward())
        if (tab.isLoading) {
            listener?.onProgressChanged(0, true)
        } else {
            listener?.onProgressChanged(100, false)
        }
    }

    fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasCurrent = currentTab == tab
        if (wasCurrent) {
            currentTab = null
        }
        tabs.remove(tab)
        webStack.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            newTab(settings.homeUrl)
        } else if (wasCurrent) {
            selectTab(tabs[index.coerceAtMost(tabs.lastIndex)])
        } else {
            listener?.onTabsChanged(tabs.size)
        }
        listener?.onTabsChanged(tabs.size)
    }

    fun closeCurrentTab() {
        currentTab?.let { closeTab(it) }
    }

    fun goBack() {
        val tab = currentTab ?: return
        if (tab.webView.canGoBack()) tab.webView.goBack()
    }

    fun goForward() {
        val tab = currentTab ?: return
        if (tab.webView.canGoForward()) tab.webView.goForward()
    }

    fun reload() {
        currentTab?.webView?.reload()
    }

    fun home() {
        loadUserInput(settings.homeUrl)
    }

    fun loadUserInput(input: String) {
        val tab = currentTab ?: newTab(settings.homeUrl)
        loadUserInput(tab, input)
    }

    private fun loadUserInput(tab: BrowserTab, input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
        if (scheme != null && scheme !in WEB_SCHEMES) {
            openExternal(value)
            return
        }
        val url = normalizeUserInput(value)
        tab.url = url
        if (tab == currentTab) listener?.onTabChanged(tab)
        tab.isLoading = true
        tab.webView.loadUrl(url)
    }

    fun normalizeUserInput(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return settings.homeUrl
        val lower = value.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:")
        ) {
            return value
        }
        if (lower.startsWith("javascript:")) {
            return settings.searchUrlFor(value)
        }
        if (lower.startsWith("localhost") || IP_ADDRESS.matches(value)) {
            return "http://$value"
        }
        if (value.contains(" ") || !value.contains(".")) {
            return settings.searchUrlFor(value)
        }
        return "https://$value"
    }

    fun canGoBack(): Boolean = currentTab?.webView?.canGoBack() == true

    fun canGoForward(): Boolean = currentTab?.webView?.canGoForward() == true

    fun requestWebFocus() {
        currentTab?.webView?.requestFocus()
    }

    fun insertText(text: String) {
        if (text.isEmpty()) return
        val webView = currentTab?.webView ?: return
        val quoted = JSONObject.quote(text)
        val js = "(function(){try{return !!(window.__etmw && window.__etmw.insertText($quoted));}" +
            "catch(e){return false;}})()"
        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                dispatchTextFallback(webView, text)
            }
        }
    }

    fun backspace() {
        val webView = currentTab?.webView ?: return
        val js = "(function(){try{return !!(window.__etmw && window.__etmw.backspace());}" +
            "catch(e){return false;}})()"
        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                dispatchKey(webView, KeyEvent.KEYCODE_DEL)
            }
        }
    }

    fun forwardDelete() {
        val webView = currentTab?.webView ?: return
        val js = "(function(){try{return !!(window.__etmw && window.__etmw.forwardDelete());}" +
            "catch(e){return false;}})()"
        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                dispatchKey(webView, KeyEvent.KEYCODE_FORWARD_DEL)
            }
        }
    }

    fun pressEnter() {
        val webView = currentTab?.webView ?: return
        val js = "(function(){try{return !!(window.__etmw && window.__etmw.enter());}" +
            "catch(e){return false;}})()"
        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                dispatchKey(webView, KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    fun pressTab() {
        val webView = currentTab?.webView ?: return
        dispatchKey(webView, KeyEvent.KEYCODE_TAB)
    }

    fun pressEscape() {
        val webView = currentTab?.webView ?: return
        dispatchKey(webView, KeyEvent.KEYCODE_ESCAPE)
    }

    fun arrow(direction: String, shift: Boolean, word: Boolean) {
        val webView = currentTab?.webView ?: return
        val js = "(function(){try{return !!(window.__etmw && window.__etmw.arrow('" +
            direction + "'," + shift + "," + word + "));}catch(e){return false;}})()"
        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                val keyCode = when (direction) {
                    "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                    "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    "up" -> KeyEvent.KEYCODE_DPAD_UP
                    else -> KeyEvent.KEYCODE_DPAD_DOWN
                }
                var meta = 0
                if (shift) meta = meta or KeyEvent.META_SHIFT_ON
                if (word) meta = meta or KeyEvent.META_ALT_ON
                dispatchKey(webView, keyCode, meta)
            }
        }
    }

    fun selectAll() {
        executeJavascript("(function(){try{return !!(window.__etmw && window.__etmw.selectAll());}" +
            "catch(e){return false;}})()")
    }

    fun copySelection() {
        executeJavascript("(function(){try{return document.execCommand('copy');}" +
            "catch(e){return false;}})()")
    }

    fun cutSelection() {
        executeJavascript("(function(){try{return document.execCommand('cut');}" +
            "catch(e){return false;}})()")
    }

    fun undo() {
        executeJavascript("(function(){try{return document.execCommand('undo');}" +
            "catch(e){return false;}})()")
    }

    fun redo() {
        executeJavascript("(function(){try{return document.execCommand('redo');}" +
            "catch(e){return false;}})()")
    }

    fun executeJavascript(script: String) {
        currentTab?.webView?.evaluateJavascript(script, null)
    }

    fun zoomIn() {
        siteTextZoom = (siteTextZoom + 10).coerceAtMost(220)
        applyZoom()
    }

    fun zoomOut() {
        siteTextZoom = (siteTextZoom - 10).coerceAtLeast(60)
        applyZoom()
    }

    fun resetZoom() {
        siteTextZoom = 100
        applyZoom()
    }

    private fun applyZoom() {
        tabs.forEach { it.webView.settings.textZoom = siteTextZoom }
    }

    fun applyUserAgent() {
        tabs.forEach {
            it.webView.settings.userAgentString =
                if (settings.desktopMode) desktopUserAgent else defaultUserAgent
        }
        reload()
    }

    fun onCreateWindow(): BrowserTab {
        return newTab(null, true)
    }

    fun exitFullscreen() {
        if (fullscreenView != null) {
            fullscreenCallback?.onCustomViewHidden()
        }
    }

    fun toggleTabSwitcher() {
        if (tabOverlay != null) hideTabSwitcher() else showTabSwitcher()
    }

    fun showTabSwitcher() {
        if (tabOverlay != null) return
        val overlay = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_scrim)
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(18), activity.dp(10), activity.dp(14), activity.dp(6))
        }
        val title = activity.textView(
            activity.getString(R.string.tab_switcher),
            20f,
            activity.getColor(R.color.text_primary),
            medium = true
        )
        header.addView(title)

        val newTab = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_secondary_button)
            setPadding(activity.dp(12), activity.dp(7), activity.dp(13), activity.dp(7))
            isClickable = true
            setOnClickListener {
                hideTabSwitcher()
                newTab(settings.homeUrl)
            }
        }
        newTab.addView(activity.iconView(R.drawable.ic_plus, activity.getColor(R.color.text_primary), 16))
        newTab.addView(activity.textView(
            activity.getString(R.string.new_tab),
            12.5f,
            activity.getColor(R.color.text_primary),
            medium = true
        ).apply {
            setPadding(activity.dp(6), 0, 0, 0)
        })
        val newTabParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        newTabParams.marginStart = activity.dp(14)
        header.addView(newTab, newTabParams)

        val spacer = View(activity)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val done = activity.textView(
            activity.getString(R.string.done),
            13f,
            activity.getColor(R.color.accent_bright),
            medium = true
        ).apply {
            setPadding(activity.dp(12), activity.dp(8), activity.dp(8), activity.dp(8))
            isClickable = true
            setOnClickListener { hideTabSwitcher() }
        }
        header.addView(done)
        overlay.addView(header)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(4), activity.dp(14), activity.dp(18))
        }
        tabs.forEach { tab ->
            list.addView(buildTabRow(tab))
        }
        scroll.addView(list)
        overlay.addView(scroll)

        webHost.addView(overlay)
        tabOverlay = overlay
    }

    private fun buildTabRow(tab: BrowserTab): View {
        val active = tab === currentTab
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(
                if (active) R.drawable.bg_tab_row_active else R.drawable.bg_tab_row
            )
            setPadding(activity.dp(13), activity.dp(10), activity.dp(8), activity.dp(10))
            isClickable = true
            setOnClickListener {
                hideTabSwitcher()
                selectTab(tab)
            }
        }

        val favicon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_globe)
            setColorFilter(activity.getColor(if (active) R.color.accent_bright else R.color.text_secondary))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(favicon, LinearLayout.LayoutParams(activity.dp(20), activity.dp(20)))
        tab.favicon?.let {
            favicon.setImageBitmap(it)
            favicon.clearColorFilter()
        }

        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleText = tab.title.ifBlank {
            if (tab.url.isBlank()) activity.getString(R.string.new_tab) else tab.url
        }
        labels.addView(activity.textView(
            titleText,
            13.5f,
            activity.getColor(R.color.text_primary),
            medium = true
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(activity.textView(
            tab.url.ifBlank { activity.getString(R.string.new_tab) },
            10.5f,
            activity.getColor(R.color.text_tertiary)
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val labelsParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelsParams.marginStart = activity.dp(11)
        row.addView(labels, labelsParams)

        val close = ImageView(activity).apply {
            setImageResource(R.drawable.ic_tab_close)
            setColorFilter(activity.getColor(R.color.text_secondary))
            setBackgroundResource(R.drawable.bg_close_button)
            setPadding(activity.dp(7), activity.dp(7), activity.dp(7), activity.dp(7))
            isClickable = true
            contentDescription = activity.getString(R.string.close_tab)
            setOnClickListener {
                closeTab(tab)
                hideTabSwitcher()
                showTabSwitcher()
            }
        }
        row.addView(close, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)))

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = activity.dp(7)
        row.layoutParams = params
        return row
    }

    fun hideTabSwitcher() {
        tabOverlay?.let { webHost.removeView(it) }
        tabOverlay = null
    }

    private inner class NativeBridge {
        @android.webkit.JavascriptInterface
        fun onEditableFocused() {
            activity.runOnUiThread {
                listener?.onEditableFocused()
            }
        }
    }

    private fun buildWebViewClient(tab: BrowserTab): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != null && scheme !in WEB_SCHEMES) {
                    openExternal(uri.toString())
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return AdBlockManager.shouldIntercept(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tab.url = url.orEmpty()
                tab.isLoading = true
                if (tab === currentTab) {
                    listener?.onTabChanged(tab)
                    listener?.onProgressChanged(0, true)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                view.evaluateJavascript(PageScripts.HELPER, null)
                view.evaluateJavascript(PageScripts.COSMETIC, null)
                AdBlockManager.inject(view, url ?: tab.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.url = url.orEmpty()
                tab.isLoading = false
                view.evaluateJavascript(PageScripts.HELPER, null)
                view.evaluateJavascript(PageScripts.COSMETIC, null)
                AdBlockManager.inject(view, url ?: tab.url)
                if (tab === currentTab) {
                    listener?.onTabChanged(tab)
                    listener?.onProgressChanged(100, false)
                    listener?.onNavigationChanged(view.canGoBack(), view.canGoForward())
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null) {
                    tab.url = url
                    if (tab === currentTab) listener?.onTabChanged(tab)
                }
                if (tab === currentTab) {
                    listener?.onNavigationChanged(view.canGoBack(), view.canGoForward())
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame && tab === currentTab) {
                    listener?.onProgressChanged(100, false)
                }
            }
        }
    }

    private fun buildChromeClient(tab: BrowserTab): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab !== currentTab) return
                tab.isLoading = newProgress < 100
                listener?.onProgressChanged(newProgress, newProgress < 100)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title.orEmpty()
                if (tab === currentTab) listener?.onTabChanged(tab)
            }

            override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
                tab.favicon = icon
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val newTab = newTab(null, true)
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                return listener?.onShowFileChooser(webView, filePathCallback, fileChooserParams) ?: false
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                listener?.onFullscreenChanged(true, view)
            }

            override fun onHideCustomView() {
                fullscreenView = null
                fullscreenCallback = null
                listener?.onFullscreenChanged(false, null)
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, false, false)
            }
        }
    }

    private fun buildDownloadListener(): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e("BrowserController", "Download failed", t)
                Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openExternal(url: String) {
        try {
            val uri = Uri.parse(url)
            val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e("BrowserController", "Could not open $url", t)
            Toast.makeText(activity, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchTextFallback(webView: WebView, text: String) {
        try {
            val map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = map.getEvents(text.toCharArray())
            if (events != null) {
                events.forEach { webView.dispatchKeyEvent(it) }
            }
        } catch (t: Throwable) {
            Log.e("BrowserController", "Key fallback failed", t)
        }
    }

    private fun dispatchKey(webView: WebView, keyCode: Int, metaState: Int = 0) {
        val now = android.os.SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        )
        webView.dispatchKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)
        )
    }

    fun onPause() {
        CookieManager.getInstance().flush()
    }

    fun onResume() {
    }

    fun onDestroy() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
        hideTabSwitcher()
        tabs.forEach {
            webStack.removeView(it.webView)
            it.webView.stopLoading()
            it.webView.destroy()
        }
        tabs.clear()
        currentTab = null
    }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file")
        private val IP_ADDRESS = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")
    }
}
