package com.gushypushy.diffusereborn

import android.app.WallpaperColors
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.graphics.*
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.min

class DiffuseWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return DiffuseEngine()
    }

    inner class DiffuseEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener,
        DisplayManager.DisplayListener {

        private val handler = Handler(Looper.getMainLooper())

        private lateinit var prefs: SharedPreferences
        private var displayManager: DisplayManager? = null
        private var visualizer: Visualizer? = null

        // -----------------------------
        // SPRITES (MAJOR FPS BOOST)
        // -----------------------------
        private lateinit var baseBlob: Bitmap
        private lateinit var blobSprites: Array<Bitmap>    // 5 pre-scaled sprites
        private val blobPaints = Array(5) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }}

        private val dest = RectF()

        // -----------------------------
        // STATE / SETTINGS
        // -----------------------------
        private var isAmbient = false
        private var isTouching = false
        private var touchX = -10000f
        private var touchY = -10000f

        private var screenW = 0
        private var screenH = 0

        // motion + offsets
        private val blobOffsetX = FloatArray(5)
        private val blobOffsetY = FloatArray(5)

        private var time = 0f
        private val positions = FloatArray(10)

        // music-reactive
        private var bass = 0f
        private var treble = 0f

        // user settings
        private var userSpeed = 0.02f
        private var userSize = 1f
        private var userBright = 1f
        private var userBeatPulse = 1f
        private var userBeatShake = 1f
        private var userDensity = 180
        private var userWander = 1f
        private var userSat = 1f
        private var userVign = 0f
        private var isDust = true
        private var dustOpacity = 0f
        private var dustSize = 1f
        private var dustCount = 50
        private var isWidget = true
        private var widgetX = 0.5f
        private var widgetY = 0.5f
        private var widgetScale = 1f
        private var isTouch = true
        private var saver = false
        private var oled = false
        private var light = false
        private var debug = false

        // particles
        private val MAX = 200
        private val pX = FloatArray(MAX)
        private val pY = FloatArray(MAX)
        private val pSpeed = FloatArray(MAX)
        private val pAlpha = FloatArray(MAX)
        private val pPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        // widgets
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(5f, 0f,0f, Color.BLACK)
        }
        private val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            setShadowLayer(3f, 0f,0f, Color.BLACK)
        }
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        // vignette
        private val vignPaint = Paint()

        // color fading
        private var drawingColors = MusicListenerService.currentColors.toMutableList()
        private var oldColors = drawingColors.toList()
        private var targetColors = drawingColors.toList()
        private var fade = 1f
        private val fadeSpeed = 0.015f

        // debug fps
        private var fps = 0
        private var frames = 0
        private var lastTime = 0L
        private val fpsPaint = Paint().apply {
            color = Color.GREEN
            textSize = 60f
            setShadowLayer(5f, 2f,2f, Color.BLACK)
        }

        // DRAW LOOP =====================================================
        private val drawLoop = object : Runnable {
            override fun run() {
                draw()
                val delay =
                    if (isAmbient) 1000L
                    else if (saver) 33L
                    else 8L
                handler.postDelayed(this, delay)
            }
        }

        // ===============================================================
        // INIT
        // ===============================================================
        init {
            createSprites()
            for (i in 0 until MAX) {
                pX[i] = (Math.random()*800).toFloat()
                pY[i] = (Math.random()*1800).toFloat()
                pSpeed[i] = (1f + Math.random()*2).toFloat()
                pAlpha[i] = (Math.random()*255).toFloat()
            }
        }

        private fun createSprites() {
            // base blob — 256px radial alpha
            val size = 256
            baseBlob = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(baseBlob)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size/2f, size/2f, size/2f,
                    Color.WHITE, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            c.drawCircle(size/2f, size/2f, size/2f, p)

            // 5 pre-scaled sprites (NO runtime scaling anymore)
            blobSprites = Array(5) { i ->
                val scale = 0.8f + i * 0.25f
                val s = (size * scale).toInt()
                Bitmap.createScaledBitmap(baseBlob, s, s, true)
            }
        }

        // ===============================================================
        // TOUCH
        // ===============================================================
        override fun onTouchEvent(e: MotionEvent?) {
            if (!isTouch || isAmbient || e == null) return
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touchX = e.x; touchY = e.y; isTouching = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    touchX = -10000f
                    touchY = -10000f
                }
            }
        }

        // ===============================================================
        // SYSTEM EVENTS
        // ===============================================================
        override fun onCreate(holder: SurfaceHolder?) {
            super.onCreate(holder)
            setTouchEventsEnabled(true)
            prefs = getSharedPreferences("diffuse_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)

            updateSettings()

            displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager?.registerDisplayListener(this, null)
            checkAmbient()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            displayManager?.unregisterDisplayListener(this)
            visualizer?.release()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            w: Int,
            h: Int
        ) {
            screenW = w
            screenH = h

            for (i in 0 until MAX) {
                pX[i] = (Math.random()*w).toFloat()
                pY[i] = (Math.random()*h).toFloat()
            }

            val rad = hypot(w.toDouble(), h.toDouble()).toFloat()
            vignPaint.shader = RadialGradient(
                w/2f, h/2f, rad,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0.6f, 1f),
                Shader.TileMode.CLAMP
            )

            super.onSurfaceChanged(holder, format, w, h)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                handler.post(drawLoop)
                tryVisualizer()
            } else {
                handler.removeCallbacks(drawLoop)
                visualizer?.release()
                visualizer = null
            }
        }

        override fun onDisplayChanged(id: Int) { checkAmbient() }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}

        private fun checkAmbient() {
            val disp = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val state = disp?.state ?: Display.STATE_ON
            val amb = (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND)
            if (amb != isAmbient) {
                isAmbient = amb
                if (amb) visualizer?.release()
            }
        }

        // ===============================================================
        // VISUALIZER
        // ===============================================================
        private fun tryVisualizer() {
            if (!hasMicPermission()) return
            try {
                visualizer = Visualizer(0)
                visualizer?.captureSize = Visualizer.getCaptureSizeRange()[1]
                visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, sr: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                        if (fft == null) return

                        var bassAcc = 0f
                        for (i in 1 until 3) {
                            val r = fft[i*2].toFloat()
                            val im = fft[i*2+1].toFloat()
                            bassAcc += hypot(r, im)
                        }
                        val rawBass = bassAcc / 2
                        bass = if (rawBass > bass) rawBass else bass * 0.92f

                        var treAcc = 0f
                        for (i in 10 until 30) {
                            val r = fft[i*2].toFloat()
                            val im = fft[i*2+1].toFloat()
                            treAcc += hypot(r, im)
                        }
                        val rawTre = treAcc / 20
                        treble = if (rawTre > treble) rawTre else treble * 0.8f
                    }
                },
                    Visualizer.getMaxCaptureRate()/2,
                    false,
                    true
                )
                visualizer?.enabled = true
            } catch (_: Exception) {}
        }

        private fun hasMicPermission(): Boolean {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        }

        // ===============================================================
        // SETTINGS
        // ===============================================================
        override fun onSharedPreferenceChanged(
            p: SharedPreferences?,
            k: String?
        ) { updateSettings() }

        private fun updateSettings() {
            userSpeed      = 0.0001f + prefs.getInt("speed", 50)/100f * 0.08f
            userSize       = 0.5f + prefs.getInt("size", 50)/100f
            userBright     = 0.05f + prefs.getInt("bright", 50)/100f * 0.25f
            userBeatPulse  = prefs.getInt("beat", 50)/50f * 2f
            userBeatShake  = prefs.getInt("shake", 20)/100f * 2f
            userDensity    = 100 + (prefs.getInt("density", 50)/100f * 155).toInt()
            userWander     = 0.2f + prefs.getInt("wander", 50)/100f * 1.5f
            userSat        = 1f + prefs.getInt("sat", 50)/100f
            userVign       = prefs.getInt("vignette", 0).toFloat()
            isDust         = prefs.getBoolean("dust_on", true)
            dustOpacity    = prefs.getInt("dust", 50)/100f * 255f
            dustSize       = 0.2f + prefs.getInt("dust_size", 50)/100f * 3f
            dustCount      = (prefs.getInt("dust_count", 50)/100f * MAX).toInt()
            isWidget       = prefs.getBoolean("widget_on", true)
            widgetScale    = 0.5f + prefs.getInt("widget_scale", 50)/50f
            widgetX        = prefs.getInt("pos_x", 50)/100f
            widgetY        = prefs.getInt("pos_y", 50)/100f
            isTouch        = prefs.getBoolean("touch_mode", true)
            saver          = prefs.getBoolean("power_saver", false)
            oled           = prefs.getBoolean("oled_mode", false)
            light          = prefs.getBoolean("light_mode", false)
            debug          = prefs.getBoolean("debug_mode", false)
        }

        // ===============================================================
        // DRAW FRAME (OPTIMIZED)
        // ===============================================================
        private fun draw() {
            val holder = surfaceHolder
            val canvas = holder.lockCanvas() ?: return

            try {
                if (isAmbient) {
                    canvas.drawColor(Color.BLACK)
                    drawWidgetAOD(canvas)
                    return
                }

                if (debug) updateFPS()

                // color transitions
                val newColors = MusicListenerService.currentColors
                if (newColors != targetColors) {
                    oldColors = drawingColors.toList()
                    targetColors = newColors
                    fade = 0f
                }
                if (fade < 1f) {
                    fade = (fade + fadeSpeed).coerceAtMost(1f)
                    for (i in 0 until 5) {
                        drawingColors[i] = lerpColor(oldColors[i], targetColors[i], fade)
                    }
                }

                // background
                when {
                    light -> canvas.drawColor(Color.WHITE)
                    oled  -> canvas.drawColor(Color.BLACK)
                    else -> {
                        val c = drawingColors[0]
                        canvas.drawColor(Color.rgb(
                            (Color.red(c)*userBright).toInt(),
                            (Color.green(c)*userBright).toInt(),
                            (Color.blue(c)*userBright).toInt()
                        ))
                    }
                }

                // animate blobs
                time += userSpeed
                val pulse = bass * userBeatPulse
                val jitterX = ((Math.random()-0.5)*bass*userBeatShake*20).toFloat()
                val jitterY = ((Math.random()-0.5)*bass*userBeatShake*20).toFloat()

                val wanderX = screenW * 0.25f * userWander
                val wanderY = screenH * 0.15f * userWander

                // compute 5 blob centers
                positions[0] = screenW*0.4f + sin(time*0.7f)*wanderX
                positions[1] = screenH*0.3f + cos(time*0.8f)*wanderY

                positions[2] = screenW*0.7f + sin(time+2f)*wanderX
                positions[3] = screenH*0.5f + cos(time*0.9f)*wanderY

                positions[4] = screenW*0.3f + cos(time+4f)*wanderX
                positions[5] = screenH*0.7f + sin(time*1.3f)*wanderY

                positions[6] = screenW*0.8f + cos(time*0.6f)*wanderX
                positions[7] = screenH*0.2f + sin(time*1.1f)*wanderY

                positions[8] = screenW*0.5f + sin(time*1.5f)*wanderX
                positions[9] = screenH*0.8f + cos(time*0.7f)*wanderY

                // draw 5 blobs
                val alpha = (userDensity + pulse*25).toInt().coerceAtMost(255)

                for (i in 0 until 5) {
                    val blob = blobSprites[i]
                    val x = positions[i*2] + jitterX + blobOffsetX[i]
                    val y = positions[i*2+1] + jitterY + blobOffsetY[i]

                    if (isTouching) applyBlobTouch(i, x, y)

                    blobOffsetX[i] *= 0.92f
                    blobOffsetY[i] *= 0.92f

                    blobPaints[i].alpha = alpha
                    blobPaints[i].colorFilter =
                        PorterDuffColorFilter(drawingColors[i], PorterDuff.Mode.SRC_IN)

                    val scale = userSize + pulse*0.08f
                    val w = blob.width * scale
                    val h = blob.height * scale

                    dest.set(x - w/2, y - h/2, x + w/2, y + h/2)
                    canvas.drawBitmap(blob, null, dest, blobPaints[i])
                }

                // particles
                if (isDust && dustOpacity > 5) {
                    val treBoost = min(treble*5f, 10f)
                    pPaint.alpha = dustOpacity.toInt()

                    for (i in 0 until dustCount) {
                        pY[i] -= (pSpeed[i] + treBoost)
                        if (pY[i] < 0) {
                            pY[i] = screenH.toFloat()
                            pX[i] = (Math.random()*screenW).toFloat()
                        }
                        val size = (dustSize * 0.07f + treble*0.2f) * (screenW/100f)
                        canvas.drawCircle(pX[i], pY[i], size, pPaint)
                    }
                }

                // vignette
                if (userVign > 0) {
                    val vigAlpha = (userVign + (bass*100)).toInt().coerceIn(0,255)
                    vignPaint.alpha = vigAlpha
                    canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), vignPaint)
                }

                // widget
                if (isWidget) drawWidget(canvas)

                // fps debug
                if (debug) canvas.drawText("FPS: $fps", 100f,200f, fpsPaint)

            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        // ===============================================================
        // HELPERS
        // ===============================================================
        private fun applyBlobTouch(i: Int, x: Float, y: Float) {
            val dx = x - touchX
            val dy = y - touchY
            val dist = hypot(dx, dy)
            val radius = screenW * 0.3f
            if (dist < radius) {
                val force = (radius - dist)/radius
                blobOffsetX[i] += dx*force*0.15f
                blobOffsetY[i] += dy*force*0.15f
            }
        }

        private fun drawWidget(canvas: Canvas) {
            val art = MusicListenerService.currentAlbumArt ?: return

            val x = screenW * widgetX
            val y = screenH * widgetY
            val size = 300f * widgetScale

            dest.set(x-size/2, y-size/2, x+size/2, y+size/2)
            canvas.drawBitmap(art, null, dest, artPaint)

            titlePaint.textSize = 50f * widgetScale
            artistPaint.textSize = 35f * widgetScale

            val title = MusicListenerService.currentTitle
            val artist = MusicListenerService.currentArtist

            val textY = y + size/2 + 70f*widgetScale

            if (title.length > 15) {
                titlePaint.textAlign = Paint.Align.LEFT
                val width = titlePaint.measureText(title)
                val gap = 100f
                val loop = width + gap
                val scroll = (time*50f) % loop

                canvas.save()
                canvas.clipRect(x-size/2-50f, textY-80f, x+size/2+50f, textY+30f)
                canvas.drawText(title, x-size/2-50f - scroll, textY, titlePaint)
                canvas.drawText(title, x-size/2-50f - scroll + loop, textY, titlePaint)
                canvas.restore()

                titlePaint.textAlign = Paint.Align.CENTER
            } else {
                canvas.drawText(title, x, textY, titlePaint)
            }
            canvas.drawText(artist, x, textY + 50f*widgetScale, artistPaint)
        }

        private fun drawWidgetAOD(canvas: Canvas) {
            val art = MusicListenerService.currentAlbumArt ?: return

            val x = screenW*0.5f
            val y = screenH*0.75f
            val size = 300f * widgetScale * 1.3f

            dest.set(x-size/2, y-size/2, x+size/2, y+size/2)
            canvas.drawBitmap(art, null, dest, artPaint)

            titlePaint.textSize = 50f * widgetScale
            artistPaint.textSize = 35f * widgetScale

            canvas.drawText(MusicListenerService.currentTitle, x, y+size/2+70f, titlePaint)
            canvas.drawText(MusicListenerService.currentArtist, x, y+size/2+120f, artistPaint)
        }

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
            val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)

            var r = (ar + (br-ar)*t).toInt()
            var g = (ag + (bg-ag)*t).toInt()
            var b2 = (ab + (bb-ab)*t).toInt()

            if (userSat > 1f) {
                val hsv = FloatArray(3)
                Color.RGBToHSV(r,g,b2,hsv)
                hsv[1] *= userSat
                return Color.HSVToColor(hsv)
            }

            return Color.rgb(r,g,b2)
        }

        private fun updateFPS() {
            frames++
            val now = System.currentTimeMillis()
            if (now - lastTime > 1000) {
                fps = frames
                frames = 0
                lastTime = now
            }
        }
    }
}
