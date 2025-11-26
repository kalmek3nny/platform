package com.gushypushy.diffusereborn

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.palette.graphics.Palette

class MusicListenerService : NotificationListenerService() {

    companion object {
        var currentColors: List<Int> = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        var currentTitle: String = "Diffuse Reborn"
        var currentArtist: String = "Waiting for music..."
        var currentAlbumArt: Bitmap? = null

        private var isHomeVisible = false
        private var instance: MusicListenerService? = null

        fun setHomeState(visible: Boolean) {
            isHomeVisible = visible
            if (visible) instance?.startPolling()
            else instance?.stopPolling()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunner = object : Runnable {
        override fun run() {
            if (!isHomeVisible) return
            if (!checkMediaSession()) checkActiveNotifications()
            handler.postDelayed(this, 500)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        stopPolling()
    }

    fun startPolling() {
        handler.removeCallbacks(pollRunner)
        handler.post(pollRunner)
    }

    fun stopPolling() {
        handler.removeCallbacks(pollRunner)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (isHomeVisible) {
            try {
                if (checkMediaSession()) return
                checkNotificationExtras(sbn)
            } catch (e: Exception) { }
        }
    }

    private fun checkActiveNotifications() {
        try {
            val notifications = activeNotifications
            for (sbn in notifications) checkNotificationExtras(sbn)
        } catch (e: Exception) {}
    }

    private fun checkMediaSession(): Boolean {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicListenerService::class.java)
            val controllers = manager.getActiveSessions(component)
            for (controller in controllers) {
                val metadata = controller.metadata
                if (metadata != null) {
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    if (!title.isNullOrEmpty()) {
                        currentTitle = title
                        currentArtist = artist ?: ""
                    }

                    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    if (bitmap != null && !bitmap.isRecycled) {
                        processBitmapFast(bitmap)
                        return true
                    }
                }
            }
        } catch (e: Exception) { }
        return false
    }

    private fun checkNotificationExtras(sbn: StatusBarNotification?) {
        val extras = sbn?.notification?.extras ?: return
        val title = extras.getString("android.title")
        val artist = extras.getString("android.text")
        if (!title.isNullOrEmpty()) {
            currentTitle = title
            currentArtist = artist ?: ""
        }

        val bitmap = getBitmapFromBundle(extras, "android.largeIcon")
            ?: getBitmapFromBundle(extras, "android.picture")
        if (bitmap != null && !bitmap.isRecycled) processBitmapFast(bitmap)
    }

    private fun getBitmapFromBundle(bundle: Bundle, key: String): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 33) {
            bundle.getParcelable(key, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable(key)
        }
    }

    private fun processBitmapFast(original: Bitmap) {
        try {
            var workableBitmap = original
            if (original.config == Bitmap.Config.HARDWARE) {
                workableBitmap = original.copy(Bitmap.Config.ARGB_8888, false)
            }

            // HIGH RES UPDATE: 600x600
            val tinyBitmap = Bitmap.createScaledBitmap(workableBitmap, 600, 600, false)
            currentAlbumArt = tinyBitmap

            // For colors, we still use a tiny version to keep it fast
            val colorBitmap = Bitmap.createScaledBitmap(workableBitmap, 50, 50, false)
            Palette.from(colorBitmap).generate { palette ->
                if (palette != null) {
                    val c1 = palette.getVibrantColor(0)
                    val c2 = palette.getLightVibrantColor(0)
                    val c3 = palette.getDarkVibrantColor(0)
                    val c4 = palette.getMutedColor(0)
                    val c5 = palette.getDominantColor(0)
                    val distinctList = mutableListOf<Int>()
                    if (c1 != 0) distinctList.add(c1)
                    if (c2 != 0) distinctList.add(c2)
                    if (c3 != 0) distinctList.add(c3)
                    if (c4 != 0) distinctList.add(c4)
                    if (c5 != 0) distinctList.add(c5)
                    if (distinctList.isNotEmpty()) {
                        while (distinctList.size < 5) distinctList.add(distinctList[0])
                        if (currentColors != distinctList) currentColors = distinctList
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}