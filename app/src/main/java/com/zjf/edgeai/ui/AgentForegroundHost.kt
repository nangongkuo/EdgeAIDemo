package com.zjf.edgeai.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.zjf.edgeai.R
import com.zjf.edgeai.agent.api.RunId

interface AgentForegroundHost {
    fun start(runId: RunId)
    fun stop()

    data object None : AgentForegroundHost {
        override fun start(runId: RunId) = Unit
        override fun stop() = Unit
    }
}

class AndroidAgentForegroundHost(private val context: Context) : AgentForegroundHost {
    override fun start(runId: RunId) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context, AgentForegroundService::class.java)
                .putExtra(AgentForegroundService.EXTRA_RUN_ID, runId.value),
        )
    }

    override fun stop() {
        context.applicationContext.stopService(Intent(context, AgentForegroundService::class.java))
    }
}

/** 只承载同一进程中的 Durable Run，不参与逻辑 Worker 编排。 */
class AgentForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(EXTRA_RUN_ID).orEmpty()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_edge_ai)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(getString(R.string.agent_notification_text, runId.take(8)))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RUN_ID = "run_id"
        private const val CHANNEL_ID = "edge-agent-runs"
        private const val NOTIFICATION_ID = 1001
    }
}
