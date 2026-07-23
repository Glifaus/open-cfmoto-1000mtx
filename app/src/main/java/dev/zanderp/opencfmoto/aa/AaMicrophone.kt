// SPDX-License-Identifier: AGPL-3.0-or-later
// OpenCfMoto glue (uses AGPLv3 protocol from headunit-revived). Streams the phone's microphone to
// Android Auto over the AAP MIC channel, so "Hey Google" / the Assistant button works — the only
// hands-free way to set a destination on a bike. Ported from the ionutradu252/open-cfmoto fork.
package dev.zanderp.opencfmoto.aa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dev.zanderp.opencfmoto.aa.proto.Common
import dev.zanderp.opencfmoto.aa.proto.Media
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The head unit's microphone, as far as Android Auto is concerned.
 *
 * AA asks for the mic with MICROPHONE_REQUEST(open=true) whenever the Assistant starts; we answer
 * MICROPHONE_RESPONSE and then push raw PCM up the MIC channel until it asks us to close.
 *
 * Audio source: the phone's `VOICE_RECOGNITION` input. On a bike the rider's mic is usually a helmet
 * headset paired to the BIKE, which bridges it to the phone as a Bluetooth headset — so we route
 * capture to the Bluetooth SCO device when one is present ([preferBluetoothMic]); otherwise the
 * phone's own mic is used.
 *
 * Format must match what [ServiceDiscoveryResponse] advertises: 16 kHz, 16-bit, mono.
 */
class AaMicrophone(
    private val context: Context,
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        /** ~20 ms of audio per message — small enough for snappy voice, big enough to avoid spam. */
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 50

        /**
         * AAP microphone media packets use the raw-media layout from Headunit Revived:
         * `[channel][flags][timestamp ms BE][PCM16 LE…]`.
         *
         * Bytes 2–3 are replaced with the encrypted payload length by [AapTransport], which is why
         * the raw message starts its timestamp at offset 2 and does not include a message type.
         */
        internal fun buildMediaData(
            samples: ShortArray,
            count: Int,
            timestampMs: Long,
        ): AapMessage {
            require(count in 0..samples.size)
            val total = 10 + count * 2
            val data = ByteArray(total)
            data[0] = Channel.ID_MIC.toByte()
            data[1] = 0x0b
            Utils.put_time(2, data, timestampMs)
            val pcm = ByteBuffer.wrap(data, 10, count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) pcm.putShort(samples[i])
            return AapMessage(Channel.ID_MIC, 0x0b, -1, 2, total, data)
        }
    }

    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var sessionId = 0

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Handle AA's MICROPHONE_REQUEST: open or close the mic, and answer it. */
    fun onRequest(open: Boolean, channel: Int) {
        if (open) start() else stop("AA closed the mic")
        // Answer either way, so AA isn't left waiting. If the open failed (no permission, mic busy)
        // say so rather than claiming success — otherwise AA sits listening to a stream we never send.
        val status = if (!open || recording) Common.MessageStatus.STATUS_SUCCESS_VALUE
                     else Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
        transport.send(
            AapMessage(
                channel, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
                Media.MicrophoneResponse.newBuilder()
                    .setStatus(status).setSessionId(sessionId).build()
            )
        )
        log("[MIC] request open=$open → ${if (recording) "recording" else "closed"}")
    }

    fun setSessionId(id: Int) { sessionId = id }

    @SuppressLint("MissingPermission") // Guarded by hasPermission() immediately below.
    private fun start() {
        if (recording) return
        if (!hasPermission()) {
            log("[MIC] no RECORD_AUDIO permission — voice won't work. Grant it in the app.")
            return
        }
        try {
            dev.zanderp.opencfmoto.AndroidAutoService.updateForegroundType()
        } catch (e: Exception) {
            log("[MIC] failed to update service foreground type: $e")
        }
        try {
            preferBluetoothMic()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SAMPLES * 2 * 4)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                log("[MIC] AudioRecord init failed"); r.release(); return
            }
            recorder = r
            recording = true
            r.startRecording()
            thread = thread(name = "aa-mic", isDaemon = true) { pump(r) }
            log("[MIC] recording started (${SAMPLE_RATE}Hz mono) → Android Auto")
        } catch (e: Exception) {
            log("[MIC] start failed: $e")
            recording = false
        }
    }

    /**
     * Prefer the rider's Bluetooth mic (helmet → bike → phone) over the phone's own, which is
     * useless in a pocket at speed.
     */
    private fun preferBluetoothMic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bt = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (bt != null) {
                    val accepted = am.setCommunicationDevice(bt)
                    log(
                        "[MIC] Bluetooth route requested: type=${bt.type} accepted=$accepted",
                    )
                } else {
                    log("[MIC] no Bluetooth mic available — using the phone's mic")
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
            }
        } catch (e: Exception) {
            log("[MIC] bluetooth mic routing failed ($e) — using the phone's mic")
        }
    }

    private fun releaseBluetoothMic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else @Suppress("DEPRECATION") am.stopBluetoothSco()
        } catch (_: Exception) {}
    }

    /** Read PCM and push it up the MIC channel as AAP media-data messages. */
    private fun pump(r: AudioRecord) {
        val buf = ShortArray(CHUNK_SAMPLES)
        var sent = 0L
        var levelSamples = 0L
        var sumSquares = 0.0
        var peak = 0
        val route = r.routedDevice
        log(
            "[MIC] active input: type=${route?.type ?: 0} state=${r.recordingState}",
        )
        while (recording) {
            val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { break }
            if (n <= 0) continue
            try {
                for (i in 0 until n) {
                    val sample = buf[i].toInt()
                    sumSquares += sample.toDouble() * sample
                    peak = maxOf(peak, abs(sample))
                }
                levelSamples += n
                transport.send(buildMediaData(buf, n, SystemClock.elapsedRealtime()))
                sent++
                if (sent <= 2L) log("[MIC] chunks sent=$sent")
                if (sent % 50L == 0L) {
                    val rms = if (levelSamples == 0L) 0 else sqrt(sumSquares / levelSamples).toInt()
                    log("[MIC] signal: chunks=$sent rms=$rms peak=$peak")
                    levelSamples = 0
                    sumSquares = 0.0
                    peak = 0
                }
            } catch (e: Exception) {
                log("[MIC] send failed: $e"); break
            }
        }
    }

    fun stop(reason: String) {
        if (!recording && recorder == null) return
        recording = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        releaseBluetoothMic()
        log("[MIC] stopped ($reason)")
    }
}
