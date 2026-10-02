package com.zen.facetrial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        /** Cosine similarity needed to call two faces the same person. Shown on screen for tuning. */
        private const val MATCH_THRESHOLD = 0.60f
        private const val MIN_FACE_PX = 80
        private const val ENROLL_SAMPLES = 6
        private const val GREET_COOLDOWN_MS = 20_000L
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlay
    private lateinit var statusText: TextView
    private lateinit var nameText: TextView
    private lateinit var micButton: Button
    private lateinit var cameraExecutor: ExecutorService

    private var useFrontCamera = true

    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var listening = false

    // Face recognition state
    private lateinit var store: FaceStore
    private var embedder: FaceEmbedder? = null
    @Volatile private var pendingName: String? = null
    private val enrollSamples: MutableList<FloatArray> =
        Collections.synchronizedList(mutableListOf())
    private var lastEmbedAt = 0L
    private var lastLabel: String? = null
    private var candidate: String? = null
    private var candidateCount = 0
    private val lastGreetAt = mutableMapOf<String, Long>()

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                statusText.text = "Camera permission denied. Enable it in Settings > Apps > Face Trial."
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                nameText.text = "Microphone permission denied. Enable it in Settings > Apps > Face Trial."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        nameText = findViewById(R.id.nameText)
        micButton = findViewById(R.id.micButton)
        cameraExecutor = Executors.newSingleThreadExecutor()

        store = FaceStore(this)
        embedder = try {
            FaceEmbedder(this)
        } catch (e: Throwable) {
            null
        }
        showKnownNames()
        if (embedder == null) {
            nameText.text = "Face model missing in this build. Recognition is off."
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.US)
                }
                ttsReady = true
            }
        }

        findViewById<Button>(R.id.flipButton).setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }

        findViewById<Button>(R.id.forgetButton).setOnClickListener {
            store.clear()
            pendingName = null
            enrollSamples.clear()
            lastGreetAt.clear()
            candidate = null
            candidateCount = 0
            lastLabel = null
            nameText.text = "Forgot all saved faces and names."
        }

        micButton.setOnClickListener {
            if (listening) {
                stopListening()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showKnownNames() {
        val names = store.names()
        nameText.text = if (names.isEmpty()) {
            "Tap the mic and say your name"
        } else {
            "I know: ${names.joinToString(", ")}"
        }
    }

    // ---------- Speech: listen for a name, then learn the face ----------

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            nameText.text = "Speech recognition isn't available on this phone."
            return
        }
        tts?.stop()
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    nameText.text = "Listening... say your name"
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) nameText.text = partial
                }

                override fun onResults(results: Bundle?) {
                    setListening(false)
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (heard.isNullOrBlank()) {
                        nameText.text = "I didn't catch that. Try again."
                        return
                    }
                    startEnrolling(extractName(heard))
                }

                override fun onError(error: Int) {
                    setListening(false)
                    nameText.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear a name. Try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs internet on this phone."
                        else -> "Speech error ($error). Try again."
                    }
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        setListening(true)
        speech?.startListening(intent)
    }

    private fun stopListening() {
        speech?.stopListening()
        setListening(false)
    }

    private fun setListening(value: Boolean) {
        listening = value
        micButton.text = if (value) "Stop" else "Say your name"
    }

    /** Name heard -> now capture a few frames of the face and save them under that name. */
    private fun startEnrolling(name: String) {
        if (embedder == null) {
            nameText.text = "Hello, $name! (Face model missing, so I can't remember your face.)"
            speak("Hello $name.")
            return
        }
        enrollSamples.clear()
        pendingName = name
        nameText.text = "Hello, $name! Look at the camera and hold still..."
        speak("Hello $name. Look at the camera and hold still, so I can remember you.")
    }

    /** "my name is Zen" -> "Zen" */
    private fun extractName(heard: String): String {
        val cleaned = heard.trim()
            .replace(
                Regex(
                    "^(hi|hello|hey)?[ ,]*(my name is|my name's|i am|i'm|im|this is|call me|it's|its)\\s+",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
        val name = if (cleaned.isEmpty()) heard.trim() else cleaned
        return name.split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercase() }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speak")
        } else {
            runOnUiThread {
                Toast.makeText(this, "Text-to-speech isn't ready yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Camera + face detection + recognition ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) } }

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                statusText.text = "Looking for faces..."
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                statusText.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = rotation == 90 || rotation == 270
        val imgW = if (rotated) proxy.height else proxy.width
        val imgH = if (rotated) proxy.width else proxy.height

        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val largest = faces.maxByOrNull { it.boundingBox.width() }
                var label: String? = null
                if (largest != null) {
                    label = try {
                        processFace(proxy, largest.boundingBox)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    candidate = null
                    candidateCount = 0
                    lastLabel = null
                }
                overlay.update(
                    faces.map { it.boundingBox },
                    imgW, imgH, useFrontCamera,
                    faces.indexOf(largest), label
                )
                runOnUiThread {
                    statusText.text = when (faces.size) {
                        0 -> "No face detected"
                        1 -> "1 face detected"
                        else -> "${faces.size} faces detected"
                    }
                }
            }
            .addOnCompleteListener(cameraExecutor) { proxy.close() }
    }

    /** Runs on the camera thread. Returns the label to draw above the face. */
    private fun processFace(proxy: ImageProxy, box: Rect): String? {
        val model = embedder ?: return null
        if (box.width() < MIN_FACE_PX) return "Come closer"

        val now = SystemClock.elapsedRealtime()
        val enrolling = pendingName
        val interval = if (enrolling != null) 250L else 400L
        if (now - lastEmbedAt < interval) return lastLabel
        lastEmbedAt = now

        val crop = cropFace(proxy, box) ?: return lastLabel
        val vector = model.embed(crop)

        // Learning a new face
        if (enrolling != null) {
            enrollSamples.add(vector)
            if (enrollSamples.size >= ENROLL_SAMPLES) {
                val samples = synchronized(enrollSamples) { enrollSamples.toList() }
                samples.forEach { store.add(enrolling, it) }
                enrollSamples.clear()
                pendingName = null
                lastGreetAt[enrolling] = now
                runOnUiThread { nameText.text = "Saved! I'll remember you, $enrolling." }
                speak("Got it. I will remember you, $enrolling.")
                lastLabel = enrolling
                return lastLabel
            }
            lastLabel = "Learning ${enrollSamples.size}/$ENROLL_SAMPLES"
            return lastLabel
        }

        // Recognising a known face
        if (store.isEmpty()) {
            lastLabel = null
            return null
        }
        val match = store.match(vector, MATCH_THRESHOLD)
        val score = String.format(Locale.US, "%.2f", match.score)
        val name = match.name
        if (name != null) {
            if (name == candidate) candidateCount++ else {
                candidate = name
                candidateCount = 1
            }
            if (candidateCount >= 2) greetIfDue(name, now)
            lastLabel = "$name ($score)"
        } else {
            candidate = null
            candidateCount = 0
            lastLabel = "Unknown ($score)"
        }
        return lastLabel
    }

    private fun greetIfDue(name: String, now: Long) {
        if (listening) return
        val last = lastGreetAt[name]
        if (last != null && now - last < GREET_COOLDOWN_MS) return
        lastGreetAt[name] = now
        runOnUiThread { nameText.text = "Hi, $name!" }
        speak("Hi $name")
    }

    /** Upright square crop around the face, ready for the model. */
    private fun cropFace(proxy: ImageProxy, box: Rect): Bitmap? {
        val raw = proxy.toBitmap()
        val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)

        val side = (max(box.width(), box.height()) * 1.15f).toInt()
            .coerceAtMost(min(upright.width, upright.height))
        if (side <= 0) return null
        val left = (box.centerX() - side / 2).coerceIn(0, upright.width - side)
        val top = (box.centerY() - side / 2).coerceIn(0, upright.height - side)
        return Bitmap.createBitmap(upright, left, top, side, side)
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.destroy()
        tts?.shutdown()
        cameraExecutor.shutdown()
        detector.close()
        embedder?.close()
    }
}
