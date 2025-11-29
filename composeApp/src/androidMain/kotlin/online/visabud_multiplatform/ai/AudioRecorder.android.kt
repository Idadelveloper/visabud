package online.visabud.app.visabud_multiplatform.ai

import android.media.MediaRecorder
import java.io.File

/**
 * Simple on-device audio recorder actual for Android.
 * Uses MediaRecorder to capture mic input into cache directory as M4A (AAC).
 */
private class AndroidAudioRecorder : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    override suspend fun start(): Boolean {
        return try {
            stopInternal()
            val cache = try {
                // Try to obtain application cache dir via ActivityThread
                val app = currentApplication()
                app?.cacheDir
            } catch (_: Throwable) { null }
            val dir = cache ?: File("/sdcard/Download").takeIf { it.exists() } ?: File("/sdcard")
            outFile = File(dir, "visabud_rec_${System.currentTimeMillis()}.m4a")
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(outFile!!.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (_: Throwable) {
            stopInternal()
            false
        }
    }

    override suspend fun stop(): String? {
        val path = outFile?.absolutePath
        stopInternal()
        return path
    }

    override fun isRecording(): Boolean = recorder != null

    private fun stopInternal() {
        try {
            recorder?.apply {
                runCatching { stop() }
                runCatching { reset() }
                runCatching { release() }
            }
        } catch (_: Throwable) { }
        recorder = null
    }
}

@Suppress("PrivateApi")
private fun currentApplication(): android.app.Application? {
    return try {
        val clazz = Class.forName("android.app.ActivityThread")
        val method = clazz.getDeclaredMethod("currentApplication")
        method.invoke(null) as? android.app.Application
    } catch (_: Throwable) { null }
}

actual fun audioRecorder(platform: Any?): AudioRecorder = AndroidAudioRecorder()
