package com.marsa.autobackup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import java.util.UUID

object AppPrefs {
    private const val FILE = "marsa_backup"
    private fun prefs(c: Context) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceUuid(c: Context): String {
        val p = prefs(c)
        var id = p.getString("device_uuid", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            p.edit().putString("device_uuid", id).apply()
        }
        return id
    }

    fun folder(c: Context): String? =
        prefs(c).getString("folder_uri", null)

    fun setFolder(c: Context, uri: String) {
        prefs(c).edit().putString("folder_uri", uri).apply()
    }

    fun auto(c: Context) =
        prefs(c).getBoolean("auto_backup", false)

    fun setAuto(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("auto_backup", enabled).apply()
    }
}

object SecureStore {
    private const val ALIAS = "MARSA_BACKUP_TOKEN"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)

        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(c: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        c.getSharedPreferences("marsa_secure", Context.MODE_PRIVATE)
            .edit().putString("device_token", encoded).apply()
    }

    fun token(c: Context): String? {
        val encoded = c.getSharedPreferences(
            "marsa_secure", Context.MODE_PRIVATE
        ).getString("device_token", null) ?: return null

        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= 12) return null
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

object BackupApi {
    const val BASE = "https://file.marsa9.com"

    fun request(
        method: String,
        path: String,
        token: String? = null,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        val connection = (URL(BASE + path).openConnection()
            as HttpURLConnection)

        try {
            connection.requestMethod = method
            connection.connectTimeout = 30000
            connection.readTimeout = 180000
            connection.setRequestProperty("Accept", "application/json")

            if (token != null) {
                connection.setRequestProperty(
                    "Authorization", "Bearer $token"
                )
            }

            headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }

            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                if (!headers.containsKey("Content-Type")) {
                    connection.setRequestProperty(
                        "Content-Type", "application/json; charset=utf-8"
                    )
                }
                connection.outputStream.use { it.write(body) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (code !in 200..299) {
                val message = try {
                    JSONObject(response).optString("message", response)
                } catch (_: Exception) {
                    response
                }
                throw IOException("HTTP $code: $message")
            }

            val json = JSONObject(response)
            if (!json.optBoolean("success", false)) {
                throw IOException(
                    json.optString("message", "API menolak permintaan")
                )
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun register(c: Context, enrollmentKey: String): JSONObject {
        val uuid = AppPrefs.deviceUuid(c)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            .trim().take(150)

        val body = JSONObject()
            .put("device_uuid", uuid)
            .put("device_name", deviceName)
            .toString().toByteArray()

        val result = request(
            "POST",
            "/api/v1/device/register",
            body = body,
            headers = mapOf("X-Enrollment-Key" to enrollmentKey)
        )

        val device = result.getJSONObject("device")
        val token = device.getString("token")
        SecureStore.save(c, token)
        return device
    }
}

object BackupEngine {
    private const val CHUNK_SIZE = 4 * 1024 * 1024

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }

    private fun hashFile(c: Context, file: DocumentFile): String {
        val input = c.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Tidak bisa membaca ${file.name}")
        return input.use { sha256(it) }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw IOException("File berubah atau terpotong")
            if (count == 0) continue
            offset += count
        }
        return result
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(
                buffer, 0,
                minOf(buffer.size.toLong(), remaining).toInt()
            )
            if (count < 0) throw IOException("Gagal melanjutkan file")
            if (count > 0) remaining -= count
        }
    }

    private fun uploadFile(
        c: Context,
        file: DocumentFile,
        relativePath: String,
        token: String,
        progress: (String) -> Unit
    ) {
        val name = file.name ?: return
        val size = file.length()
        if (size < 0 || size > 10L * 1024 * 1024 * 1024) {
            throw IOException("Ukuran file tidak didukung: $relativePath")
        }

        progress("Memeriksa: $relativePath")
        val hash = hashFile(c, file)

        val initBody = JSONObject()
            .put("relative_path", relativePath)
            .put("original_filename", name)
            .put("sha256", hash)
            .put("size_bytes", size)
            .toString().toByteArray()

        val init = BackupApi.request(
            "POST",
            "/api/v1/backup/upload/init",
            token,
            initBody
        ).getJSONObject("upload")

        if (init.optBoolean("already_backed_up", false)) {
            progress("Sudah sama, dilewati: $relativePath")
            return
        }

        val sessionId = init.getString("session_id")
        val chunkSize = init.getInt("chunk_size")
        val totalChunks = init.getInt("total_chunks")

        if (chunkSize != CHUNK_SIZE) {
            throw IOException("Ukuran chunk server tidak sesuai")
        }

        val received = mutableSetOf<Int>()
        val receivedArray = init.optJSONArray("received_chunks") ?: JSONArray()
        for (i in 0 until receivedArray.length()) {
            received.add(receivedArray.getInt(i))
        }

        val input = c.contentResolver.openInputStream(file.uri)
            ?: throw IOException("File tidak dapat dibuka: $relativePath")

        input.use { source ->
            for (number in 0 until totalChunks) {
                val offset = number.toLong() * chunkSize
                val expected = if (number == totalChunks - 1) {
                    (size - offset).toInt()
                } else {
                    chunkSize
                }

                if (received.contains(number)) {
                    skipExactly(source, expected.toLong())
                    continue
                }

                val bytes = readExactly(source, expected)
                val chunkHash = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }

                val sid = URLEncoder.encode(sessionId, "UTF-8")
                val path = "/api/v1/backup/upload/chunk" +
                    "?session_id=$sid&chunk_number=$number"

                BackupApi.request(
                    "PUT",
                    path,
                    token,
                    bytes,
                    mapOf(
                        "Content-Type" to "application/octet-stream",
                        "X-Chunk-SHA256" to chunkHash
                    )
                )

                progress(
                    "Upload $relativePath: ${number + 1}/$totalChunks"
                )
            }
        }

        val completeBody = JSONObject()
            .put("session_id", sessionId)
            .toString().toByteArray()

        val completed = BackupApi.request(
            "POST",
            "/api/v1/backup/upload/complete",
            token,
            completeBody
        )

        val result = completed.optJSONObject("upload")
            ?: completed.optJSONObject("result")
            ?: completed
        val version = result?.optInt("version_no", 0) ?: 0
        progress("Tersimpan: $relativePath (versi $version)")
    }

    fun backupTree(
        c: Context,
        treeUri: Uri,
        progress: (String) -> Unit
    ) {
        val token = SecureStore.token(c)
            ?: throw IOException("Perangkat belum terdaftar")

        val root = DocumentFile.fromTreeUri(c, treeUri)
            ?: throw IOException("Folder backup tidak dapat dibuka")

        if (!root.isDirectory) {
            throw IOException("Lokasi backup bukan folder")
        }

        var count = 0

        fun walk(directory: DocumentFile, prefix: String) {
            for (child in directory.listFiles()) {
                val name = child.name ?: continue
                val relative = if (prefix.isEmpty()) name
                    else "$prefix/$name"

                if (child.isDirectory) {
                    walk(child, relative)
                } else if (child.isFile) {
                    uploadFile(c, child, relative, token, progress)
                    count++
                    progress("File diperiksa: $count")
                }
            }
        }

        walk(root, "")
        progress("Backup selesai. $count file diperiksa.")
    }
}
