package com.etmw.foldpc

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.etmw.foldpc.adblock.AdBlockManager
import kotlin.math.max

class SettingsActivity : Activity() {

    private lateinit var prefs: SettingsRepository
    private lateinit var root: LinearLayout
    private lateinit var homeUrl: EditText
    private lateinit var switchFunctionRow: Switch
    private lateinit var switchAdblock: Switch
    private lateinit var switchDesktop: Switch
    private var restoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        configureWindow()
        setContentView(R.layout.activity_settings)
        applySystemBarAppearance()
        prefs = SettingsRepository(this)

        root = findViewById(R.id.settings_root)
        homeUrl = findViewById(R.id.home_url)
        switchFunctionRow = findViewById(R.id.switch_function_row)
        switchAdblock = findViewById(R.id.switch_adblock)
        switchDesktop = findViewById(R.id.switch_desktop)

        applyInsets()
        loadValues()
        setupActions()
        root.requestFocus()
    }

    private fun configureWindow() {
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
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
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
        root.requestApplyInsets()
    }

    private fun loadValues() {
        restoring = true
        homeUrl.setText(prefs.homeUrl)
        switchFunctionRow.isChecked = prefs.showFunctionRow
        switchAdblock.isChecked = prefs.adBlockEnabled
        switchDesktop.isChecked = prefs.desktopMode
        restoring = false
    }

    private fun setupActions() {
        findViewById<View>(R.id.settings_back).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.settings_done).setOnClickListener { saveAndFinish() }

        homeUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveHome()
        }
        homeUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveHome()
                hideSystemKeyboard()
                true
            } else {
                false
            }
        }

        switchFunctionRow.setOnCheckedChangeListener { _, checked ->
            if (restoring) return@setOnCheckedChangeListener
            prefs.showFunctionRow = checked
        }
        switchAdblock.setOnCheckedChangeListener { _, checked ->
            if (restoring) return@setOnCheckedChangeListener
            prefs.adBlockEnabled = checked
            AdBlockManager.setEnabled(checked)
        }
        switchDesktop.setOnCheckedChangeListener { _, checked ->
            if (restoring) return@setOnCheckedChangeListener
            prefs.desktopMode = checked
        }

        findViewById<View>(R.id.update_filters).setOnClickListener {
            AdBlockManager.updateFilters()
            Toast.makeText(this, R.string.filters_updated, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.reset_defaults).setOnClickListener {
            restoring = true
            homeUrl.setText(SettingsRepository.DEFAULT_HOME)
            switchFunctionRow.isChecked = true
            switchAdblock.isChecked = true
            switchDesktop.isChecked = false
            restoring = false
            prefs.homeUrl = SettingsRepository.DEFAULT_HOME
            prefs.showFunctionRow = true
            prefs.adBlockEnabled = true
            prefs.desktopMode = false
            AdBlockManager.setEnabled(true)
        }
    }

    private fun saveHome() {
        val normalized = prefs.normalizeHome(homeUrl.text.toString())
        prefs.homeUrl = normalized
        if (homeUrl.text.toString() != normalized) {
            homeUrl.setText(normalized)
            homeUrl.setSelection(homeUrl.text.length)
        }
    }

    private fun saveAndFinish() {
        saveHome()
        finish()
    }

    private fun hideSystemKeyboard() {
        try {
            val manager = getSystemService(InputMethodManager::class.java) ?: return
            manager.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    override fun onPause() {
        saveHome()
        super.onPause()
    }
}
