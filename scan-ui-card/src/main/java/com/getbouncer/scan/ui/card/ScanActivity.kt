package com.getbouncer.scan.ui.card

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.TextureView
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.Stats
import com.getbouncer.scan.framework.api.ERROR_CODE_NOT_AUTHENTICATED
import com.getbouncer.scan.framework.api.NetworkResult
import com.getbouncer.scan.framework.api.uploadScanStats
import com.getbouncer.scan.framework.api.validateApiKey
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import com.getbouncer.scan.camera.FrameConverter
import com.getbouncer.scan.camera.camera2.Camera2Adapter
import com.getbouncer.scan.camera.camera2.ImageListenerAdapter
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CameraErrorListenerImpl(
    private val context: Context,
    private val callback: (Throwable?) -> Unit
) : CameraErrorListener {
    override fun onCameraOpenError(cause: Throwable?) {
        showCameraError(R.string.bouncer_error_camera_open, cause)
    }

    override fun onCameraAccessError(cause: Throwable?) {
        showCameraError(R.string.bouncer_error_camera_access, cause)
    }

    override fun onCameraUnsupportedError(cause: Throwable?) {
        showCameraError(R.string.bouncer_error_camera_unsupported, cause)
    }

    private fun showCameraError(@StringRes message: Int, cause: Throwable?) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.bouncer_error_camera_title)
        builder.setMessage(message)
        builder.setPositiveButton(R.string.bouncer_error_camera_acknowledge_button) { _, _ ->
            callback(cause)
        }
        builder.show()
    }
}

abstract class ScanActivity<ImageFormat, State, AnalyzerResult, FinalResult> : AppCompatActivity(), CoroutineScope {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1200

        private const val RESULT_INSTANCE_ID = "instanceId"
        private const val RESULT_SCAN_ID = "scanId"

        private const val RESULT_CANCELED_REASON = "canceledReason"
        private const val CANCELED_REASON_USER = -1
        private const val CANCELED_REASON_CAMERA_ERROR = -2
        private const val CANCELED_REASON_ANALYZER_FAILURE = -3

        fun getCanceledReason(data: Intent?): Int =
            data?.getIntExtra(RESULT_CANCELED_REASON, Int.MIN_VALUE) ?: Int.MIN_VALUE

        fun Intent?.isUserCanceled(): Boolean = getCanceledReason(this) == CANCELED_REASON_USER
        fun Intent?.isCameraError(): Boolean = getCanceledReason(this) == CANCELED_REASON_CAMERA_ERROR
        fun Intent?.isAnalyzerFailure(): Boolean = getCanceledReason(this) == CANCELED_REASON_ANALYZER_FAILURE

        fun Intent?.instanceId(): String? = this?.getStringExtra(RESULT_INSTANCE_ID)
        fun Intent?.scanId(): String? = this?.getStringExtra(RESULT_SCAN_ID)
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Main

    protected val scanStat = Stats.trackTask("scan_activity")
    private val permissionStat = Stats.trackTask("camera_permission")

    protected var isFlashlightOn: Boolean = false
        private set

    private lateinit var resultAggregator:
            ResultAggregator<ImageFormat, State, AnalyzerResult, FinalResult>

    private val cameraAdapter by lazy { buildCameraAdapter() }
    protected val cameraErrorListener by lazy {
        CameraErrorListenerImpl(this) { t -> cameraErrorCancelScan(t) }
    }

    private var isApiKeyValid = true

