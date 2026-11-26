package com.marsa.autobackup

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.*
import android.net.Uri
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var enrollment: EditText
    private lateinit var autoButton: Button

    companion object {
        private const val PICK_FOLDER = 1001
        private const val NOTIFICATION_PERMISSION = 1002
        private const val WORK_NAME = "marsa-periodic-backup"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFFF3F4F6.toInt())
        }

        val scroll = ScrollView(this)
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "MARSA Auto Backup"
            textSize = 26f
            setTextColor(0xFF101827.toInt())
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Backup aman ke server MARSA"
            textSize = 14f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        enrollment = EditText(this).apply {
            hint = "Enrollment key"
            inputType = 129
            maxLines = 1
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF334155.toInt())
            setPadding(0, 20, 0, 20)
            text = "Server: ${BackupApi.BASE}\nBelum terdaftar"
        }

        fun addButton(label: String, action: () -> Unit): Button {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }
            root.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
            )
            return button
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(enrollment)

        addButton("1. Daftarkan perangkat") {
            val key = enrollment.text.toString().trim()
            if (key.isEmpty()) {
                showStatus("Masukkan enrollment key terlebih dahulu.")
            } else {
                showStatus("Menghubungkan ke server...")
                executor.execute {
                    try {
                        val device = BackupApi.register(this, key)
                        runOnUiThread {
                            enrollment.text.clear()
                            showStatus(
                                "Perangkat terdaftar.\n" +
                                "ID: ${device.optInt("device_id")}\n" +
                                "Nama: ${device.optString("device_name")}\n" +
                                "Token tersimpan aman."
                            )
                        }
                    } catch (e: Exception) {
                        showStatus("Registrasi gagal: ${e.message}")
                    }
                }
            }
        }

        addButton("2. Pilih folder backup") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, PICK_FOLDER)
        }

        addButton("3. Backup sekarang") {
            startManualBackup()
        }

        autoButton = addButton(
            if (AppPrefs.auto(this)) "Matikan backup otomatis"
            else "Aktifkan backup otomatis"
        ) {
            toggleAutoBackup()
        }

        root.addView(status)
        setContentView(scroll)

        refreshStatus()
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            status.text = message
        }
    }

    private fun refreshStatus() {
        val registered = SecureStore.token(this) != null
        val folder = AppPrefs.folder(this)
        val automatic = AppPrefs.auto(this)

        status.text = buildString {
            append("Server: ${BackupApi.BASE}\n")
            append("Perangkat: ")
            append(if (registered) "Terdaftar" else "Belum terdaftar")
            append("\nFolder: ")
            append(if (folder != null) "Sudah dipilih" else "Belum dipilih")
            append("\nBackup otomatis: ")
            append(if (automatic) "Aktif" else "Tidak aktif")
        }

        autoButton.text = if (automatic) {
            "Matikan backup otomatis"
        } else {
            "Aktifkan backup otomatis"
        }
    }

    private fun startManualBackup() {
        val folder = AppPrefs.folder(this)
        if (SecureStore.token(this) == null) {
            showStatus("Daftarkan perangkat terlebih dahulu.")
            return
        }
        if (folder == null) {
            showStatus("Pilih folder backup terlebih dahulu.")
            return
        }

        showStatus("Backup dimulai...")
        executor.execute {
            try {
                BackupEngine.backupTree(this, Uri.parse(folder)) {
                    showStatus(it)
                }
            } catch (e: Exception) {
                showStatus("Backup gagal: ${e.message}")
            }
        }
    }

    private fun toggleAutoBackup() {
        if (AppPrefs.auto(this)) {
            WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
            AppPrefs.setAuto(this, false)
            refreshStatus()
            return
        }

        if (SecureStore.token(this) == null) {
            showStatus("Daftarkan perangkat terlebih dahulu.")
            return
        }

        if (AppPrefs.folder(this) == null) {
            showStatus("Pilih folder backup terlebih dahulu.")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            12, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        AppPrefs.setAuto(this, true)
        showStatus("Backup otomatis dijadwalkan setiap 12 jam.")
        refreshStatus()
    }

    @Deprecated("Legacy folder picker result")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                AppPrefs.setFolder(this, uri.toString())
                refreshStatus()
                showStatus("Folder backup berhasil dipilih.")
            } catch (e: Exception) {
                showStatus("Gagal menyimpan izin folder: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
