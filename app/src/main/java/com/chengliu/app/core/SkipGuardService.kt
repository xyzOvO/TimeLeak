package com.chengliu.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.chengliu.app.MainActivity
import com.chengliu.app.R

/**
 * 跳过广告的独立前台保活服务 —— 不碰 VPN、不读界面、不做任何点击判定。
 *
 * 无障碍服务是系统按授权绑定的：进程被省电策略回收后，部分 ROM（MIUI 实测）
 * 会把服务从 enabled_accessibility_services 移除，或留下一个已经死掉的 binder，
 * 界面事件再也送不进来，表现就是「开关是开的，但广告一次都跳不过」。
 * 这里只负责把进程钉在前台优先级，让连接不再因为进程被回收而掉线。
 *
 * 通知走 IMPORTANCE_MIN 的独立渠道：用户可以在系统通知设置里单独关掉这个渠道，
 * 通知消失但前台服务、无障碍授权都还在。关掉通知权限（Android 13+）同理。
 */
class SkipGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !AppPrefs(this).skipAdEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            startForeground(NOTIF_ID, buildNotification())
            // 系统回收后尽量重新拉起；重起时再读一次开关，关了就自行退出。
            START_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "无法进入前台，保活不生效", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID, "跳过广告保活", NotificationManager.IMPORTANCE_MIN,
        )
        ch.description = "保持无障碍连接稳定；可在系统通知设置里单独隐藏，不影响功能"
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("跳过广告守护中")
            .setContentText("可在通知设置里隐藏本条，功能不受影响")
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "SkipGuard"
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "chengliu_skip_guard"
        private const val ACTION_START = "com.chengliu.app.action.SKIP_GUARD_START"
        private const val ACTION_STOP = "com.chengliu.app.action.SKIP_GUARD_STOP"

        /** 幂等：开关开着就把进程钉在前台；重复调用不会叠加通知。 */
        fun ensure(ctx: Context) {
            if (!AppPrefs(ctx).skipAdEnabled) return
            val i = Intent(ctx, SkipGuardService::class.java).setAction(ACTION_START)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                Log.w(TAG, "无法启动保活服务", e)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, SkipGuardService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "无法停止保活服务", e)
            }
        }
    }
}
