package com.jjveleber.videoeditor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jjveleber.videoeditor.media.CompositionPlan
import com.jjveleber.videoeditor.media.Exporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Runs one export at a time in the foreground so it survives backgrounding and screen-off. */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService requires startForeground promptly, even if we then bail out.
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progressNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        val planJson = intent?.getStringExtra(EXTRA_PLAN)
        if (planJson == null) {
            if (ExportController.state.value is ExportState.Running) {
                ExportController.update(ExportState.Failed("Export failed"))
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val plan = Json.decodeFromString<CompositionPlan>(planJson)
        ExportController.update(ExportState.Running(0))
        job = scope.launch {
            val result = try {
                val uri = Exporter(applicationContext).export(plan) { percent ->
                    ExportController.update(ExportState.Running(percent))
                    notifications.notify(PROGRESS_ID, progressNotification(percent))
                }
                ExportState.Done(uri.toString())
            } catch (e: CancellationException) {
                ExportController.update(ExportState.Failed("Export cancelled"))
                throw e
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: "Export failed")
            }
            ExportController.update(result)
            ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notifications.notify(RESULT_ID, resultNotification(result))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Exporting video")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun resultNotification(state: ExportState): Notification {
        val (title, text) = when (state) {
            is ExportState.Done -> "Export complete" to "Saved to Movies/VideoEditor/"
            is ExportState.Failed -> "Export failed" to state.message
            else -> "Export" to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val EXTRA_PLAN = "plan"
        private const val CHANNEL_ID = "export"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2

        fun start(context: Context, plan: CompositionPlan) {
            if (ExportController.state.value is ExportState.Running) return
            ExportController.begin(plan.clips.map { it.uri }.toSet())
            try {
                val intent = Intent(context, ExportService::class.java).putExtra(EXTRA_PLAN, Json.encodeToString(plan))
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                ExportController.update(ExportState.Failed(e.message ?: "Export failed"))
            }
        }
    }
}
