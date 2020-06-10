package com.getbouncer.scan.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import com.getbouncer.scan.camera.FrameConverter
import com.getbouncer.scan.camera.camera1.Camera1Adapter
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.Stats
import com.getbouncer.scan.framework.api.ERROR_CODE_NOT_AUTHENTICATED
import com.getbouncer.scan.framework.api.NetworkResult
import com.getbouncer.scan.framework.api.uploadScanStats
import com.getbouncer.scan.framework.api.validateApiKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

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
        AlertDialog.Builder(context)
            .setTitle(R.string.bouncer_error_camera_title)
            .setMessage(message)
            .setPositiveButton(R.string.bouncer_error_camera_acknowledge_button) { _, _ -> callback(cause) }
            .show()
    }
}

abstract class ScanActivity<DataFormat> : AppCompatActivity(), CoroutineScope {

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

        fun Intent?.isUserCanceled(): Boolean = getCanceledReason(this) ==
            CANCELED_REASON_USER
        fun Intent?.isCameraError(): Boolean = getCanceledReason(this) ==
            CANCELED_REASON_CAMERA_ERROR
        fun Intent?.isAnalyzerFailure(): Boolean = getCanceledReason(this) ==
            CANCELED_REASON_ANALYZER_FAILURE

        fun Intent?.instanceId(): String? = this?.getStringExtra(RESULT_INSTANCE_ID)
        fun Intent?.scanId(): String? = this?.getStringExtra(RESULT_SCAN_ID)
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Main

    protected val scanStat = Stats.trackTask("scan_activity")
    private val permissionStat = Stats.trackTask("camera_permission")

    protected var isFlashlightOn: Boolean = false
        private set

    private val cameraAdapter by lazy { buildCameraAdapter() }
    protected val cameraErrorListener by lazy {
        CameraErrorListenerImpl(this) { t -> cameraErrorCancelScan(t) }
    }

    protected var isApiKeyValid = true
        private set

    @LayoutRes
    abstract fun getLayoutRes(): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // prevent screenshots and keep the screen on while scanning
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE + WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_SECURE + WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(getLayoutRes())

        runBlocking { Stats.startScan() }

        ensureValidApiKey()

        if (!CameraAdapter.isCameraSupported(this)) {
            showCameraNotSupportedDialog()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        } else {
            runBlocking { permissionStat.trackResult("already_granted") }
            prepareCamera { onCameraReady() }
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
     * Show a dialog explaining that the camera is not available.
     */
    private fun showCameraNotSupportedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bouncer_error_camera_title)
            .setMessage(R.string.bouncer_error_camera_unsupported)
            .setPositiveButton(R.string.bouncer_error_camera_acknowledge_button) { _, _ -> cameraErrorCancelScan() }
            .show()
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
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
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
                        onInvalidApiKey()
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
                        onInvalidApiKey()
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

    protected fun showApiKeyInvalidError() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bouncer_api_key_invalid_title)
            .setMessage(R.string.bouncer_api_key_invalid_message)
            .setPositiveButton(R.string.bouncer_api_key_invalid_ok) { _, _ -> userCancelScan() }
            .setCancelable(false)
            .show()
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

        onCameraStreamAvailable(cameraAdapter.getImageStream())
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
    private fun buildCameraAdapter() = Camera1Adapter(
        activity = this,
        previewView = previewFrame,
        minimumResolution = minimumAnalysisResolution,
        frameConverter = buildFrameConverter(),
        cameraErrorListener = cameraErrorListener
    )

    protected abstract val minimumAnalysisResolution: Size

    protected abstract val previewFrame: FrameLayout

    /**
     * A stream of images from the camera is available to be processed.
     */
    protected abstract fun onCameraStreamAvailable(cameraStream: Channel<DataFormat>)

    /**
     * A converter to translate the output from the camera to images usable by the scanner.
     */
    protected abstract fun buildFrameConverter(): FrameConverter<Bitmap, DataFormat>

    /**
     * The API key was invalid.
     */
    protected abstract fun onInvalidApiKey()
}