    @LayoutRes
    abstract fun getLayoutRes(): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutRes())

        runBlocking { Stats.startScan() }

        // prevent screenshots
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        ensureValidApiKey()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        } else {
            runBlocking { permissionStat.trackResult("already_granted") }
            prepareCamera { onCameraReady() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::resultAggregator.isInitialized) {
            resultAggregator.resetAndPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isApiKeyValid && ::resultAggregator.isInitialized) {
            resultAggregator.resume()
        }
    }

    /**
     * Handle permission status changes. If the camera permission has been granted, start it. If
     * not, show a dialog.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()) {
            when (grantResults[0]) {
                PackageManager.PERMISSION_GRANTED -> {
                    runBlocking { permissionStat.trackResult("granted") }
                    prepareCamera { onCameraReady() }
                }
                else -> {
                    runBlocking { permissionStat.trackResult("denied") }
                    showPermissionDeniedDialog()
                }
            }
        }
    }

    /**
     * Show an explanation dialog for why we are requesting camera permissions.
     */
    private fun showPermissionDeniedDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.bouncer_camera_permission_denied_message)
            .setPositiveButton(R.string.bouncer_camera_permission_denied_ok) { _, _ -> requestCameraPermission() }
            .setNegativeButton(R.string.bouncer_camera_permission_denied_cancel) { _, _ -> prepareCamera { onCameraReady() } }
        builder.show()
    }

    /**
     * Request permission to use the camera.
     */
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Validate the API key against the server. If it's invalid, close the scanner.
     */
    private fun ensureValidApiKey() {
        launch {
            when (val apiKeyValidateResult = validateApiKey()) {
                is NetworkResult.Success -> {
                    if (!apiKeyValidateResult.body.isApiKeyValid) {
                        Log.e(
                            Config.logTag,
                            "API key is invalid: ${apiKeyValidateResult.body.keyInvalidReason}"
                        )
                        isApiKeyValid = false
                        resultAggregator.resetAndPause()
                        showApiKeyInvalidError()
                    }
                }
                is NetworkResult.Error -> {
                    if (apiKeyValidateResult.error.errorCode == ERROR_CODE_NOT_AUTHENTICATED) {
                        Log.e(
                            Config.logTag,
                            "API key is invalid: ${apiKeyValidateResult.error.errorMessage}"
                        )
                        isApiKeyValid = false
                        resultAggregator.resetAndPause()
                        showApiKeyInvalidError()
                    } else {
                        Log.w(
                            Config.logTag,
                            "Unable to validate API key: ${apiKeyValidateResult.error.errorMessage}"
                        )
                    }
                }
                is NetworkResult.Exception -> Log.w(
                    Config.logTag,
                    "Unable to validate API key",
                    apiKeyValidateResult.exception
                )
            }
        }
    }

    private fun showApiKeyInvalidError() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.bouncer_api_key_invalid_title)
        builder.setMessage(R.string.bouncer_api_key_invalid_message)
        builder.setPositiveButton(R.string.bouncer_api_key_invalid_ok) { _, _ -> userCancelScan() }
        builder.setCancelable(false)
        builder.show()
    }

    /**
     * Turn the flashlight on or off.
     */
    protected fun toggleFlashlight() {
        isFlashlightOn = !isFlashlightOn
        setFlashlightState(isFlashlightOn)
        runBlocking {
            Stats.trackRepeatingTask("torch_state").trackResult(if (isFlashlightOn) "on" else "off")
        }
    }

    /**
     * Called when the flashlight state has changed.
     */
    protected abstract fun onFlashlightStateChanged(flashlightOn: Boolean)

    /**
     * Turn the flashlight on or off.
     */
    private fun setFlashlightState(on: Boolean) {
        cameraAdapter.setTorchState(on)
        isFlashlightOn = on
        onFlashlightStateChanged(on)
    }

    /**
     * Cancel scanning due to a camera error.
     */
    private fun cameraErrorCancelScan(cause: Throwable? = null) {
        Log.e(Config.logTag, "Canceling scan due to camera error", cause)
        runBlocking { scanStat.trackResult("camera_error") }
        cancelScan(CANCELED_REASON_CAMERA_ERROR)
    }

    /**
     * The scan has been cancelled by the user.
     */
    protected fun userCancelScan() {
        runBlocking { scanStat.trackResult("user_canceled") }
        cancelScan(CANCELED_REASON_USER)
    }

    /**
     * Cancel scanning due to analyzer failure
     */
    protected fun analyzerFailureCancelScan(cause: Throwable? = null) {
        Log.e(Config.logTag, "Canceling scan due to analyzer error", cause)
        runBlocking { scanStat.trackResult("analyzer_failure") }
        cancelScan(CANCELED_REASON_ANALYZER_FAILURE)
    }

    /**
     * Cancel a scan
     */
    protected fun cancelScan(reasonCode: Int) {
        val intent = Intent()
            .putExtra(RESULT_CANCELED_REASON, reasonCode)
            .putExtra(RESULT_INSTANCE_ID, Stats.instanceId)
            .putExtra(RESULT_SCAN_ID, Stats.scanId)
        setResult(Activity.RESULT_CANCELED, intent)
        closeScanner()
    }

    /**
     * Complete a scan
     */
    protected fun completeScan(result: Intent) {
        result
            .putExtra(RESULT_INSTANCE_ID, Stats.instanceId)
            .putExtra(RESULT_SCAN_ID, Stats.scanId)
        setResult(Activity.RESULT_OK, result)
        closeScanner()
    }

    /**
     * Close the scanner.
     */
    private fun closeScanner() {
        setFlashlightState(false)
        uploadScanStats(this, Stats.instanceId, Stats.scanId)
        runBlocking { Stats.finishScan() }
        finish()
    }

    /**
     * Prepare to start the camera. Once the camera is ready, [onCameraReady] must be called.
     */
    protected abstract fun prepareCamera(onCameraReady: () -> Unit)

    private fun onCameraReady() {
        cameraAdapter.bindToLifecycle(this)

        val stat = Stats.trackTask("torch_supported")
        cameraAdapter.withFlashSupport {
            runBlocking { stat.trackResult(if (it) "supported" else "unsupported") }
            setFlashlightState(cameraAdapter.isTorchOn())
            onFlashSupported(it)
        }
    }

    /**
     * Perform an action when the flash is supported
     */
    protected abstract fun onFlashSupported(supported: Boolean)

    protected fun setFocus(point: PointF) {
        cameraAdapter.setFocus(point)
    }

    /**
     * Cancel the scan when the user presses back.
     */
    override fun onBackPressed() {
        userCancelScan()
    }

    /**
     * Generate a camera adapter
     */
    private fun buildCameraAdapter(): CameraAdapter {
        resultAggregator = buildResultAggregator()
        val mainLoop = buildMainLoop(resultAggregator)

        launch(Dispatchers.Default) {
            mainLoop.start(this)
        }

        val onImageAvailableListener = ImageListenerAdapter(
            loop = mainLoop,
            frameConverter = buildFrameConverter()
        )

        return Camera2Adapter(
            activity = this,
            onImageAvailableListener = onImageAvailableListener,
            minimumResolution = minimumAnalysisResolution,
            cameraErrorListener = cameraErrorListener,
            cameraTexture = previewTextureView
        )
    }

    protected abstract val viewFinderRect: Rect

    protected abstract val minimumAnalysisResolution: Size

    protected abstract val previewTextureView: TextureView?

    /**
     * Generate the main loop
     */
    protected abstract fun buildMainLoop(
        resultAggregator: ResultAggregator<ImageFormat, State, AnalyzerResult, FinalResult>
    ): ProcessBoundAnalyzerLoop<ImageFormat, State, AnalyzerResult>

    protected abstract fun buildResultAggregator():
            ResultAggregator<ImageFormat, State, AnalyzerResult, FinalResult>

    protected abstract fun buildFrameConverter(): FrameConverter<Bitmap, ImageFormat>
}
