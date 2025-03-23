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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.abs

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
            val args = call.method.split(";").toTypedArray()
            if (args[0] == "toggle" && args.size >= 4) {
                val data = toggleListening(args)

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

    private fun toggleListening(args: Array<String>): String {
        val minMagnitude = args[1].toIntOrNull()
        val updateRate = args[2].toIntOrNull()
        val numFreqs = args[3].toIntOrNull()
        if (minMagnitude != null) {

        }
        if (updateRate != null) {

        }
        if (numFreqs != null) {

        }
        StreamHandler.listening.set(!StreamHandler.listening.get()); // not proper but whatever
        return "toggle"
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
        private const val MAX_HZ_TO_DISPLAY = 4000
        val recordingInProgress = AtomicBoolean(false)
        var recorder: AudioRecord? = null
        private var recordingThread: Thread? = null
        val listening = AtomicBoolean()
        val minMagnitude = AtomicLong(500_000_000_000)
        val updateRate = AtomicInteger(10)
        val numFreqs = AtomicInteger(2) // TODO

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            eventSink = events
            if (!recordingInProgress.get()) {
                startRecording()
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }

        @SuppressLint("DefaultLocale")
        private fun sampleToHz(sample: Int): String {
            val freq = (2 * SAMPLING_RATE_IN_HZ / sample.toDouble())
            return if (!listening.get() || !freq.isFinite() || freq > MAX_HZ_TO_DISPLAY) {
                    "-"
                } else {
                    "${String.format("%.2f", freq).padEnd(7)} (${Freqs.getClosest(freq)})"
                }
        }

        private fun startRecording() {
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

        private fun stopRecording() {
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
            private val fft1d = DoubleFFT_1D(BUFFER_SIZE.toLong())
            private var counter = 0;
            private var slowUpdate = "-"

            override fun run() {
                try {
                    val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    if (recordingInProgress.get()) {
                        val result: Int = recorder?.read(buffer, BUFFER_SIZE) ?: -1
                        if (result < 0) {
                            throw RuntimeException(
                                "Reading of audio buffer failed: " +
                                        getBufferReadFailureReason(result)
                            )
                        }
                        val shortBuffer = ShortArray(bufferSize)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
                        buffer.clear()
                        handler.post {
                            counter++;
                            val s = sampleToHz(findLargestMagnitudeSample(shortBuffer))
                            if (counter % updateRate.get() == 0 || slowUpdate == "-") {
                                slowUpdate = s
                            }
                            eventSink?.success("$slowUpdate\n\n$s")
                        }
                        handler.postDelayed(this, 100)
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
                val fftBuffer = DoubleArray(bufferSize * 4) // pad last half with 0s?
                val magnitude = DoubleArray(bufferSize * 4)
                var maxVal = 0.toDouble()
                var bin = 0

                for (i in 0 until bufferSize) {
                    val windowVal = 1 - cos(i * 2 * Math.PI / (data.size - 1))
                    fftBuffer[2 * i] = data[i] * windowVal
                    fftBuffer[2 * i + 1] = 0.toDouble()
                }
                fft1d.complexForward(fftBuffer)

                for (i in 0 until bufferSize * 2) {
                    val real = fftBuffer[2 * i]
                    val imaginary = fftBuffer[2 * i + 1]
                    magnitude[2 * i] = real * real + imaginary * imaginary
                    magnitude[2 * i + 1] = 0.toDouble()
                }
                fft1d.complexInverse(magnitude, false)

                for (i in magnitude.indices) {
                    if (
                        magnitude[i] > maxVal
                            && magnitude[i] > minMagnitude.get()
                            && (2 * SAMPLING_RATE_IN_HZ / i.toDouble()) < 700
                            && (2 * SAMPLING_RATE_IN_HZ / i.toDouble()) > 30
                    ) {
                        maxVal = magnitude[i]
                        bin = i
                    }
                }
                return bin
            }
        }
    }
}

object Freqs {
    val asMap =
        mapOf(
            "B7"                 to 3951.066,
            "A#7/Bb7"            to 3729.310,
            "A7"                 to 3520.000,
            "G#7/Ab7"            to 3322.438,
            "G7"                 to 3135.963,
            "F#7/Gb7"            to 2959.955,
            "F7"                 to 2793.826,
            "E7"                 to 2637.020,
            "D#7/Eb7"            to 2489.016,
            "D7"                 to 2349.318,
            "C#7/Db7"            to 2217.461,
            "C7"                 to 2093.005,
            "B6"                 to 1975.533,
            "A#6/Bb6"            to 1864.655,
            "A6"                 to 1760.000,
            "G#6/Ab6"            to 1661.219,
            "G6"                 to 1567.982,
            "F#6/Gb6"            to 1479.978,
            "F6"                 to 1396.913,
            "E6"                 to 1318.510,
            "D#6/Eb6"            to 1244.508,
            "D6"                 to 1174.659,
            "C#6/Db6"            to 1108.731,
            "C6"                 to 1046.502,
            "B5"                 to 987.7666,
            "A#5/Bb5"            to 932.3275,
            "A5"                 to 880.0000,
            "G#5/Ab5"            to 830.6094,
            "G5"                 to 783.9909,
            "F#5/Gb5"            to 739.9888,
            "F5"                 to 698.4565,
            "E5"                 to 659.2551,
            "D#5/Eb5"            to 622.2540,
            "D5"                 to 587.3295,
            "C#5/Db5"            to 554.3653,
            "C5"                 to 523.2511,
            "B4"                 to 493.8833,
            "A#4/Bb4"            to 466.1638,
            "A4"                 to 440.0000,
            "G#4/Ab4"            to 415.3047,
            "G4"                 to 391.9954,
            "F#4/Gb4"            to 369.9944,
            "F4"                 to 349.2282,
            "E4"                 to 329.6276,
            "D#4/Eb4"            to 311.1270,
            "D4"                 to 293.6648,
            "C#4/Db4"            to 277.1826,
            "C4"                 to 261.6256,
            "B3"                 to 246.9417,
            "A#3/Bb3"            to 233.0819,
            "A3"                 to 220.0000,
            "G#3/Ab3"            to 207.6523,
            "G3"                 to 195.9977,
            "F#3/Gb3"            to 184.9972,
            "F3"                 to 174.6141,
            "E3"                 to 164.8138,
            "D#3/Eb3"            to 155.5635,
            "D3"                 to 146.8324,
            "C#3/Db3"            to 138.5913,
            "C3"                 to 130.8128,
            "B2"                 to 123.4708,
            "A#2/Bb2"            to 116.5409,
            "A2"                 to 110.0000,
            "G#2/Ab2"            to 103.8262,
            "G2"                 to 97.99886,
            "F#2/Gb2"            to 92.49861,
            "F2"                 to 87.30706,
            "E2"                 to 82.40689,
            "D#2/Eb2"            to 77.78175,
            "D2"                 to 73.41619,
            "C#2/Db2"            to 69.29566,
            "C2"                 to 65.40639,
            "B1"                 to 61.73541,
            "A#1/Bb1"            to 58.27047,
            "A1"                 to 55.00000,
            "G#1/Ab1"            to 51.91309,
            "G1"                 to 48.99943,
            "F#1/Gb1"            to 46.24930,
            "F1"                 to 43.65353,
            "E1"                 to 41.20344,
            "D#1/Eb1"            to 38.89087,
            "D1"                 to 36.70810,
            "C#1/Db1"            to 34.64783,
            "C1"                 to 32.70320,
            "B0"                 to 30.86771,
            "A#0/Bb0"            to 29.13524,
            "A0"                 to 27.50000,
            "G#0/Ab0"            to 25.95654,
            "G0"                 to 24.49971,
            "F#0/Gb0"            to 23.12465,
            "F0"                 to 21.82676,
            "E0"                 to 20.60172,
            "D#0/Eb0"            to 19.44544,
            "D0"                 to 18.35405,
            "C#0/Db0"            to 17.32391,
            "C0"                 to 16.35160,
        )

    fun getClosest(freq: Double): String {
        val closest = asMap.entries.minByOrNull { abs(it.value - freq) }
        if (closest == null) {
            return ""
        }
        val noteWithoutOctave = closest.key.filter { !it.isDigit() }
        val diffSign = if (freq > closest.value) "+" else "-"
        val diff = String.format("%.2f", abs(freq - closest.value))
        return "${noteWithoutOctave} ${diffSign} ${diff}"
    }
}
