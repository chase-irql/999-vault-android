package com.vault999.android.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vault999.android.R
import com.vault999.android.MainActivity
import com.vault999.android.VaultApplication
import com.vault999.android.model.DownloadJob
import com.vault999.android.model.DownloadStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class VaultTransferScheduler(private val context: Context) {
    fun schedule(id: String, estimatedBytes: Long?, wifiOnly: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            val extras = android.os.PersistableBundle().apply { putString(KEY_ID, id) }
            val builder = JobInfo.Builder(jobId(id), ComponentName(context, VaultTransferJobService::class.java))
                .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setUserInitiated(true)
                .setExtras(extras)
            estimatedBytes?.takeIf { it >= 0 }?.let { builder.setEstimatedNetworkBytes(it, 0) }
            check(context.getSystemService(JobScheduler::class.java).schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) {
                "Android declined the user-initiated transfer"
            }
        } else {
            val request = OneTimeWorkRequestBuilder<VaultTransferWorker>()
                .setInputData(Data.Builder().putString(KEY_ID, id).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun stop(id: String) {
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).cancel(jobId(id))
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    companion object {
        const val KEY_ID = "download_id"
        fun workName(id: String) = "vault-transfer-$id"
        fun jobId(id: String): Int = 0x39900000 or (id.hashCode() and 0x000fffff)
    }
}

class VaultTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(VaultTransferScheduler.KEY_ID) ?: return Result.failure()
        val repository = (applicationContext as VaultApplication).graph.downloadRepository
        setForeground(notificationInfo(id, null))
        return when (repository.execute(id) { progress -> setForeground(notificationInfo(id, progress)) }) {
            TransferExecutionResult.FINISHED -> Result.success()
            TransferExecutionResult.RETRY -> Result.retry()
        }
    }

    private fun notificationInfo(id: String, job: DownloadJob?): ForegroundInfo =
        ForegroundInfo(VaultTransferScheduler.jobId(id), TransferNotifications.build(applicationContext, id, job))
}

@RequiresApi(34)
class VaultTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(VaultTransferScheduler.KEY_ID) ?: return false
        setNotification(params, VaultTransferScheduler.jobId(id), TransferNotifications.build(this, id, null), JOB_END_NOTIFICATION_POLICY_DETACH)
        lateinit var launched: Job
        launched = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                (application as VaultApplication).graph.downloadRepository.execute(id) { progress ->
                    setNotification(params, VaultTransferScheduler.jobId(id), TransferNotifications.build(this@VaultTransferJobService, id, progress), JOB_END_NOTIFICATION_POLICY_DETACH)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                TransferExecutionResult.RETRY
            }
            // onStopJob owns rescheduling after the system stops a job. Only a still-current run
            // may call jobFinished; this avoids a cancelled coroutine racing that callback.
            if (running.remove(params.jobId, launched)) {
                jobFinished(params, result == TransferExecutionResult.RETRY)
            }
        }
        running.put(params.jobId, launched)?.cancel(CancellationException("Transfer replaced"))
        launched.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel(CancellationException("System stopped user transfer"))
        return true
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(VaultTransferScheduler.KEY_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as VaultApplication).graph.downloadRepository
                when (intent.action) {
                    ACTION_CANCEL -> repository.cancel(id)
                    ACTION_PAUSE -> repository.pause(id)
                    ACTION_RESUME -> repository.resume(id)
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.vault999.android.download.CANCEL"
        const val ACTION_PAUSE = "com.vault999.android.download.PAUSE"
        const val ACTION_RESUME = "com.vault999.android.download.RESUME"
    }
}

object TransferNotifications {
    private const val CHANNEL = "vault_transfers"

    fun build(context: Context, id: String, job: DownloadJob?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Vault downloads", NotificationManager.IMPORTANCE_LOW))
        val stage = job?.stage ?: DownloadStage.QUEUED
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(job?.displayName ?: "999 Vault download")
            .setContentText(stage.name.lowercase().replace('_', ' '))
            .setOngoing(stage !in setOf(DownloadStage.COMPLETED, DownloadStage.CANCELLED, DownloadStage.FAILED))
            .setAutoCancel(stage in setOf(DownloadStage.COMPLETED, DownloadStage.COMPLETED_WITH_ERRORS, DownloadStage.CANCELLED, DownloadStage.FAILED))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    VaultTransferScheduler.jobId(id),
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
        val total = job?.bytesTotal
        if (total != null && total > 0) builder.setProgress(1000, ((job.bytesCompleted * 1000L) / total).coerceIn(0, 1000).toInt(), false)
        else builder.setProgress(0, 0, stage in setOf(DownloadStage.QUEUED, DownloadStage.DOWNLOADING))
        if (stage !in setOf(DownloadStage.COMPLETED, DownloadStage.COMPLETED_WITH_ERRORS, DownloadStage.CANCELLED, DownloadStage.FAILED)) {
            val pauseOrResume = if (stage == DownloadStage.PAUSED) DownloadActionReceiver.ACTION_RESUME else DownloadActionReceiver.ACTION_PAUSE
            builder.addAction(0, if (stage == DownloadStage.PAUSED) "Resume" else "Pause", action(context, id, pauseOrResume, 1))
            builder.addAction(0, "Cancel", action(context, id, DownloadActionReceiver.ACTION_CANCEL, 2))
        }
        return builder.build()
    }

    private fun action(context: Context, id: String, action: String, offset: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        VaultTransferScheduler.jobId(id) + offset,
        Intent(context, DownloadActionReceiver::class.java).setAction(action).putExtra(VaultTransferScheduler.KEY_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
