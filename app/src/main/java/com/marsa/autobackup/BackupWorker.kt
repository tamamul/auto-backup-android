package com.marsa.autobackup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL = "marsa_backup_channel"
        const val NOTIFICATION_ID = 4101

        fun notification(context: Context, message: String): Notification {
            val manager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "MARSA Auto Backup",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }

            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("MARSA Auto Backup")
                .setContentText(message)
                .setOngoing(true)
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folder = AppPrefs.folder(applicationContext)
            ?: return@withContext Result.failure()

        if (SecureStore.token(applicationContext) == null) {
            return@withContext Result.failure()
        }

        try {
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification(applicationContext, "Backup sedang berjalan")
                )
            )

            BackupEngine.backupTree(
                applicationContext,
                Uri.parse(folder)
            ) { message ->
                val manager = applicationContext.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager
                manager.notify(
                    NOTIFICATION_ID,
                    notification(applicationContext, message)
                )
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
