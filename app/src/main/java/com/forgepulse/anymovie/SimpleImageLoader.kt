package com.forgepulse.anymovie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Cancellable-by-tag, size-limited image loader for native posters; no full-size image caching. */
object SimpleImageLoader {
    private const val MAX_IMAGE_BYTES = 7 * 1024 * 1024
    private val executor = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = (bitmap.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(view: ImageView, url: String?) {
        val normalized = url?.takeIf { it.startsWith("https://") }
        view.tag = normalized
        view.setImageDrawable(null)
        if (normalized == null) return
        synchronized(cache) { cache.get(normalized) }?.let { view.setImageBitmap(it); return }
        executor.execute {
            val bitmap = runCatching {
                val conn = (URL(normalized).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AnyMovie/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "image/webp,image/jpeg,image/png,*/*")
                }
                try {
                    if (conn.responseCode !in 200..299 || conn.contentLengthLong > MAX_IMAGE_BYTES) return@runCatching null
                    val bytes = conn.inputStream.use { stream ->
                        ByteArrayOutputStream().use { output ->
                            val chunk = ByteArray(8_192)
                            var count = 0
                            while (true) {
                                val read = stream.read(chunk)
                                if (read < 0) break
                                count += read
                                if (count > MAX_IMAGE_BYTES) return@runCatching null
                                output.write(chunk, 0, read)
                            }
                            output.toByteArray()
                        }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally { conn.disconnect() }
            }.getOrNull() ?: return@execute
            synchronized(cache) { cache.put(normalized, bitmap) }
            view.post {
                if (view.tag == normalized) {
                    view.alpha = 0.5f
                    view.setImageBitmap(bitmap)
                    view.animate().alpha(1f).setDuration(180).start()
                }
            }
        }
    }
}
