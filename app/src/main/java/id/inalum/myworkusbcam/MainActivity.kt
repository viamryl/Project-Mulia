package id.inalum.myworkusbcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jiangdg.usbcamera.UVCCameraHelper
import com.jiangdg.usbcamera.UVCCameraHelper.OnMyDevConnectListener
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.common.AbstractUVCCameraHandler
import com.serenegiant.usb.widget.CameraViewInterface
import id.inalum.myworkusbcam.databinding.ActivityMainBinding
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.core.vision.ImageProcessingOptions
import org.tensorflow.lite.task.vision.classifier.Classifications
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.File
import androidx.camera.core.ImageProxy

class MainActivity : AppCompatActivity(), CameraDialog.CameraDialogParent, CameraViewInterface.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var classificationResult: TextView

    private var mCameraHelper: UVCCameraHelper? = null
    private var mUVCCameraView: CameraViewInterface? = null

    private var isRequest = false
    private var isPreview = false

    private lateinit var imageClassifier: ImageClassificationHelper

    private val listener: OnMyDevConnectListener = object : OnMyDevConnectListener {
        override fun onAttachDev(device: UsbDevice) {
            if (!isRequest) {
                isRequest = true
                mCameraHelper?.requestPermission(0)
            }
        }

        override fun onDettachDev(device: UsbDevice) {
            if (isRequest) {
                isRequest = false
                mCameraHelper?.closeCamera()
                showShortMsg("${device.deviceName} is out")
            }
        }

        override fun onConnectDev(device: UsbDevice, isConnected: Boolean) {
            if (!isConnected) {
                showShortMsg("Fail to connect, please check resolution params")
                isPreview = false
            } else {
                isPreview = true
                showShortMsg("Connecting")
                Thread {
                    try {
                        Thread.sleep(2500)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    Looper.prepare()
                    if (mCameraHelper?.isCameraOpened == true) {
                        showShortMsg("Camera is open")
                        mCameraHelper?.setModelValue(UVCCameraHelper.MODE_BRIGHTNESS, 100)
                        mCameraHelper?.setModelValue(UVCCameraHelper.MODE_CONTRAST, 100)
                    }
                    Looper.loop()
                }.start()
            }
        }

        override fun onDisConnectDev(device: UsbDevice) {
            showShortMsg("Disconnecting")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        classificationResult = findViewById(R.id.classificationResult)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mUVCCameraView = binding.cameraView
        mUVCCameraView?.setCallback(this)

        mCameraHelper = UVCCameraHelper.getInstance()
        mCameraHelper?.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_MJPEG)
        mCameraHelper?.initUSBMonitor(this, mUVCCameraView, listener)

        mCameraHelper?.setOnPreviewFrameListener(AbstractUVCCameraHandler.OnPreViewResultListener { nv21Yuv ->
            Log.d("DEBUG", "onPreviewResult: ${nv21Yuv.size}")
        })

        imageClassifier = ImageClassificationHelper(
            context = this,
            classifierListener = object : ImageClassificationHelper.ClassifierListener {
                override fun onResults(results: List<Classifications>?, inferenceTime: Long) {
                    runOnUiThread {
                        if (results != null && results.isNotEmpty()) {
                            val classifications = results[0].categories
                            val resultText = if (classifications.isNotEmpty()) {
                                classifications.joinToString("\n") {
                                    "${it.label}: ${it.score * 100}%"
                                }
                            } else {
                                "Klasifikasi tidak ditemukan."
                            }
                            classificationResult.text = resultText
                        } else {
                            classificationResult.text = "Hasil klasifikasi tidak tersedia."
                        }
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        classificationResult.text = "Error: $error"
                    }
                }
            }
        )

        binding.button.setOnClickListener {
            captureAndClassifyImage()
        }
    }

    override fun onStart() {
        super.onStart()
        mCameraHelper?.registerUSB()
    }

    override fun onStop() {
        super.onStop()
        mCameraHelper?.unregisterUSB()
    }

    private fun showShortMsg(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun getUSBMonitor(): USBMonitor {
        return mCameraHelper!!.usbMonitor
    }

    override fun onDialogResult(canceled: Boolean) {
        if (canceled) {
            showShortMsg("Batalan Operasi")
        }
    }

    fun isCameraOpened(): Boolean {
        return mCameraHelper!!.isCameraOpened
    }

    override fun onSurfaceCreated(view: CameraViewInterface?, surface: Surface?) {
        if (!isPreview && mCameraHelper!!.isCameraOpened) {
            mCameraHelper!!.startPreview(mUVCCameraView)
            isPreview = true
        }
    }

    override fun onSurfaceChanged(view: CameraViewInterface?, surface: Surface?, width: Int, height: Int) {
    }

    override fun onSurfaceDestroy(view: CameraViewInterface?, surface: Surface?) {
        if (isPreview && mCameraHelper!!.isCameraOpened) {
            mCameraHelper!!.stopPreview()
            isPreview = false
        }
    }

    private fun captureAndClassifyImage() {
        if (isCameraOpened()) {
            val myFile = File(getExternalFilesDir("Download"), "download.jpg")
            mCameraHelper!!.capturePicture(myFile.path, object : AbstractUVCCameraHandler.OnCaptureListener {
                override fun onCaptureResult(path: String) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Picture saved: $path",
                            Toast.LENGTH_SHORT
                        ).show()

                        val bitmap: Bitmap = BitmapFactory.decodeFile(path)
                        imageClassifier.classifyImage(bitmap)
                    }
                }
            })
        }
    }

    class ImageClassificationHelper(
        var threshold: Float = 0.1f,
        var maxResults: Int = 3,
        val modelName: String = "quantized_model_tflite_v12.tflite",
        val context: Context,
        val classifierListener: ClassifierListener?
    ) {
        private var imageClassifier: ImageClassifier? = null

        init {
            setupImageClassifier()
        }

        private fun setupImageClassifier() {
            val optionsBuilder = ImageClassifier.ImageClassifierOptions.builder()
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)
            val baseOptionsBuilder = BaseOptions.builder()
                .setNumThreads(4)
            optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

            try {
                imageClassifier = ImageClassifier.createFromFileAndOptions(
                    context,
                    modelName,
                    optionsBuilder.build()
                )
            } catch (e: IllegalStateException) {
                classifierListener?.onError(context.getString(R.string.image_classifier_failed))
                Log.e(TAG, e.message.toString())
            }
        }

        fun classifyImage(bitmap: Bitmap) {
            if (imageClassifier == null) {
                setupImageClassifier()
            }

            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(128, 128, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                .add(CastOp(DataType.UINT8))
                .build()

            val tensorImage = TensorImage(DataType.UINT8)
            tensorImage.load(bitmap)
            tensorImage.buffer.rewind()

            var inferenceTime = SystemClock.uptimeMillis()
            val results = imageClassifier?.classify(tensorImage)
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime

            classifierListener?.onResults(results, inferenceTime)
        }

        private fun toBitmap(image: ImageProxy): Bitmap {
            val bitmapBuffer = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
            image.close()
            return bitmapBuffer
        }

        private fun getOrientationFromRotation(rotation: Int): ImageProcessingOptions.Orientation {
            return when (rotation) {
                Surface.ROTATION_270 -> ImageProcessingOptions.Orientation.BOTTOM_RIGHT
                Surface.ROTATION_180 -> ImageProcessingOptions.Orientation.RIGHT_BOTTOM
                Surface.ROTATION_90 -> ImageProcessingOptions.Orientation.TOP_LEFT
                else -> ImageProcessingOptions.Orientation.RIGHT_TOP
            }
        }

        interface ClassifierListener {
            fun onError(error: String)
            fun onResults(results: List<Classifications>?, inferenceTime: Long)
        }

        companion object {
            private const val TAG = "ImageClassifierHelper"
        }
    }
}
