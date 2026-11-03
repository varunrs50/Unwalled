package app.unwalled.mirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Phone sound for the Phone Screen window: other apps' playback, captured through the approved
 * screen-sharing session (Android's playback capture, which apps can opt out of and which never
 * includes calls), encoded as AAC and handed to [send] as packets of kind 4 (codec config) and 5.
 */
class AudioCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val send: (kind: Int, pts: Long, data: ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val allowed: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !allowed) return
        running = true
        thread = Thread({ run() }, "audio-capture").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    @Suppress("MissingPermission") // checked in start()
    private fun run() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES) * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrElse { Log.w(TAG, "Couldn't start sound capture: ${it.message}"); running = false; return }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 2)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        record.startRecording()
        Log.i(TAG, "Sound capture started")

        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                // Timestamps on the same clock as the video encoder's, so the Mac can line them up.
                val pts = System.nanoTime() / 1_000
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.getInputBuffer(inIndex)?.apply { clear(); put(pcm, 0, n) }
                    encoder.queueInputBuffer(inIndex, 0, n, pts, 0)
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex < 0) break
                    val buffer = encoder.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        send(if (config) 4 else 5, info.presentationTimeUs, bytes)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sound capture stopped: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { encoder.stop() }
            encoder.release()
            running = false
            Log.i(TAG, "Sound capture ended")
        }
    }

    companion object {
        private const val TAG = "Unwalled"
        private const val SAMPLE_RATE = 48_000
        private const val BIT_RATE = 128_000
        /** One AAC frame: 1024 stereo 16-bit samples. */
        private const val FRAME_BYTES = 1024 * 2 * 2
    }
}

/**
 * Silences the phone's own speaker while its sound plays on the Mac. Playback capture happens before
 * the phone's volume is applied, so the Mac still gets full sound. The volume is saved to storage
 * so it comes back even if Unwalled stops unexpectedly, and left alone if the user changed it.
 */
internal class SpeakerMute(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("mirror", Context.MODE_PRIVATE)

    fun mute() {
        if (prefs.contains(SAVED)) return
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volume == 0) return
        prefs.edit().putInt(SAVED, volume).apply()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        Log.i(TAG, "Phone speaker muted while sound plays on the Mac")
    }

    fun restore() {
        val volume = prefs.getInt(SAVED, -1)
        if (volume < 0) return
        prefs.edit().remove(SAVED).apply()
        // Only if it's still muted; otherwise the user has set a volume themselves.
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
            Log.i(TAG, "Phone speaker volume restored")
        }
    }

    private companion object {
        const val TAG = "Unwalled"
        const val SAVED = "savedMusicVolume"
    }
}
