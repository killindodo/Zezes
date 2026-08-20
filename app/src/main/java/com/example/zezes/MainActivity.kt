package com.example.zezes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.zezes.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import me.jahnen.libaums.core.UsbMassStorageDevice
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.zezes.USB_PERMISSION"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var availableDevices: List<UsbDevice> = emptyList()
    private var selectedIsoUri: Uri? = null
    private var flashStartTime: Long = 0L
    private var pulseJob: Job? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("zezes_prefs", Context.MODE_PRIVATE)
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val progress = intent.getIntExtra(FlashingService.EXTRA_PROGRESS, -1)
            val speed = intent.getDoubleExtra(FlashingService.EXTRA_SPEED, 0.0)
            val message = intent.getStringExtra(FlashingService.EXTRA_MESSAGE)
            val isFinished = intent.getBooleanExtra(FlashingService.EXTRA_IS_FINISHED, false)

            if (progress != -1) {
                binding.pbFlashingProgress.isIndeterminate = false
                binding.pbFlashingProgress.progress = progress
                binding.progressDetailText.text = getString(R.string.progress_template, progress, speed)
                binding.speedGraphView.addSpeedPoint(speed)

                if (flashStartTime > 0) {
                    val elapsed = System.currentTimeMillis() - flashStartTime
                    val totalSize = selectedIsoUri?.let { getUriSize(it) } ?: 0L
                    val remainingBytes = ((100 - progress) * totalSize) / 100
                    val remainingMillis = if (speed > 0.0) {
                        ((remainingBytes / (speed * 1024.0 * 1024.0)) * 1000.0).toLong()
                    } else {
                        0L
                    }
                    val metricsText = getString(
                        R.string.time_metrics_template,
                        formatDuration(elapsed),
                        formatDuration(remainingMillis)
                    )
                    binding.tvActivityStatus.tag = metricsText
                }
            }

            if (message != null) {
                binding.tvConsoleLog.text = message
                appendConsoleLog(message)
            }

            if (isFinished) {
                binding.pbFlashingProgress.isIndeterminate = false
                binding.pbFlashingProgress.progress = 100
                binding.flashButton.isEnabled = true
                binding.extractButton.isEnabled = true
                stopPulseAnimation()
                binding.tvActivityStatus.text = getString(R.string.system_ready)
            }
        }
    }

    private val pickIsoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedIsoUri = uri
            val fileName = getFileName(uri)
            binding.tvIsoFileName.text = getString(R.string.iso_file_name, fileName)
            val uriSize = getUriSize(uri)
            binding.tvIsoFileSize.text = getString(R.string.iso_file_size, formatSize(uriSize))
            val osType = if (fileName.contains("win", ignoreCase = true)) "Windows" else "Linux/Other"
            binding.tvIsoOsType.text = getString(R.string.iso_os_type, osType)
            updateFlashButtonState()
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanForUsbDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device?.deviceId == usbDevice?.deviceId) {
                        usbDevice = null
                        binding.tvConsoleLog.text = getString(R.string.usb_disconnected)
                        binding.deviceSelector.setText("", false)
                        binding.tvUsbDriveName.text = getString(R.string.usb_drive_name, getString(R.string.none_placeholder))
                        binding.tvUsbCapacity.text = getString(R.string.usb_total_capacity, getString(R.string.none_placeholder))
                        binding.tvUsbFsFormat.text = getString(R.string.usb_fs_format, getString(R.string.none_placeholder))
                        updateFlashButtonState()
                    }
                    scanForUsbDevices()
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            val name = device?.productName ?: device?.deviceName ?: getString(R.string.unknown)
                            binding.tvConsoleLog.text = getString(R.string.permission_denied, name)
                        } else if (device != null) {
                            val productName = device.productName ?: device.deviceName
                            binding.tvUsbDriveName.text = getString(R.string.usb_drive_name, productName)
                            usbDevice = device
                            updateFlashButtonState()
                            retrieveUsbDetails(device)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        setupThemeSelector()
        val savedThemeIndex = prefs.getInt("theme_index", 0)
        applyTheme(savedThemeIndex)

        binding.ivAppLogo.setOnLongClickListener {
            showThemeSelectionDialog()
            true
        }

        binding.ivGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/killindodo"))
            startActivity(intent)
        }

        binding.scanUsbButton.setOnClickListener {
            scanForUsbDevices()
        }

        binding.deviceSelector.setOnItemClickListener { _, _, position, _ ->
            val device = availableDevices[position]
            requestUsbPermission(device)
        }

        binding.pickIsoButton.setOnClickListener {
            pickIsoLauncher.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*"))
        }

        binding.flashButton.setOnClickListener {
            showConfirmationDialog(
                "Raw Flash",
                "This will wipe all data on the USB. Linux ISOs will work best in this mode."
            ) {
                startService(FlashingService.ACTION_START_FLASH)
            }
        }

        binding.extractButton.setOnClickListener {
            showConfirmationDialog(
                "Extract ISO",
                "This will format the USB to FAT32 and extract files. Recommended for Windows."
            ) {
                startService(FlashingService.ACTION_START_EXTRACTION)
            }
        }

        setupToolbar()

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val progressFilter = IntentFilter(FlashingService.ACTION_PROGRESS_UPDATE)
        ContextCompat.registerReceiver(this, progressReceiver, progressFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }
        requestStoragePermissions()
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION").apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestStoragePermissionLauncher.launch(
                arrayOf(
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_MEDIA_AUDIO"
                )
            )
        } else {
            requestStoragePermissionLauncher.launch(
                arrayOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"
                )
            )
        }
    }

    private fun appendConsoleLog(text: String) {
        val currentLog = binding.tvConsoleLog.text.toString()
        val newLog = if (currentLog.isEmpty()) text else "$currentLog\n$text"
        binding.tvConsoleLog.text = newLog
    }

    private fun startService(action: String) {
        val intent = Intent(this, FlashingService::class.java).apply {
            this.action = action
            putExtra(FlashingService.EXTRA_USB_DEVICE, usbDevice)
            putExtra(FlashingService.EXTRA_ISO_URI, selectedIsoUri)
        }
        ContextCompat.startForegroundService(this, intent)

        binding.pbFlashingProgress.isIndeterminate = true
        binding.tvConsoleLog.text = ""
        val productName = usbDevice?.productName ?: "USB Drive"
        appendConsoleLog("Starting process: $productName")
        flashStartTime = System.currentTimeMillis()
        startPulseAnimation()
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Proceed") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupThemeSelector() {}

    private fun showThemeSelectionDialog() {
        val popup = PopupMenu(this, binding.ivAppLogo)
        val warningItem = popup.menu.add(0, -1, 0, getString(R.string.warning_theme_change))
        warningItem.isEnabled = false

        ThemeManager.themes.forEachIndexed { i, theme ->
            popup.menu.add(0, i, i + 1, theme.name)
        }

        popup.setOnMenuItemClickListener { item ->
            if (item.itemId != -1) {
                applyTheme(item.itemId)
                prefs.edit().putInt("theme_index", item.itemId).apply()
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun applyTheme(index: Int) {
        val theme = ThemeManager.themes[index]

        binding.root.setBackgroundColor(theme.backgroundColor)
        (binding.toolbar.parent as? View)?.setBackgroundColor(theme.backgroundColor)
        binding.toolbar.setTitleTextColor(theme.accent1)
        binding.ivAppLogo.imageTintList = null
        binding.ivGithub.imageTintList = ColorStateList.valueOf(theme.accent1)

        val textViews = listOf(
            binding.tvIsoFileName,
            binding.tvIsoFileSize,
            binding.tvIsoOsType,
            binding.tvUsbDriveName,
            binding.tvUsbCapacity,
            binding.tvUsbFsFormat
        )
        textViews.forEach { it.setTextColor(theme.textColor) }

        binding.tvThemeHeader.setTextColor(theme.accent1)
        binding.progressDetailText.setTextColor(theme.accent1)

        listOf(
            binding.cardThemeSelection to theme.accent1,
            binding.cardIsoSelection to theme.accent1,
            binding.cardUsbTarget to theme.accent2
        ).forEach { (card, color) ->
            card.setCardBackgroundColor(theme.cardColor)
            card.strokeColor = color
        }

        val terminalCard = binding.tvConsoleLog.parent?.parent as? com.google.android.material.card.MaterialCardView
        terminalCard?.let {
            it.setCardBackgroundColor(if (theme.isLight) theme.cardColor else Color.BLACK)
            it.strokeColor = theme.accent3
        }

        binding.tvConsoleLog.setTextColor(theme.terminalTextColor)
        binding.tvActivityStatus.setTextColor(theme.accent3)
        binding.speedGraphView.setGraphColor(theme.accent1, theme.secondaryTextColor)

        (binding.pickIsoButton as? com.google.android.material.button.MaterialButton)?.strokeColor = ColorStateList.valueOf(theme.accent1)
        binding.pickIsoButton.setTextColor(theme.accent1)

        (binding.scanUsbButton as? com.google.android.material.button.MaterialButton)?.strokeColor = ColorStateList.valueOf(theme.accent2)
        binding.scanUsbButton.setTextColor(theme.accent2)

        binding.flashButton.backgroundTintList = ColorStateList.valueOf(theme.accent1)
        binding.flashButton.setTextColor(if (theme.isLight) theme.backgroundColor else Color.BLACK)

        binding.pbFlashingProgress.setIndicatorColor(theme.accent1)
        binding.pbFlashingProgress.trackColor = if (theme.isLight) Color.LTGRAY else Color.parseColor("#333333")

        binding.themeSelectorLayout.setBoxStrokeColor(theme.accent1)
        binding.themeSelectorLayout.hintTextColor = ColorStateList.valueOf(theme.accent1)
        binding.themeSelector.setTextColor(theme.textColor)

        binding.deviceSelectorLayout.setBoxStrokeColor(theme.accent2)
        binding.deviceSelectorLayout.hintTextColor = ColorStateList.valueOf(theme.accent2)
        binding.deviceSelector.setTextColor(theme.textColor)

        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (theme.isLight) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
            window.insetsController?.setSystemBarsAppearance(
                if (theme.isLight) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        window.statusBarColor = theme.backgroundColor
        window.navigationBarColor = theme.backgroundColor
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.menu.findItem(R.id.action_theme_switch)?.isVisible = false
    }

    private fun scanForUsbDevices() {
        val devices = usbManager.deviceList.values.filter { isMassStorageDevice(it) }
        availableDevices = devices

        if (availableDevices.isEmpty()) {
            binding.tvConsoleLog.text = getString(R.string.no_usb_found)
            binding.deviceSelector.setAdapter(null)
            return
        }

        val deviceNames = availableDevices.map {
            val name = it.productName ?: it.deviceName
            "$name (${it.deviceId})"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, deviceNames)
        binding.deviceSelector.setAdapter(adapter)

        if (availableDevices.size == 1) {
            binding.deviceSelector.setText(deviceNames[0], false)
            requestUsbPermission(availableDevices[0])
        } else {
            binding.tvConsoleLog.text = getString(R.string.found_devices, availableDevices.size)
            binding.deviceSelector.showDropDown()
        }
    }

    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 8) {
                return true
            }
        }
        return false
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun retrieveUsbDetails(device: UsbDevice) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("Zezes", "Retrieving details for device: ${device.deviceName}")
                val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(this@MainActivity)
                val usbStorage = massStorageDevices.find { it.usbDevice.deviceId == device.deviceId }

                if (usbStorage != null) {
                    usbStorage.init()
                    val partitions = usbStorage.partitions
                    var totalSize = 0L
                    for (partition in partitions) {
                        totalSize += partition.blockSize.toLong() * partition.blocks
                    }
                    val fsType = partitions.firstOrNull()?.fileSystem?.type?.toString() ?: getString(R.string.unknown)
                    Log.d("Zezes", "USB Size: $totalSize, FS: $fsType")

                    withContext(Dispatchers.Main) {
                        binding.tvUsbCapacity.text = getString(R.string.usb_total_capacity, formatSize(totalSize))
                        binding.tvUsbFsFormat.text = getString(R.string.usb_fs_format, fsType)
                    }
                } else {
                    Log.w("Zezes", "No libaums mass storage device found for ${device.deviceName}")
                    withContext(Dispatchers.Main) {
                        binding.tvUsbCapacity.text = getString(R.string.usb_total_capacity, getString(R.string.none_placeholder))
                        binding.tvUsbFsFormat.text = getString(R.string.usb_fs_format, getString(R.string.none_placeholder))
                    }
                }
            } catch (e: Exception) {
                Log.e("Zezes", "Error retrieving USB details", e)
                withContext(Dispatchers.Main) {
                    binding.tvUsbCapacity.text = getString(R.string.usb_total_capacity, getString(R.string.unknown))
                    binding.tvUsbFsFormat.text = getString(R.string.usb_fs_format, getString(R.string.unknown))
                    appendConsoleLog("Warning: Could not read USB details: ${e.message}")
                }
            }
        }
    }

    private fun updateFlashButtonState() {
        val ready = usbDevice != null && selectedIsoUri != null
        binding.flashButton.isEnabled = ready
        binding.extractButton.isEnabled = ready

        if (usbDevice == null) {
            binding.tvConsoleLog.text = getString(R.string.select_usb_hint)
            return
        }
        if (selectedIsoUri == null) {
            binding.tvConsoleLog.text = getString(R.string.select_iso_hint)
            return
        }
        val productName = usbDevice?.productName ?: "USB Drive"
        binding.tvConsoleLog.text = getString(R.string.ready_to_flash, productName)
    }

    private fun getFileName(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val colIndex = cursor.getColumnIndex("_display_name")
                if (cursor.moveToFirst() && colIndex != -1) {
                    name = cursor.getString(colIndex)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1 && name != null) {
                name = name?.substring(cut + 1)
            }
        }
        return name ?: "Unknown"
    }

    private fun getUriSize(uri: Uri): Long {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { pfd ->
            val len = pfd.length
            if (len != -1L) return len
        }
        return 0L
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val divisor = Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.getDefault(), "%.2f %s", size / divisor, units[digitGroups])
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun startPulseAnimation() {
        pulseJob?.cancel()
        pulseJob = lifecycleScope.launch {
            val pulses = arrayOf("1000", "0100", "0010", "0001", "0010", "0100")
            var i = 0
            while (isActive) {
                val pulse = pulses[i]
                val metrics = (binding.tvActivityStatus.tag as? String) ?: "Processing..."
                binding.tvActivityStatus.text = getString(R.string.activity_pulse_template, pulse, metrics)
                i = (i + 1) % pulses.size
                delay(200)
            }
        }
    }

    private fun stopPulseAnimation() {
        pulseJob?.cancel()
        pulseJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(progressReceiver)
        stopPulseAnimation()
    }
}
