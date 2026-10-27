package com.etmw.foldpc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.etmw.foldpc.adblock.AdBlockManager
import com.etmw.foldpc.browser.BrowserController
import com.etmw.foldpc.browser.BrowserTab
import com.etmw.foldpc.keyboard.ArrowDirection
import com.etmw.foldpc.keyboard.MacKeyboardView
import com.etmw.foldpc.keyboard.Modifiers
import com.etmw.foldpc.ui.dp
import com.etmw.foldpc.ui.iconView
import com.etmw.foldpc.ui.textView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var prefs: SettingsRepository
    private lateinit var root: LinearLayout
    private lateinit var webHost: FrameLayout
    private lateinit var keyboard: MacKeyboardView
    private lateinit var restoreChip: View
    private lateinit var addressBar: EditText
    private lateinit var clearAddress: ImageButton
    private lateinit var siteIcon: ImageView
    private lateinit var tabCount: TextView
    private lateinit var pageProgress: ProgressBar
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnTabs: View
    private lateinit var btnMenu: ImageButton
    private lateinit var btnKeyboardToggle: ImageButton

    private lateinit var browser: BrowserController

    private var keyboardVisible = true
    private var keyboardAnimator: ValueAnimator? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenOverlay: FrameLayout? = null
    private var wasKeyboardVisibleBeforeFullscreen = true
    private var menuWindow: PopupWindow? = null
    private var lastBackPress = 0L

    private var knownAdBlock = true
    private var knownDesktopMode = false
    private var knownFunctionRow = true
    private val imeHideHandler = Handler(Looper.getMainLooper())
    private val imeHideRunnable = object : Runnable {
        private var tries = 0
        override fun run() {
            hideSystemKeyboard()
            tries++
            if (tries < 12) {
                imeHideHandler.postDelayed(this, 100L)
            }
        }
    }
    private val imeGuard = object : Runnable {
        override fun run() {
            hideSystemKeyboard()
            imeHideHandler.postDelayed(this, 420L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestOrientation()
        configureWindow()
        prefs = SettingsRepository(this)
        knownAdBlock = prefs.adBlockEnabled
        knownDesktopMode = prefs.desktopMode
        knownFunctionRow = prefs.showFunctionRow

        setContentView(R.layout.activity_main)
        applySystemBarAppearance()
        bindViews()
        applyInsets()

        keyboard.showFunctionRow = prefs.showFunctionRow
        keyboardVisible = true

        browser = BrowserController(this, findViewById(R.id.web_stack), prefs)
        browser.listener = browserListener()

        setupToolbar()
        setupAddressBar()
        setupKeyboard()
        AdBlockManager.setEnabled(prefs.adBlockEnabled)

        val launchUrl = intent?.data?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?.toString()
            ?: prefs.homeUrl
        browser.newTab(launchUrl)

        if (!prefs.keyboardVisible) {
            setKeyboardVisible(false, animate = false, persist = false)
        } else {
            updateKeyboardUi()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data ?: return
        if (uri.scheme == "http" || uri.scheme == "https") {
            browser.newTab(uri.toString())
            if (!keyboardVisible) setKeyboardVisible(true, animate = false, persist = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::browser.isInitialized) return
        if (knownAdBlock != prefs.adBlockEnabled) {
            knownAdBlock = prefs.adBlockEnabled
            AdBlockManager.setEnabled(knownAdBlock)
            browser.reload()
        }
        if (knownDesktopMode != prefs.desktopMode) {
            knownDesktopMode = prefs.desktopMode
            browser.applyUserAgent()
        }
        if (knownFunctionRow != prefs.showFunctionRow) {
            knownFunctionRow = prefs.showFunctionRow
            keyboard.showFunctionRow = knownFunctionRow
        }
        hideSystemKeyboard()
        imeHideHandler.removeCallbacks(imeGuard)
        imeHideHandler.postDelayed(imeGuard, 420L)
    }

    override fun onPause() {
        menuWindow?.dismiss()
        menuWindow = null
        imeHideHandler.removeCallbacks(imeHideRunnable)
        imeHideHandler.removeCallbacks(imeGuard)
        hideSystemKeyboard()
        if (::browser.isInitialized) browser.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        keyboardAnimator?.cancel()
        imeHideHandler.removeCallbacks(imeHideRunnable)
        imeHideHandler.removeCallbacks(imeGuard)
        if (::browser.isInitialized) browser.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::keyboard.isInitialized) {
            val params = keyboard.layoutParams
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            keyboard.layoutParams = params
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenOverlay != null) {
            browser.exitFullscreen()
            return
        }
        if (browser.isTabSwitcherVisible) {
            browser.hideTabSwitcher()
            return
        }
        if (addressBar.hasFocus()) {
            addressBar.setText(browser.currentUrl())
            addressBar.clearFocus()
            browser.requestWebFocus()
            return
        }
        if (browser.canGoBack()) {
            browser.goBack()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBackPress < 2000L) {
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE_CHOOSER) {
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun configureWindow() {
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        webHost = findViewById(R.id.web_host)
        keyboard = findViewById(R.id.keyboard)
        restoreChip = findViewById(R.id.keyboard_restore_chip)
        addressBar = findViewById(R.id.address_bar)
        clearAddress = findViewById(R.id.btn_clear_address)
        siteIcon = findViewById(R.id.site_security_icon)
        tabCount = findViewById(R.id.tab_count)
        pageProgress = findViewById(R.id.page_progress)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
        btnTabs = findViewById(R.id.btn_tabs)
        btnMenu = findViewById(R.id.btn_menu)
        btnKeyboardToggle = findViewById(R.id.btn_keyboard_toggle)
    }

    private fun applySystemBarAppearance() {
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    }

    private fun applyInsets() {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun setupToolbar() {
        btnBack.setOnClickListener { browser.goBack() }
        btnForward.setOnClickListener { browser.goForward() }
        btnReload.setOnClickListener { browser.reload() }
        btnTabs.setOnClickListener { browser.toggleTabSwitcher() }
        btnMenu.setOnClickListener { showMenu() }
        btnKeyboardToggle.setOnClickListener {
            setKeyboardVisible(!keyboardVisible, animate = true)
        }
        restoreChip.setOnClickListener {
            setKeyboardVisible(true, animate = true)
        }
        btnBack.setOnLongClickListener {
            browser.home()
            true
        }
    }

    private fun setupAddressBar() {
        addressBar.setText("")
        addressBar.showSoftInputOnFocus = false
        addressBar.setOnClickListener {
            addressBar.selectAll()
            hideSystemKeyboard()
        }
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                addressBar.selectAll()
                hideSystemKeyboard()
                if (!keyboardVisible) setKeyboardVisible(true, animate = true)
            }
        }
        addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                navigateAddress()
                true
            } else {
                false
            }
        }
        addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clearAddress.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        clearAddress.setOnClickListener {
            addressBar.setText("")
            addressBar.requestFocus()
            hideSystemKeyboard()
        }
    }

    private fun setupKeyboard() {
        keyboard.listener = object : MacKeyboardView.Listener {
            override fun onCharacter(text: String, modifiers: Modifiers) {
                hideSystemKeyboard()
                if (modifiers.command) {
                    performCommand(text, modifiers)
                    return
                }
                if (addressBar.hasFocus()) {
                    insertAddressText(text)
                } else {
                    browser.insertText(text)
                }
            }

            override fun onBackspace(modifiers: Modifiers) {
                hideSystemKeyboard()
                if (addressBar.hasFocus()) deleteAddressBackward() else browser.backspace()
            }

            override fun onDeleteForward(modifiers: Modifiers) {
                hideSystemKeyboard()
                if (addressBar.hasFocus()) deleteAddressForward() else browser.forwardDelete()
            }

            override fun onEnter(modifiers: Modifiers) {
                hideSystemKeyboard()
                if (addressBar.hasFocus()) navigateAddress() else browser.pressEnter()
            }

            override fun onTab(modifiers: Modifiers) {
                hideSystemKeyboard()
                if (addressBar.hasFocus()) {
                    addressBar.clearFocus()
                    browser.requestWebFocus()
                } else {
                    browser.pressTab()
                }
            }

            override fun onEscape(modifiers: Modifiers) {
                hideSystemKeyboard()
                if (addressBar.hasFocus()) {
                    addressBar.setText(browser.currentUrl())
                    addressBar.clearFocus()
                    browser.requestWebFocus()
                } else {
                    browser.pressEscape()
                }
            }

            override fun onArrow(direction: ArrowDirection, modifiers: Modifiers) {
                hideSystemKeyboard()
                if (addressBar.hasFocus()) {
                    val keyCode = when (direction) {
                        ArrowDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                        ArrowDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                        ArrowDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
                        ArrowDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    }
                    var meta = 0
                    if (modifiers.shift) meta = meta or KeyEvent.META_SHIFT_ON
                    if (modifiers.option) meta = meta or KeyEvent.META_ALT_ON
                    dispatchKeyToAddress(keyCode, meta)
                    return
                }
                if (modifiers.command) {
                    when (direction) {
                        ArrowDirection.LEFT -> browser.goBack()
                        ArrowDirection.RIGHT -> browser.goForward()
                        ArrowDirection.UP -> browser.executeJavascript(
                            "window.scrollTo({top:0,behavior:'smooth'})"
                        )
                        ArrowDirection.DOWN -> browser.executeJavascript(
                            "window.scrollTo({top:document.body.scrollHeight,behavior:'smooth'})"
                        )
                    }
                    return
                }
                val dir = when (direction) {
                    ArrowDirection.LEFT -> "left"
                    ArrowDirection.RIGHT -> "right"
                    ArrowDirection.UP -> "up"
                    ArrowDirection.DOWN -> "down"
                }
                browser.arrow(dir, modifiers.shift, modifiers.option)
            }

            override fun onFunction(number: Int, modifiers: Modifiers) {
                hideSystemKeyboard()
                when (number) {
                    1 -> showAbout()
                    2 -> openSettings()
                    4, 6 -> focusAddress()
                    5 -> browser.reload()
                    7 -> browser.home()
                    8 -> browser.goBack()
                    9 -> browser.goForward()
                    10 -> browser.newTab(prefs.homeUrl)
                    11 -> setKeyboardVisible(!keyboardVisible, animate = true)
                    12 -> showMenu()
                }
            }

            override fun onFunctionRowToggled(visible: Boolean) {
                prefs.showFunctionRow = visible
                knownFunctionRow = visible
            }
        }
    }

    private fun browserListener(): BrowserController.Listener {
        return object : BrowserController.Listener {
            override fun onTabChanged(tab: BrowserTab) {
                if (!addressBar.hasFocus()) {
                    val value = tab.url
                    if (addressBar.text.toString() != value) {
                        addressBar.setText(value)
                        addressBar.setSelection(0)
                    }
                }
                updateSecurityIcon(tab.url)
            }

            override fun onTabsChanged(count: Int) {
                tabCount.text = count.toString()
            }

            override fun onNavigationChanged(canGoBack: Boolean, canGoForward: Boolean) {
                btnBack.alpha = if (canGoBack) 1f else 0.35f
                btnBack.isEnabled = canGoBack
                btnForward.alpha = if (canGoForward) 1f else 0.35f
                btnForward.isEnabled = canGoForward
            }

            override fun onProgressChanged(progress: Int, loading: Boolean) {
                pageProgress.progress = progress
                pageProgress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
            }

            override fun onWebInteraction() {
                if (addressBar.hasFocus()) {
                    addressBar.clearFocus()
                }
                suppressSystemKeyboard()
            }

            override fun onEditableFocused() {
                if (!keyboardVisible) setKeyboardVisible(true, animate = true)
                suppressSystemKeyboard()
            }

            override fun onFullscreenChanged(entering: Boolean, view: View?) {
                if (entering && view != null) {
                    enterFullscreen(view)
                } else {
                    exitFullscreen()
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val intent = Intent.createChooser(params.createIntent(), "Choose file")
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                } catch (t: Throwable) {
                    filePathCallback = null
                    false
                }
            }
        }
    }

    private fun navigateAddress() {
        val input = addressBar.text.toString()
        addressBar.clearFocus()
        browser.requestWebFocus()
        hideSystemKeyboard()
        browser.loadUserInput(input)
    }

    private fun focusAddress() {
        addressBar.requestFocus()
        addressBar.selectAll()
        if (!keyboardVisible) setKeyboardVisible(true, animate = true)
        hideSystemKeyboard()
    }

    private fun insertAddressText(text: String) {
        val editable = addressBar.text ?: return
        val start = min(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        val end = max(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        editable.replace(start, end, text)
        addressBar.setSelection(start + text.length)
    }

    private fun deleteAddressBackward() {
        val editable = addressBar.text ?: return
        val start = min(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        val end = max(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(start, end)
            addressBar.setSelection(start)
        } else if (start > 0) {
            editable.delete(start - 1, start)
            addressBar.setSelection(start - 1)
        }
    }

    private fun deleteAddressForward() {
        val editable = addressBar.text ?: return
        val start = min(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        val end = max(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(start, end)
            addressBar.setSelection(start)
        } else if (start < editable.length) {
            editable.delete(start, start + 1)
            addressBar.setSelection(start)
        }
    }

    private fun dispatchKeyToAddress(keyCode: Int, metaState: Int = 0) {
        val now = SystemClock.uptimeMillis()
        addressBar.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        addressBar.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    private fun performCommand(text: String, modifiers: Modifiers) {
        val key = text.lowercase()
        val inAddress = addressBar.hasFocus()
        if (inAddress) {
            when (key) {
                "a" -> addressBar.selectAll()
                "c" -> copyAddressSelection()
                "x" -> cutAddressSelection()
                "v" -> pasteIntoAddress()
                "z" -> {
                    val meta = if (modifiers.shift) {
                        KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON
                    } else {
                        KeyEvent.META_CTRL_ON
                    }
                    dispatchKeyToAddress(KeyEvent.KEYCODE_Z, meta)
                }
                "l" -> focusAddress()
                "r" -> browser.reload()
                "t" -> browser.newTab(prefs.homeUrl)
                "w" -> browser.closeCurrentTab()
                "[" -> browser.goBack()
                "]" -> browser.goForward()
                "=", "+" -> browser.zoomIn()
                "-", "_" -> browser.zoomOut()
                "0" -> browser.resetZoom()
            }
            return
        }

        when (key) {
            "a" -> browser.selectAll()
            "c" -> browser.copySelection()
            "x" -> browser.cutSelection()
            "v" -> pasteIntoWeb()
            "z" -> if (modifiers.shift) browser.redo() else browser.undo()
            "l" -> focusAddress()
            "r" -> browser.reload()
            "t" -> browser.newTab(prefs.homeUrl)
            "w" -> browser.closeCurrentTab()
            "h" -> browser.home()
            "[" -> browser.goBack()
            "]" -> browser.goForward()
            "=", "+" -> browser.zoomIn()
            "-", "_" -> browser.zoomOut()
            "0" -> browser.resetZoom()
        }
    }

    private fun clipboardText(): String {
        val manager = getSystemService(ClipboardManager::class.java) ?: return ""
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    private fun setClipboard(text: String, label: String = "URL") {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun copyAddressSelection() {
        val editable = addressBar.text ?: return
        val start = min(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        val end = max(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        if (start == end) {
            if (editable.isNotEmpty()) setClipboard(editable.toString())
        } else {
            setClipboard(editable.subSequence(start, end).toString())
        }
    }

    private fun cutAddressSelection() {
        val editable = addressBar.text ?: return
        val start = min(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        val end = max(addressBar.selectionStart, addressBar.selectionEnd)
            .coerceIn(0, editable.length)
        if (start == end) return
        setClipboard(editable.subSequence(start, end).toString())
        editable.delete(start, end)
        addressBar.setSelection(start)
    }

    private fun pasteIntoAddress() {
        val text = clipboardText()
        if (text.isNotEmpty()) insertAddressText(text)
    }

    private fun pasteIntoWeb() {
        val text = clipboardText()
        if (text.isNotEmpty()) browser.insertText(text)
    }

    private fun updateSecurityIcon(url: String) {
        if (url.startsWith("https://", ignoreCase = true)) {
            siteIcon.setImageResource(R.drawable.ic_lock)
        } else if (url.startsWith("http://", ignoreCase = true)) {
            siteIcon.setImageResource(R.drawable.ic_unlock)
        } else {
            siteIcon.setImageResource(R.drawable.ic_globe)
            siteIcon.setColorFilter(getColor(R.color.text_tertiary))
            return
        }
        siteIcon.clearColorFilter()
    }

    private fun setKeyboardVisible(visible: Boolean, animate: Boolean, persist: Boolean = true) {
        if (keyboardVisible == visible) {
            updateKeyboardUi()
            return
        }
        keyboardVisible = visible
        if (persist) prefs.keyboardVisible = visible
        keyboardAnimator?.cancel()

        if (!animate) {
            val params = keyboard.layoutParams
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            keyboard.layoutParams = params
            keyboard.visibility = if (visible) View.VISIBLE else View.GONE
            updateKeyboardUi()
            return
        }

        if (visible) {
            val width = if (keyboard.width > 0) keyboard.width else root.width
            val targetHeight = keyboard.desiredHeightForWidth(width)
            if (targetHeight <= 0) {
                keyboard.visibility = View.VISIBLE
                updateKeyboardUi()
                return
            }
            val params = keyboard.layoutParams
            params.height = 0
            keyboard.layoutParams = params
            keyboard.visibility = View.VISIBLE
            restoreChip.visibility = View.GONE
            keyboardAnimator = ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 230L
                addUpdateListener { animation ->
                    val height = animation.animatedValue as Int
                    val lp = keyboard.layoutParams
                    lp.height = height
                    keyboard.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!keyboardVisible) return
                        val lp = keyboard.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        keyboard.layoutParams = lp
                        updateKeyboardUi()
                    }
                })
                start()
            }
        } else {
            val startHeight = if (keyboard.height > 0) keyboard.height else keyboard.desiredHeightForWidth(root.width)
            keyboardAnimator = ValueAnimator.ofInt(startHeight, 0).apply {
                duration = 190L
                addUpdateListener { animation ->
                    val height = animation.animatedValue as Int
                    val lp = keyboard.layoutParams
                    lp.height = height
                    keyboard.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (keyboardVisible) return
                        keyboard.visibility = View.GONE
                        val lp = keyboard.layoutParams
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        keyboard.layoutParams = lp
                        updateKeyboardUi()
                    }
                })
                start()
            }
        }
        updateKeyboardUi()
    }

    private fun updateKeyboardUi() {
        btnKeyboardToggle.setImageResource(
            if (keyboardVisible) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard
        )
        btnKeyboardToggle.contentDescription = getString(
            if (keyboardVisible) R.string.hide_keyboard else R.string.show_keyboard
        )
        restoreChip.visibility =
            if (!keyboardVisible && fullscreenOverlay == null) View.VISIBLE else View.GONE
    }

    private fun hideSystemKeyboard() {
        try {
            val manager = getSystemService(InputMethodManager::class.java) ?: return
            manager.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    private fun suppressSystemKeyboard() {
        imeHideHandler.removeCallbacks(imeHideRunnable)
        imeHideRunnable.run()
    }

    private fun enterFullscreen(view: View) {
        if (fullscreenOverlay != null) return
        wasKeyboardVisibleBeforeFullscreen = keyboardVisible
        if (keyboardVisible) setKeyboardVisible(false, animate = false, persist = false)
        hideSystemKeyboard()
        val content = findViewById<FrameLayout>(android.R.id.content)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenOverlay = overlay
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.insetsController?.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        updateKeyboardUi()
    }

    private fun exitFullscreen() {
        val overlay = fullscreenOverlay ?: return
        fullscreenOverlay = null
        val content = findViewById<FrameLayout>(android.R.id.content)
        content.removeView(overlay)
        window.insetsController?.show(WindowInsets.Type.systemBars())
        if (wasKeyboardVisibleBeforeFullscreen) {
            setKeyboardVisible(true, animate = false, persist = false)
        }
        updateKeyboardUi()
    }

    private fun showMenu() {
        menuWindow?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        addMenuItem(content, R.drawable.ic_plus, getString(R.string.new_tab), null) {
            browser.newTab(prefs.homeUrl)
        }
        addMenuItem(content, R.drawable.ic_home, getString(R.string.home), null) {
            browser.home()
        }
        addMenuItem(content, R.drawable.ic_reload, getString(R.string.reload), null) {
            browser.reload()
        }
        addMenuDivider(content)
        addMenuItem(
            content,
            R.drawable.ic_desktop,
            getString(R.string.desktop_site),
            if (prefs.desktopMode) "On" else "Off"
        ) {
            prefs.desktopMode = !prefs.desktopMode
            knownDesktopMode = prefs.desktopMode
            browser.applyUserAgent()
        }
        addMenuItem(
            content,
            R.drawable.ic_shield,
            getString(R.string.adblocking),
            if (prefs.adBlockEnabled) "On" else "Off"
        ) {
            prefs.adBlockEnabled = !prefs.adBlockEnabled
            knownAdBlock = prefs.adBlockEnabled
            AdBlockManager.setEnabled(knownAdBlock)
            browser.reload()
        }
        addMenuDivider(content)
        addMenuItem(content, R.drawable.ic_settings, getString(R.string.settings), null) {
            openSettings()
        }
        addMenuItem(content, R.drawable.ic_info, getString(R.string.about), null) {
            showAbout()
        }

        val width = dp(286)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dp(16).toFloat()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.setOnDismissListener {
            if (menuWindow === popup) menuWindow = null
        }
        val location = IntArray(2)
        btnMenu.getLocationOnScreen(location)
        val screenWidth = resources.displayMetrics.widthPixels
        val x = (location[0] + btnMenu.width - width)
            .coerceAtMost(screenWidth - width - dp(8))
            .coerceAtLeast(dp(8))
        val y = location[1] + btnMenu.height + dp(4)
        popup.showAtLocation(btnMenu, Gravity.TOP or Gravity.START, x, y)
        menuWindow = popup
    }

    private fun addMenuItem(
        container: LinearLayout,
        icon: Int,
        label: String,
        state: String?,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_menu_item)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener {
                menuWindow?.dismiss()
                onClick()
            }
        }
        row.addView(iconView(icon, getColor(R.color.text_secondary), 20))
        val title = textView(
            label,
            13.5f,
            getColor(R.color.text_primary),
            medium = true
        )
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = dp(11)
        row.addView(title, titleParams)
        if (state != null) {
            val isOn = state == "On"
            row.addView(textView(
                state,
                11.5f,
                getColor(if (isOn) R.color.accent_bright else R.color.text_tertiary),
                medium = true
            ))
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addMenuDivider(container: LinearLayout) {
        val divider = View(this).apply { setBackgroundColor(getColor(R.color.line_soft)) }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        params.topMargin = dp(4)
        params.bottomMargin = dp(4)
        params.leftMargin = dp(10)
        params.rightMargin = dp(10)
        container.addView(divider, params)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showAbout() {
        val message = """
            ETMW Fold PC 1.0

            A landscape-first mini computer for your Pixel Fold.

            Keyboard shortcuts
            ⌘L address bar    ⌘T new tab    ⌘W close tab
            ⌘R reload         ⌘[ back       ⌘] forward
            ⌘C copy           ⌘V paste      ⌘A select all
            ⌘+ / ⌘- zoom      ⌘0 reset zoom
            F5 reload         F6 address    F11 maximize
            F1 about          F2 settings   F12 menu

            Ad blocking is handled locally by the ETMW filter
            engine using AdGuard, EasyList and EasyPrivacy
            lists. Filter
            updates stay on the device.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val REQ_FILE_CHOOSER = 4101
    }
}
