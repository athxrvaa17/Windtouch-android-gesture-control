package com.example.windtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.hypot

class GestureService : AccessibilityService(), LifecycleOwner, HandLandmarkerHelper.LandmarkerListener {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var cursorIcon: ImageView? = null
    private lateinit var wmParams: WindowManager.LayoutParams

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0

    // Sensitivity setup
    private val sensitivity = 2.5f

    private var currentX = 0f
    private var currentY = 0f

    // Drag Logic Variables
    private var isDragging = false
    private var keptStroke: GestureDescription.StrokeDescription? = null
    private var lastDragX = 0f
    private var lastDragY = 0f

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        try {
            handLandmarkerHelper = HandLandmarkerHelper(this, this)
        } catch (e: Exception) { Log.e("WindTouch", "Helper Error: $e") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        wmParams = WindowManager.LayoutParams(
            120, 120,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wmParams.gravity = Gravity.TOP or Gravity.START
        wmParams.x = 0
        wmParams.y = 0

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        cursorIcon = overlayView?.findViewById(R.id.cursor_icon)
        if (cursorIcon == null && overlayView is android.view.ViewGroup) {
            cursorIcon = ImageView(this)
            (overlayView as android.view.ViewGroup).addView(cursorIcon)
        }

        updateCursorColor(Color.GREEN) // Default Green

        try {
            windowManager?.addView(overlayView, wmParams)
        } catch (e: Exception) { e.printStackTrace() }

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val viewFinder = overlayView?.findViewById<PreviewView>(R.id.view_finder)

                if (viewFinder != null) {
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(viewFinder.surfaceProvider)

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalyzer.setAnalyzer(backgroundExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        if (bitmap != null) handLandmarkerHelper.detectLiveStream(bitmap, true)
                        imageProxy.close()
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                }
            } catch (e: Exception) { Log.e("WindTouch", "Cam Error: $e") }
        }, ContextCompat.getMainExecutor(this))
    }

    @RequiresApi(Build.VERSION_CODES.O) // Drag feature needs Android 8.0+
    override fun onResults(result: HandLandmarkerResult) {
        if (result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks()[0]

            // --- 3 FINGER LOGIC ---
            val indexTip = landmarks[8]   // Aim
            val middleTip = landmarks[12] // Trigger
            val thumbTip = landmarks[4]   // Base

            val wrist = landmarks[0]
            val middleBase = landmarks[9]
            val handSize = hypot((wrist.x() - middleBase.x()).toDouble(), (wrist.y() - middleBase.y()).toDouble())

            // Pinch Distance (Middle + Thumb)
            val pinchDist = hypot((middleTip.x() - thumbTip.x()).toDouble(), (middleTip.y() - thumbTip.y()).toDouble())

            // 1. Calculate Cursor Position
            val rawX = 1 - indexTip.y()
            val rawY = indexTip.x()
            val targetX = ((rawX - 0.5f) * sensitivity + 0.5f) * screenWidth
            val targetY = ((rawY - 0.5f) * sensitivity + 0.5f) * screenHeight

            currentX += (targetX - currentX) * 0.4f
            currentY += (targetY - currentY) * 0.4f

            // Update UI Cursor
            uiHandler.post {
                wmParams.x = currentX.toInt()
                wmParams.y = currentY.toInt()
                try { windowManager?.updateViewLayout(overlayView, wmParams) } catch (e: Exception) {}
            }

            // 2. Drag & Drop Logic (Continuous Gesture)

            // Threshold: 25% of hand size (Thoda close pinch chahiye)
            if (pinchDist < (handSize * 0.25)) {
                // PINCH DETECTED (Holding Down)

                if (!isDragging) {
                    // Start Dragging (Touch Down)
                    isDragging = true
                    lastDragX = currentX
                    lastDragY = currentY
                    updateCursorColor(Color.BLUE) // Visual Feedback

                    startGesture(currentX, currentY)
                } else {
                    // Continue Dragging (Move while holding)
                    continueGesture(currentX, currentY)
                    lastDragX = currentX
                    lastDragY = currentY
                }

            } else {
                // RELEASE DETECTED (Finger Up)

                if (isDragging) {
                    isDragging = false
                    updateCursorColor(Color.GREEN) // Back to Normal

                    endGesture(currentX, currentY)
                }
            }
        }
    }

    // --- NEW GESTURE FUNCTIONS (Android 8.0+) ---

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startGesture(x: Float, y: Float) {
        if (x < 0 || y < 0) return

        val path = Path()
        path.moveTo(x, y)
        // Duration 0 rakha taaki turant start ho
        // 'true' means this stroke WILL continue (Hold Down)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50, true)

        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)

        dispatchGesture(builder.build(), null, null)
        keptStroke = stroke // Save reference
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun continueGesture(x: Float, y: Float) {
        val lastStroke = keptStroke ?: return
        if (x < 0 || y < 0) return

        val path = Path()
        path.moveTo(lastDragX, lastDragY) // Start from last known pos
        path.lineTo(x, y)                 // Draw line to new pos

        // Continue the previous stroke
        // 'true' means still holding
        val nextStroke = lastStroke.continueStroke(path, 0, 50, true)

        val builder = GestureDescription.Builder()
        builder.addStroke(nextStroke)

        dispatchGesture(builder.build(), null, null)
        keptStroke = nextStroke // Update reference
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun endGesture(x: Float, y: Float) {
        val lastStroke = keptStroke ?: return

        val path = Path()
        path.moveTo(lastDragX, lastDragY)
        path.lineTo(x, y)

        // 'false' means we are done (Lift Finger)
        val finalStroke = lastStroke.continueStroke(path, 0, 50, false)

        val builder = GestureDescription.Builder()
        builder.addStroke(finalStroke)

        dispatchGesture(builder.build(), null, null)
        keptStroke = null // Reset
    }

    private fun updateCursorColor(color: Int) {
        uiHandler.post {
            cursorIcon?.setImageDrawable(drawCursor(color))
        }
    }

    // --- LIQUID GLASS CURSOR DESIGN ---
    private fun drawCursor(baseColor: Int): Drawable {
        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                // Size bada aur clear rakha hai
                val size = 120f

                // --- NEW SLEEK ARROW SHAPE ---
                // Ye shape "Star Trek" badge ya Navigation arrow jaisa hai
                // Tip (0,0) par hai taaki click accurate ho
                val path = Path().apply {
                    moveTo(0f, 0f)           // Tip (Nok)
                    lineTo(size * 0.7f, size * 0.25f) // Right Wing (Thoda lamba)
                    lineTo(size * 0.3f, size * 0.3f)  // Inner Cut (Beech ka gaddha)
                    lineTo(size * 0.25f, size * 0.7f) // Bottom Wing
                    close()
                }

                // --- COLORS ---
                val colors = if (baseColor == Color.BLUE) {
                    intArrayOf(Color.parseColor("#FF416C"), Color.parseColor("#FF4B2B")) // Dragging
                } else {
                    intArrayOf(Color.parseColor("#00C6FF"), Color.parseColor("#0072FF")) // Normal (Super Blue)
                }

                // --- PAINT (LIQUID FILL) ---
                val paint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    shader = LinearGradient(
                        0f, 0f, size, size,
                        colors, null,
                        Shader.TileMode.CLAMP
                    )
                    // Shadow: Glow effect ke liye
                    setShadowLayer(18f, 0f, 0f, colors[0])

                    // Note: CornerPathEffect ko 6f kar diya hai.
                    // Isse kone "Halke se" gol honge, par shape kharab nahi hogi.
                    pathEffect = CornerPathEffect(6f)
                }

                // --- BORDER (GLASS EDGE) ---
                val borderPaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                    color = Color.WHITE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    pathEffect = CornerPathEffect(6f) // Border bhi same smooth hoga
                }

                // --- ROTATION ---
                // Cursor ko -45 degree ghumaya taaki wo seedha pointer lage
                canvas.save()
                canvas.rotate(-30f, 0f, 0f)
                // Thoda offset diya taaki shadow kat na jaye
                canvas.translate(10f, 10f)

                canvas.drawPath(path, paint)
                canvas.drawPath(path, borderPaint)

                canvas.restore()
            }

            // Canvas ka size define karna zaroori hai taaki poora cursor dikhe
            override fun getIntrinsicWidth(): Int = 150
            override fun getIntrinsicHeight(): Int = 150

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    override fun onError(error: String) { Log.e("WindTouch", "AI Error: $error") }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        windowManager?.removeView(overlayView)
    }
}