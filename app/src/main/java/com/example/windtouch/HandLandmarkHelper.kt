package com.example.windtouch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandLandmarkerHelper(
    val context: Context,
    val listener: LandmarkerListener
) {
    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            // AB SLASH AA GAYA HAI:
            .setModelAssetPath("models/hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setNumHands(1)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)
            .build()

        try {
            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detectLiveStream(bitmap: Bitmap, isFrontCamera: Boolean) {
        if (handLandmarker == null) return

        val rotatedBitmap = if (isFrontCamera) {
            val matrix = Matrix()
            matrix.postScale(-1f, 1f)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        val frameTime = SystemClock.uptimeMillis()

        handLandmarker?.detectAsync(mpImage, frameTime)
    }

    private fun returnLivestreamResult(result: HandLandmarkerResult, input: MPImage) {
        listener.onResults(result)
    }

    private fun returnLivestreamError(error: RuntimeException) {
        listener.onError(error.message ?: "Unknown Error")
    }

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: HandLandmarkerResult)
    }
}