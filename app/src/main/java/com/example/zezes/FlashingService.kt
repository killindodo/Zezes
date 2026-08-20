package com.example.zezes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palantir.isofilereader.isofilereader.GenericInternalIsoFile
import com.palantir.isofilereader.isofilereader.IsoFileReader
import de.waldheinz.fs.FileSystem
import de.waldheinz.fs.FsDirectory
import de.waldheinz.fs.FsDirectoryEntry
import de.waldheinz.fs.FsFile
import de.waldheinz.fs.fat.FatFileSystem
import de.waldheinz.fs.fat.FatType
import de.waldheinz.fs.fat.SuperFloppyFormatter
import kotlinx.coroutines.*
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class FlashingService : Service() {

    companion object {
        const val ACTION_PROGRESS_UPDATE = "com.example.zezes.PROGRESS_UPDATE"
        const val ACTION_START_EXTRACTION = "ACTION_START_EXTRACTION"
        const val ACTION_START_FLASH = "ACTION_START_FLASH"
        const val CHANNEL_ID = "flashing_channel"
        const val EXTRA_ISO_URI = "EXTRA_ISO_URI"
        const val EXTRA_IS_FINISHED = "EXTRA_IS_FINISHED"
        const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
        const val EXTRA_PROGRESS = "EXTRA_PROGRESS"
        const val EXTRA_SPEED = "EXTRA_SPEED"
        const val EXTRA_USB_DEVICE = "EXTRA_USB_DEVICE"
        const val MAX_FAT32_FILE_SIZE = 4293918720L // ~4GB - 1MB
        const val NOTIFICATION_ID = 1
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_USB_DEVICE)
        }

        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_ISO_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_ISO_URI)
        }

        if (device != null && uri != null) {
            when (intent?.action) {
                ACTION_START_FLASH -> {
                    startForeground(NOTIFICATION_ID, createNotification("Raw flashing..."))
                    performRawFlashing(device, uri)
                }
                ACTION_START_EXTRACTION -> {
                    startForeground(NOTIFICATION_ID, createNotification("Extracting ISO..."))
                    performFileExtraction(device, uri)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun sendProgressUpdate(
        progress: Int,
        speed: Double,
        message: String? = null,
        isFinished: Boolean = false
    ) {
        val intent = Intent(ACTION_PROGRESS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_IS_FINISHED, isFinished)
        }
        sendBroadcast(intent)

        val notificationMsg = message ?: if (isFinished) "Complete!" else "Flashing: $progress%"
        updateNotification(notificationMsg)
        Log.d("FlashingService", "Progress: $progress%, Speed: $speed MB/s, Message: $message")
    }

    private fun performRawFlashing(usbDevice: UsbDevice, uri: Uri) {
        serviceScope.launch {
            try {
                val blockDevice = getScsiBlockDevice(usbDevice)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedInputStream(inputStream, 131072).use { bufferedInputStream ->
                        val size = getUriSize(uri)
                        streamToBlockDevice(bufferedInputStream, blockDevice, size)
                        sendProgressUpdate(100, 0.0, "Raw flash complete!", true)
                    }
                }
            } catch (e: Exception) {
                sendProgressUpdate(-1, 0.0, "Error: ${e.message}", true)
            } finally {
                stopSelf()
            }
        }
    }

    private fun performFileExtraction(usbDevice: UsbDevice, uri: Uri) {
        serviceScope.launch {
            try {
                UsbCommunicationFactory.underlyingUsbCommunication =
                    UsbCommunicationFactory.UnderlyingUsbCommunication.DEVICE_CONNECTION_SYNC

                val isoPreparation = async(Dispatchers.IO) {
                    sendProgressUpdate(0, 0.0, "Phase 1: Preparing ISO metadata...", false)
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw Exception("Failed to open ISO file descriptor")
                    val isoFile = File("/proc/self/fd/${pfd.fd}")
                    val reader = IsoFileReader(isoFile)
                    val files = reader.allFiles ?: throw Exception("Failed to read files from ISO")
                    val total = files.filter { !it.isDirectory }.sumOf { it.size }
                    sendProgressUpdate(0, 0.0, "ISO Indexing complete. Waiting for USB...", false)
                    Triple(pfd, reader, total)
                }

                sendProgressUpdate(0, 0.0, "Phase 2: Initializing USB Hardware...", false)
                val blockDevice = getScsiBlockDevice(usbDevice)
                sendProgressUpdate(0, 0.0, "Formatting USB to FAT32 (Clean)...", false)

                val adapter = LibaumsBlockDevice(blockDevice)
                val fs = SuperFloppyFormatter.get(adapter)
                    .setFatType(FatType.FAT32)
                    .setVolumeLabel("ZES")
                    .format()

                sendProgressUpdate(0, 0.0, "USB Formatted. Cooling down...", false)
                delay(2000)

                val (isoPfd, isoReader, totalBytes) = isoPreparation.await()

                try {
                    val root = fs.root
                    val allFiles = isoReader.allFiles ?: throw Exception("No files found in ISO")
                    val startTime = System.currentTimeMillis()
                    sendProgressUpdate(0, 0.0, "Phase 3: Starting file copy...", false)

                    var totalWritten = 0L

                    for (file in allFiles) {
                        val fullFileName = file.getFullFileName('/')
                        if (file.isDirectory) {
                            getOrCreateDir(root, fullFileName)
                        } else {
                            val parentDir = getOrCreateParentDir(root, fullFileName)
                            val fileName = file.fileName
                            val progress = (totalWritten * 100 / totalBytes.coerceAtLeast(1L)).toInt()
                            val speed = calculateSpeed(totalWritten, startTime)

                            sendProgressUpdate(progress, speed, "Copying: $fileName", false)

                            if (fullFileName.endsWith("install.wim", ignoreCase = true) && file.size > MAX_FAT32_FILE_SIZE) {
                                sendProgressUpdate(progress, speed, "Splitting large WIM file: $fileName", false)
                                val fileStream = isoReader.getFileStream(file)
                                WimSplitter.split(fileStream, file.size, MAX_FAT32_FILE_SIZE) { part ->
                                    val swmName = if (part == 1) "install.swm" else "install$part.swm"
                                    val entry = parentDir.addFile(swmName)
                                    FatFileOutputStream(entry.file, fs)
                                }
                                totalWritten += file.size
                            } else {
                                val entry = parentDir.addFile(fileName)
                                BufferedInputStream(isoReader.getFileStream(file), 131072).use { bis ->
                                    val out = FatFileOutputStream(entry.file, fs)
                                    val buffer = ByteArray(131072)
                                    var readBytes: Int
                                    while (bis.read(buffer).also { readBytes = it } != -1) {
                                        out.write(buffer, 0, readBytes)
                                        totalWritten += readBytes
                                        if (totalWritten % 4194304L == 0L) {
                                            val currentProgress = (totalWritten * 100 / totalBytes.coerceAtLeast(1L)).toInt()
                                            val currentSpeed = calculateSpeed(totalWritten, startTime)
                                            sendProgressUpdate(currentProgress, currentSpeed, "Copying: $fileName", false)
                                        }
                                    }
                                    out.flush()
                                }
                            }
                        }
                    }

                    sendProgressUpdate(100, 0.0, "SUCCESS: Bootable USB Created!", true)
                } finally {
                    try { isoReader.close() } catch (_: Exception) {}
                    try { isoPfd.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                sendProgressUpdate(-1, 0.0, "FATAL ERROR: ${e.message}", true)
            } finally {
                stopSelf()
            }
        }
    }

    private fun calculateSpeed(bytes: Long, startTime: Long): Double {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        return if (elapsed > 0) ((bytes / 1024.0) / 1024.0) / elapsed else 0.0
    }

    private fun getOrCreateDir(root: FsDirectory, path: String): FsDirectory {
        val parts = path.split('/').filter { it.isNotBlank() }
        var current = root
        for (part in parts) {
            current = try {
                current.getEntry(part).directory
            } catch (_: Exception) {
                current.addDirectory(part).directory
            }
        }
        return current
    }

    private fun getOrCreateParentDir(root: FsDirectory, path: String): FsDirectory {
        val parts = path.split('/').filter { it.isNotBlank() }.dropLast(1)
        var current = root
        for (part in parts) {
            current = try {
                current.getEntry(part).directory
            } catch (_: Exception) {
                current.addDirectory(part).directory
            }
        }
        return current
    }

    private fun getScsiBlockDevice(usbDevice: UsbDevice): BlockDeviceDriver {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        var usbInterface: UsbInterface? = null
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until usbDevice.interfaceCount) {
            val itf = usbDevice.getInterface(i)
            if (itf.interfaceClass == 8) { // Mass Storage
                usbInterface = itf
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type == 2) { // Bulk
                        if (ep.direction == 128) { // In
                            inEndpoint = ep
                        } else { // Out
                            outEndpoint = ep
                        }
                    }
                }
                break
            }
        }

        if (usbInterface == null || inEndpoint == null || outEndpoint == null) {
            throw Exception("Mass storage interface not found")
        }

        val connection = usbManager.openDevice(usbDevice)
            ?: throw Exception("Failed to open USB device")

        val claimed = connection.claimInterface(usbInterface, true)
        if (!claimed) {
            connection.close()
            throw Exception("Failed to claim USB interface")
        }

        Thread.sleep(500)
        val communication = CustomUsbCommunication(connection, usbInterface, outEndpoint, inEndpoint)
        val blockDevice = BlockDeviceDriverFactory.createBlockDevice(communication, 0.toByte())

        var initRetries = 0
        while (true) {
            try {
                blockDevice.init()
                return StabilityWrapper(blockDevice)
            } catch (e: Exception) {
                initRetries++
                if (initRetries >= 3) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun streamToBlockDevice(
        inputStream: InputStream,
        blockDevice: BlockDeviceDriver,
        totalSize: Long
    ) {
        val blockSize = blockDevice.blockSize
        val buffer = ByteArray(131072)
        val byteBuffer = ByteBuffer.wrap(buffer)
        var currentLba = 0L
        var totalBytesWritten = 0L
        val startTime = System.currentTimeMillis()

        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break

            val writeSize = if (bytesRead % blockSize != 0) {
                ((bytesRead / blockSize) + 1) * blockSize
            } else {
                bytesRead
            }

            byteBuffer.position(0)
            byteBuffer.limit(writeSize)

            try {
                blockDevice.write(currentLba, byteBuffer)
                currentLba += (writeSize / blockSize).toLong()
                totalBytesWritten += bytesRead.toLong()

                val progress = if (totalSize > 0) ((100 * totalBytesWritten) / totalSize).toInt() else 0
                val speed = calculateSpeed(totalBytesWritten, startTime)

                if (totalBytesWritten % 4194304L == 0L || progress == 100) {
                    sendProgressUpdate(progress, speed, "Flashing raw data...", false)
                }
            } catch (e: Exception) {
                Log.e("FlashingService", "Error writing at LBA $currentLba", e)
                throw Exception("Disk write failed at sector $currentLba: ${e.message}")
            }
        }
    }

    private fun getUriSize(uri: Uri): Long {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { pfd ->
            val len = pfd.length
            if (len != -1L) return len
        }
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val colIndex = cursor.getColumnIndex("_size")
            if (cursor.moveToFirst() && colIndex != -1) {
                return cursor.getLong(colIndex)
            }
        }
        return 0L
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zezes Flashing Tool")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flashing Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private class StabilityWrapper(
        private val delegate: BlockDeviceDriver
    ) : BlockDeviceDriver {
        override val blockSize: Int
            get() = delegate.blockSize

        override val blocks: Long
            get() = delegate.blocks

        override fun init() = delegate.init()

        override fun read(deviceOffset: Long, buffer: ByteBuffer) = delegate.read(deviceOffset, buffer)

        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            val originalLimit = buffer.limit()
            val bSize = blockSize
            var currentLba = deviceOffset

            while (buffer.hasRemaining()) {
                val remaining = buffer.remaining()
                val blocksToTransfer = (remaining / bSize).coerceAtMost(4)
                if (blocksToTransfer == 0) break

                val transferBytes = blocksToTransfer * bSize
                val nextLimit = buffer.position() + transferBytes
                buffer.limit(nextLimit.coerceAtMost(originalLimit))

                var retries = 0
                while (true) {
                    try {
                        delegate.write(currentLba, buffer)
                        break
                    } catch (e: Exception) {
                        retries++
                        if (retries >= 10) {
                            throw Exception("USB Hardware failure at LBA $currentLba: ${e.message}")
                        }
                        Thread.sleep(200)
                        try {
                            delegate.init()
                        } catch (_: Exception) {}
                    }
                }
                currentLba += blocksToTransfer.toLong()
                Thread.sleep(5)
            }
            buffer.limit(originalLimit)
        }
    }

    private class FatFileOutputStream(
        private val file: FsFile,
        private val fs: FileSystem
    ) : OutputStream() {
        private var offset: Long = 0L
        private var bytesSinceFlush: Long = 0L
        private val flushThreshold: Long = 2097152L // 2MB
        private val singleByte = ByteArray(1)

        override fun write(b: Int) {
            singleByte[0] = b.toByte()
            write(singleByte, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(b, off, len)
            file.write(offset, buffer)
            offset += len.toLong()
            bytesSinceFlush += len.toLong()
            if (bytesSinceFlush >= flushThreshold) {
                flush()
            }
        }

        override fun flush() {
            try {
                fs.flush()
                bytesSinceFlush = 0L
            } catch (_: Exception) {}
        }
    }
}
