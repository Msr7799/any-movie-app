package com.forgepulse.anymovie

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class AudioExporter(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun export(sourceUrl: String, title: String, callback: (Result<File>) -> Unit) {
        executor.execute {
            val result = runCatching { extract(sourceUrl, title) }
            main.post { callback(result) }
        }
    }

    fun close() = executor.shutdownNow()

    private fun extract(sourceUrl: String, title: String): File {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) { "Only HTTP/HTTPS sources are supported" }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceUrl, mapOf("User-Agent" to "AnyMovie/${BuildConfig.VERSION_NAME}"))
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("NO_AUDIO_TRACK")
            val format = extractor.getTrackFormat(audioTrack)
            extractor.selectTrack(audioTrack)

            val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            directory.mkdirs()
            val safeTitle = title.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "").trim().take(80).ifBlank { "audio" }
            val target = uniqueFile(directory, "$safeTitle.m4a")
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()

            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 4 * 1024 * 1024)
            } else 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
            }
            return target
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "m4a")
        var candidate = File(directory, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index).$extension")
            index += 1
        }
        return candidate
    }
}
