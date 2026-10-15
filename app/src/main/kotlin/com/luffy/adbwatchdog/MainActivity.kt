package com.luffy.adbwatchdog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.luffy.adbwatchdog.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore the language chosen via the toggle button (persists across launches).
        val savedLang = WatchdogService.prefs(this).getString(WatchdogService.KEY_LANG, null)
        if (savedLang == "zh") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        } else if (savedLang == "en") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = WatchdogService.prefs(this)

        binding.enableSwitch.isChecked = prefs.getBoolean(WatchdogService.KEY_ENABLED, false)
        binding.enableSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(WatchdogService.KEY_ENABLED, on).apply()
            if (on) WatchdogService.start(this) else WatchdogService.stop(this)
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnBattery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        binding.btnTest.setOnClickListener {
            lifecycleScope.launch {
                val s = AdbWifiController.recover(this@MainActivity)
                refreshStatus(extra = getString(R.string.manual_recover_fmt, s?.toString() ?: "null"))
            }
        }

        binding.btnLanguage.setOnClickListener { toggleLanguage() }

        // Tap the command box to copy the adb grant command.
        binding.grantCommand.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("adb", binding.grantCommand.text))
            Toast.makeText(this, R.string.copy_toast_ok, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Toggle the app language between Chinese and English and persist the choice.
     * setApplicationLocales recreates the activity automatically.
     */
    private fun toggleLanguage() {
        val prefs = WatchdogService.prefs(this)
        val current = AppCompatDelegate.getApplicationLocales()
        val currentIsZh = if (!current.isEmpty) {
            current[0]?.language == "zh"
        } else {
            resources.configuration.locales[0].language == "zh"
        }
        val target = if (currentIsZh) "en" else "zh"
        prefs.edit().putString(WatchdogService.KEY_LANG, target).apply()
        AppCompatDelegate.setApplicationLocales(
            if (target == "zh") LocaleListCompat.forLanguageTags("zh-CN")
            else LocaleListCompat.forLanguageTags("en")
        )
    }

    override fun onStart() {
        super.onStart()
        statusJob = lifecycleScope.launch {
            while (true) {
                refreshStatus()
                delay(1000)
            }
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        super.onStop()
    }

    private fun refreshStatus(extra: String? = null) {
        val prefs = WatchdogService.prefs(this)
        val adbOn = AdbWifiController.isEnabled(this)
        val granted = AdbWifiController.hasSecureSettingsPermission(this)
        val a11y = WatchdogAccessibilityService.isBound()
        val wifi = AdbWifiController.isWifiConnected(this)
        val recoveries = prefs.getInt(WatchdogService.KEY_RECOVERIES, 0)
        val failures = prefs.getInt(WatchdogService.KEY_FAILURES, 0)

        binding.status.text = buildString {
            append(getString(R.string.status_wireless)).append(" : ")
                .append(getString(if (adbOn) R.string.val_on else R.string.val_off)).append('\n')
            append(getString(R.string.status_perm)).append(" : ")
                .append(getString(if (granted) R.string.val_granted else R.string.val_not_granted)).append('\n')
            append(getString(R.string.status_a11y)).append(" : ")
                .append(getString(if (a11y) R.string.val_bound else R.string.val_not_bound)).append('\n')
            append(getString(R.string.status_wifi)).append(" : ")
                .append(getString(if (wifi) R.string.val_connected else R.string.val_disconnected)).append('\n')
            append(getString(R.string.status_counters)).append(" : ")
                .append(recoveries).append(" / ").append(failures)
            if (extra != null) append('\n').append(extra)
        }

        binding.grantHint.text = if (granted) {
            getString(R.string.hint_granted)
        } else {
            getString(R.string.hint_not_granted)
        }
        binding.grantCommand.isVisible = !granted
        if (!granted) {
            binding.grantCommand.text = getString(R.string.grant_cmd_fmt, packageName)
        }
    }
}
