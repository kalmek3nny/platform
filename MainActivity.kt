package com.gushypushy.diffusereborn

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat

class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)

        setupButtons()
        setupSliders()
        setupSwitches()
        updateStatusBar(prefs.getBoolean("light_mode", false))
    }

    override fun onResume() {
        super.onResume()
        checkStatus()
    }

    private fun updateStatusBar(isLightMode: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLightMode
    }

    // -------------------------------------------------------------
    // BUTTONS
    // -------------------------------------------------------------

    private fun setupButtons() {
        findViewById<Button>(R.id.btnHide).setOnClickListener {
            val startMain = Intent(Intent.ACTION_MAIN)
            startMain.addCategory(Intent.CATEGORY_HOME)
            startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(startMain)
        }

        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
            try {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                intent.putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, DiffuseWallpaperService::class.java)
                )
                startActivity(intent)
            } catch (e1: Exception) {
                try {
                    val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                    startActivity(chooser)
                } catch (e2: Exception) {
                    Toast.makeText(
                        this,
                        "Open wallpaper settings and choose Diffuse Reborn.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnMic).setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                101
            )
        }
    }

    // -------------------------------------------------------------
    // SWITCHES
    // -------------------------------------------------------------

    private fun setupSwitches() {
        val swDebug = findViewById<Switch>(R.id.switchDebug)
        swDebug.isChecked = prefs.getBoolean("debug_mode", false)
        swDebug.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("debug_mode", v).apply() }

        val swSaver = findViewById<Switch>(R.id.switchSaver)
        swSaver.isChecked = prefs.getBoolean("power_saver", false)
        swSaver.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("power_saver", v).apply() }

        val swOled = findViewById<Switch>(R.id.switchOled)
        swOled.isChecked = prefs.getBoolean("oled_mode", false)
        swOled.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("oled_mode", v).apply() }

        val swTouch = findViewById<Switch>(R.id.switchTouch)
        swTouch.isChecked = prefs.getBoolean("touch_mode", true)
        swTouch.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("touch_mode", v).apply() }

        val swWidget = findViewById<Switch>(R.id.switchWidget)
        swWidget.isChecked = prefs.getBoolean("widget_on", true)
        swWidget.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("widget_on", v).apply() }

        val swDust = findViewById<Switch>(R.id.switchDust)
        swDust.isChecked = prefs.getBoolean("dust_on", true)
        swDust.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("dust_on", v).apply() }

        val swLight = findViewById<Switch>(R.id.switchLight)
        swLight.isChecked = prefs.getBoolean("light_mode", false)
        swLight.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("light_mode", v).apply()
            updateStatusBar(v)
        }
    }

    // -------------------------------------------------------------
    // SLIDERS
    // -------------------------------------------------------------

    private fun setupSliders() {

        fun setupSeek(id: Int, lblId: Int, key: String, title: String) {
            val seek = findViewById<SeekBar>(id)
            val lbl = findViewById<TextView>(lblId)
            val saved = prefs.getInt(key, 50)

            seek.progress = saved
            lbl.text = "$title: $saved%"

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, b: Boolean) {
                    lbl.text = "$title: $v%"
                    prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        setupSeek(R.id.seekSpeed, R.id.lblSpeed, "speed", "Flow Speed")
        setupSeek(R.id.seekWander, R.id.lblWander, "wander", "Wander Range")
        setupSeek(R.id.seekBeat, R.id.lblBeat, "beat", "Beat Pulse")
        setupSeek(R.id.seekShake, R.id.lblShake, "shake", "Beat Shake")
        setupSeek(R.id.seekSize, R.id.lblSize, "size", "Blob Scale")
        setupSeek(R.id.seekSat, R.id.lblSat, "sat", "Color Boost")
        setupSeek(R.id.seekDensity, R.id.lblDensity, "density", "Fog Density")
        setupSeek(R.id.seekBright, R.id.lblBright, "bright", "Background Brightness")
        setupSeek(R.id.seekVig, R.id.lblVig, "vignette", "Vignette")
        setupSeek(R.id.seekDust, R.id.lblDust, "dust", "Opacity")
        setupSeek(R.id.seekDustSize, R.id.lblDustSize, "dust_size", "Dot Size")
        setupSeek(R.id.seekDustCount, R.id.lblDustCount, "dust_count", "Count")
        setupSeek(R.id.seekPosX, R.id.lblPosX, "pos_x", "Position X")
        setupSeek(R.id.seekPosY, R.id.lblPosY, "pos_y", "Position Y")
        setupSeek(R.id.seekWidgetScale, R.id.lblWidgetScale, "widget_scale", "Size Scale")
    }

    // -------------------------------------------------------------
    // STATUS CHECK (Shows Active / Not Active icons)
    // -------------------------------------------------------------

    private fun checkStatus() {
        val colorSuccess = Color.parseColor("#B7F397")
        val colorFail = Color.parseColor("#FFB4AB")
        val bgSuccess = Color.parseColor("#223322")
        val bgFail = Color.parseColor("#332222")

        fun updateBtn(id: Int, active: Boolean, txtGood: String, txtBad: String) {
            val btn = findViewById<Button>(id)
            if (active) {
                btn.text = "✅ $txtGood"
                btn.setTextColor(colorSuccess)
                btn.background.setTint(bgSuccess)
            } else {
                btn.text = "❌ $txtBad"
                btn.setTextColor(colorFail)
                btn.background.setTint(bgFail)
            }
        }

        val wpManager = WallpaperManager.getInstance(this)
        val info = wpManager.wallpaperInfo
        val isWallActive = info != null && info.packageName == packageName
        updateBtn(R.id.btnSetWallpaper, isWallActive, "Active", "Set WP")

        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isListActive = listeners != null && listeners.contains(packageName)
        updateBtn(R.id.btnPermissions, isListActive, "Active", "Enable")

        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        updateBtn(R.id.btnMic, hasMic, "Active", "Enable")
    }
}
