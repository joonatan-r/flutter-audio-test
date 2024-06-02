package com.example.audiotest

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.jtransforms.fft.DoubleFFT_1D
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos

class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "example.audiotest/data"
        private const val EVENT_CHANNEL = "example.audiotest/stream"
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(
            StreamHandler
        )

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            if (call.method == "getData") {
                val data = getData()

                if (data != "") {
                    result.success(data)
                } else {
                    result.error("UNAVAILABLE", "Data not available.", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun getData(): String {
        return "This is a test"
    }

    @SuppressLint("MissingPermission")
    object StreamHandler : EventChannel.StreamHandler {
        private var handler = Handler(Looper.getMainLooper())
        private var eventSink: EventChannel.EventSink? = null

        private const val SAMPLING_RATE_IN_HZ = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /**
         * Factor by that the minimum buffer size is multiplied. The bigger the factor is the less
         * likely it is that samples will be dropped, but more memory will be used. The minimum
         * buffer size is determined by {@link AudioRecord#getMinBufferSize(int, int, int)} and
         * depends on the recording settings.
         */
        private const val BUFFER_SIZE_FACTOR = 2
        val BUFFER_SIZE =
            AudioRecord.getMinBufferSize(
                SAMPLING_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
        val recordingInProgress = AtomicBoolean(false)
        var recorder: AudioRecord? = null
        private var recordingThread: Thread? = null
        val data = AtomicInteger()

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            eventSink = events
            val r = object : Runnable {
                var counter = 0
                @SuppressLint("DefaultLocale")
                override fun run() {
                    handler.post {
                        counter++
                        val freq = (1 / data.get().toDouble()) * SAMPLING_RATE_IN_HZ
                        val result =
                            if (!freq.isFinite() || freq.toInt() == SAMPLING_RATE_IN_HZ) {
                                "-"
                            } else {
                                String.format("%.2f", freq)
                            }
                        eventSink?.success(result)
                    }
                    handler.postDelayed(this, 100)

//                    if (counter > 600 && recordingInProgress.get()) {
//                        stopRecording()
//                    }
                    if (counter > 11 && !recordingInProgress.get()) {
                        startRecording()
                    }
                }
            }
            handler.postDelayed(r, 100)
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }

        fun startRecording() {
            println("__startRecording__")
            recorder = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLING_RATE_IN_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            println("state is ${recorder?.state}")
            recorder?.startRecording()
            recordingInProgress.set(true)
            recordingThread = Thread(RecordingRunnable(), "Recording Thread")
            recordingThread?.start()
        }

        fun stopRecording() {
            println("__stopRecording__")
            if (null == recorder) {
                return
            }
            recordingInProgress.set(false)
            recorder?.stop()
            recorder?.release()
            recorder = null
            recordingThread = null
        }

        class RecordingRunnable : Runnable {

            private val bufferSize = BUFFER_SIZE / 2
            private val fft1d = DoubleFFT_1D(bufferSize.toLong())
            private val fft1dForInv = DoubleFFT_1D((bufferSize / 2).toLong())

            override fun run() {
                try {
                    val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    while (recordingInProgress.get()) {
                        val result: Int = recorder?.read(buffer, BUFFER_SIZE) ?: -1
                        if (result < 0) {
                            throw RuntimeException(
                                "Reading of audio buffer failed: " +
                                        getBufferReadFailureReason(result)
                            )
                        }
                        val shortBuffer = ShortArray(bufferSize)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
                        data.set(findLargestMagnitudeSample(shortBuffer))
                        buffer.clear()
                        Thread.sleep(20)
                    }
                } catch (e: IOException) {
                    Log.e("Recorder", "Writing of recorded audio failed", e)
                }
            }

            private fun getBufferReadFailureReason(errorCode: Int): String {
                return when (errorCode) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                    AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                    AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                    AudioRecord.ERROR -> "ERROR"
                    else -> "Unknown ($errorCode)"
                }
            }

            // https://stackoverflow.com/questions/21799625/low-pass-android-pcm-audio-data
            // https://stackoverflow.com/questions/4225432/how-to-compute-frequency-of-data-using-fft

            private fun findLargestMagnitudeSample(data: ShortArray): Int {
                val fftBuffer = DoubleArray(bufferSize * 2)
                val magnitude = DoubleArray(bufferSize)
                var maxVal = 0.toDouble()
                var binNo = 0

                for (i in 0 until bufferSize) {
                    val windowVal = 1 - cos(i * 2 * Math.PI / (data.size - 1))
                    data[i] = (data[i] * windowVal).toInt().toShort()
                    fftBuffer[2 * i] = data[i].toDouble()
                    fftBuffer[2 * i + 1] = 0.toDouble()
                }
                fft1d.complexForward(fftBuffer)

                for (i in 0 until bufferSize) {
                    val real = fftBuffer[2 * i]
                    val imaginary = fftBuffer[2 * i + 1]
                    magnitude[i] = real * real + imaginary * imaginary
                }
                fft1dForInv.complexInverse(magnitude, false)

                for (i in magnitude.indices) {
                    if (magnitude[i] > maxVal) {
                        maxVal = magnitude[i]
                        binNo = i
                    }
                }
                return binNo
            }
        }
    }
}
