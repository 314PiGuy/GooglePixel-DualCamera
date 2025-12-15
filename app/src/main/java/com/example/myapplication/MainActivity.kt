package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val logTag = "DualCamera"
    private lateinit var surfaceViewWide: SurfaceView
    private lateinit var surfaceViewUltra: SurfaceView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var imageReaderWide: ImageReader? = null
    private var imageReaderUltra: ImageReader? = null
    private var serverWide: MjpegServer? = null
    private var serverUltra: MjpegServer? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkAndOpenCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBackgroundThread()

        // Side by side basic camera stream views
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        surfaceViewWide = SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        surfaceViewUltra = SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        layout.addView(surfaceViewWide)
        layout.addView(surfaceViewUltra)
        setContentView(layout)

        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { checkAndOpenCamera() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { closeCamera() }
        }

        surfaceViewWide.holder.addCallback(callback)
        surfaceViewUltra.holder.addCallback(callback)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun checkAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!surfaceViewWide.holder.surface.isValid || !surfaceViewUltra.holder.surface.isValid) return
        if (cameraDevice != null) return

        openDualCamera()
    }

    private fun openDualCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = findCameraIds(manager)
            if (cameraIds == null) {
                Log.e(logTag, "No suitable logical multi-camera found")
                Toast.makeText(this, "Multi-camera not found", Toast.LENGTH_LONG).show()
                return
            }

            val (logicalId, physWide, physUltra) = cameraIds
            Log.i(logTag, "Opening Logical: $logicalId, Wide: $physWide, Ultra: $physUltra")

            manager.openCamera(logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startDualStreaming(camera, physWide, physUltra)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(logTag, "Camera error: $error")
                }
            }, Handler(Looper.getMainLooper()))

        } catch (e: SecurityException) {
            Log.e(logTag, "Permission error", e)
        } catch (e: Exception) {
            Log.e(logTag, "Error opening camera", e)
        }
    }

    private fun findCameraIds(manager: CameraManager): Triple<String, String, String>? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val isLogical = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            if (facing == CameraCharacteristics.LENS_FACING_BACK && isLogical) {
                val physicalIds = chars.physicalCameraIds
                // Sorting by focal length: Smallest is Ultrawide next is Wide
                val sortedIds = physicalIds.sortedBy { physId ->
                    val physChars = manager.getCameraCharacteristics(physId)
                    physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.get(0) ?: 0f
                }

                if (sortedIds.size >= 2) {
                    // 0: Ultrawide, 1: Wide
                    return Triple(id, sortedIds[1], sortedIds[0])
                }
            }
        }
        return null
    }

    private fun startDualStreaming(camera: CameraDevice, physWide: String, physUltra: String) {
        try {
            imageReaderWide = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            imageReaderUltra = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)

            serverWide = MjpegServer(8000).apply { start() }
            serverUltra = MjpegServer(8001).apply { start() }

            imageReaderWide?.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val jpegBytes = image.toJpegBytes()
                    serverWide?.broadcast(jpegBytes)
                }
            }, backgroundHandler)

            imageReaderUltra?.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val jpegBytes = image.toJpegBytes()
                    serverUltra?.broadcast(jpegBytes)
                }
            }, backgroundHandler)

            val outputConfigWide = OutputConfiguration(surfaceViewWide.holder.surface)
            outputConfigWide.setPhysicalCameraId(physWide)

            val outputConfigUltra = OutputConfiguration(surfaceViewUltra.holder.surface)
            outputConfigUltra.setPhysicalCameraId(physUltra)

            val outputConfigReaderWide = OutputConfiguration(imageReaderWide!!.surface)
            outputConfigReaderWide.setPhysicalCameraId(physWide)

            val outputConfigReaderUltra = OutputConfiguration(imageReaderUltra!!.surface)
            outputConfigReaderUltra.setPhysicalCameraId(physUltra)

            val executor = Executors.newSingleThreadExecutor()

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfigWide, outputConfigUltra, outputConfigReaderWide, outputConfigReaderUltra),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(surfaceViewWide.holder.surface)
                            builder.addTarget(surfaceViewUltra.holder.surface)
                            builder.addTarget(imageReaderWide!!.surface)
                            builder.addTarget(imageReaderUltra!!.surface)
                            session.setRepeatingRequest(builder.build(), null, null)
                        } catch (e: Exception) {
                            Log.e(logTag, "Capture request error", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(logTag, "Configuration failed")
                    }
                }
            )

            camera.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            Log.e(logTag, "Session creation error", e)
        }
    }

    private fun closeCamera() {
        serverWide?.stop()
        serverUltra?.stop()
        imageReaderWide?.close()
        imageReaderUltra?.close()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(logTag, "Interrupted while stopping background thread", e)
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    override fun onResume() {
        super.onResume()
        if (::surfaceViewWide.isInitialized && ::surfaceViewUltra.isInitialized &&
            surfaceViewWide.holder.surface.isValid && surfaceViewUltra.holder.surface.isValid) {
             checkAndOpenCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBackgroundThread()
    }

    private fun android.media.Image.toJpegBytes(quality: Int = 85): ByteArray {
        val nv21 = toNv21()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, output)
        return output.toByteArray()
    }

    private fun android.media.Image.toNv21(): ByteArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val nv21 = ByteArray(width * height * 3 / 2)
        yPlane.buffer.get(nv21, 0, ySize)

        val rowStride = uPlane.rowStride
        val pixelStride = uPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2

        var offset = width * height
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uIndex = row * rowStride + col * pixelStride
                val vIndex = uIndex
                nv21[offset++] = vBuffer[vIndex]
                nv21[offset++] = uBuffer[uIndex]
            }
        }

        uBuffer.position(0)
        vBuffer.position(0)

        return nv21
    }

    class MjpegServer(private val port: Int) {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val clients = CopyOnWriteArrayList<ClientHandler>()

        fun start() {
            if (running.getAndSet(true)) return
            acceptThread = Thread {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                    Log.i("MjpegServer", "Server started on port $port")
                    while (running.get()) {
                        try {
                            val socket = serverSocket?.accept() ?: break
                            val handler = ClientHandler(socket)
                            clients += handler
                            handler.start()
                        } catch (e: Exception) {
                            if (running.get()) Log.e("MjpegServer", "Accept error", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MjpegServer", "Server error", e)
                } finally {
                    running.set(false)
                }
            }.apply { name = "MjpegServer-$port"; start() }
        }

        fun broadcast(jpegData: ByteArray) {
            if (clients.isEmpty()) return
            val iterator = clients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                if (!client.offerFrame(jpegData)) {
                    client.stop()
                    clients.remove(client)
                }
            }
        }

        fun stop() {
            if (!running.getAndSet(false)) return
            try { serverSocket?.close() } catch (_: Exception) {}
            acceptThread?.join(500)
            clients.forEach { it.stop() }
            clients.clear()
        }

        private inner class ClientHandler(private val socket: Socket) {
            private val active = AtomicBoolean(true)
            private val queue = LinkedBlockingQueue<ByteArray>(5)
            private var workerThread: Thread? = null

            fun start() {
                workerThread = Thread {
                    try {
                        val out = socket.getOutputStream()
                        out.write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=boundary\r\n" +
                            "\r\n"
                        ).toByteArray())
                        out.flush()
                        while (active.get()) {
                            val frame = queue.poll(2, TimeUnit.SECONDS) ?: continue
                            try {
                                out.write(("--boundary\r\n" +
                                        "Content-Type: image/jpeg\r\n" +
                                        "Content-Length: ${frame.size}\r\n" +
                                        "\r\n").toByteArray())
                                out.write(frame)
                                out.write("\r\n".toByteArray())
                                out.flush()
                            } catch (e: Exception) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MjpegServer", "Client stream error", e)
                    } finally {
                        stop()
                    }
                }.apply { name = "MjpegClient-$port"; start() }
            }

            fun offerFrame(frame: ByteArray): Boolean {
                if (!active.get()) return false
                if (!queue.offer(frame)) {
                    queue.poll()
                    if (!queue.offer(frame)) {
                        return false
                    }
                }
                return true
            }

            fun stop() {
                if (!active.getAndSet(false)) return
                try { socket.close() } catch (_: Exception) {}
                workerThread?.join(200)
            }
        }
    }
}
