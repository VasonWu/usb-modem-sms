package dev.usbsms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 通话前台服务。
 *
 * 职责：
 * 1. 作为前台服务保持通话期间App活跃，不被系统杀死
 * 2. 管理 Android AudioManager 的音频模式与路由
 * 3. 处理 USB 音频设备的连接与切换
 *
 * 关于"对方听不到声音"的修复：
 * - 设置 MODE_IN_COMMUNICATION 而不是 MODE_NORMAL
 * - 音频路由到 USB 设备（模块的 UAC 接口）
 * - 确保麦克风不被静音、录音权限已授予
 */
class CallService : Service() {

    private lateinit var audioManager: AudioManager
    private var wasSpeakerOn = false
    private var originalMode = AudioManager.MODE_NORMAL

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> startCall(intent.getStringExtra(EXTRA_NUMBER) ?: "")
            ACTION_END_CALL -> endCall()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_TOGGLE_SPEAKER -> toggleSpeaker()
            ACTION_UPDATE_NOTIFICATION -> updateNotification(
                intent.getStringExtra(EXTRA_NUMBER) ?: "",
                intent.getLongExtra(EXTRA_DURATION, 0)
            )
        }
        return START_NOT_STICKY
    }

    private fun startCall(number: String) {
        // 保存原始状态以便恢复
        wasSpeakerOn = audioManager.isSpeakerphoneOn
        originalMode = audioManager.mode

        // 关键：设置为通话模式
        // MODE_IN_COMMUNICATION 是 VoIP/USB 通话的正确模式
        // 比 MODE_IN_CALL 更适合 USB 音频设备
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // 先关闭扬声器，等 USB 设备就绪后再路由
        audioManager.isSpeakerphoneOn = false

        // 尝试路由到 USB 音频设备
        routeAudioToUsb()

        // 设置通话音量到合适大小
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            (maxVol * 0.8f).toInt(),
            0
        )

        // 启动前台服务
        startForeground(NOTIFICATION_ID, buildNotification(number, 0))
    }

    /**
     * 路由音频到 USB 设备。
     *
     * 这是修复"对方听不到我说话"的核心：
     * - 模块的 USB 音频接口（UAC）同时包含输入（麦克风）和输出（扬声器）
     * - 必须确保 Android 将音频输入和输出都路由到 USB 设备
     * - 如果只路由了输出没路由输入，就会出现"能听到对方但对方听不到"
     */
    private fun routeAudioToUsb() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：用 setCommunicationDevice 明确指定设备
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            val usbDevice = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (usbDevice != null) {
                audioManager.setCommunicationDevice(usbDevice)
                return
            }
        }

        // 旧版本 fallback：
        // 关闭扬声器，系统会自动走已连接的 USB 音频
        // 前提是 USB 音频设备已被系统识别
        audioManager.isSpeakerphoneOn = false

        // 某些 ROM 下需要明确开启蓝牙 SCO 或设置为有线耳机模式
        // 但对 USB 音频来说，系统通常会自动处理
    }

    private fun toggleMute() {
        val muted = !audioManager.isMicrophoneMute
        audioManager.isMicrophoneMute = muted
        // 同时给模块也发静音命令（双重保险）
        // 注意：这里不直接调用 Modem，因为 Service 里没有 Modem 实例
        // 静音由 MainActivity 统一管理
    }

    private fun toggleSpeaker() {
        val on = !audioManager.isSpeakerphoneOn
        if (on) {
            // 切换到内置扬声器 + 内置麦克风
            audioManager.isSpeakerphoneOn = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 切换到内置设备
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val builtIn = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                builtIn?.let { audioManager.setCommunicationDevice(it) }
            }
        } else {
            // 切回 USB 音频
            audioManager.isSpeakerphoneOn = false
            routeAudioToUsb()
        }
    }

    private fun endCall() {
        // 恢复音频状态
        runCatching {
            audioManager.isMicrophoneMute = false
            audioManager.isSpeakerphoneOn = wasSpeakerOn
            audioManager.mode = originalMode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(number: String, durationSec: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(number, durationSec))
    }

    private fun buildNotification(number: String, durationSec: Long): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mins = durationSec / 60
        val secs = durationSec % 60
        val timeStr = "%02d:%02d".format(mins, secs)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通话中  $number")
            .setContentText(timeStr)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "通话",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "通话进行中的前台通知"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START_CALL = "dev.usbsms.action.START_CALL"
        const val ACTION_END_CALL = "dev.usbsms.action.END_CALL"
        const val ACTION_TOGGLE_MUTE = "dev.usbsms.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "dev.usbsms.action.TOGGLE_SPEAKER"
        const val ACTION_UPDATE_NOTIFICATION = "dev.usbsms.action.UPDATE_NOTIF"

        const val EXTRA_NUMBER = "number"
        const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "call_service"
        private const val NOTIFICATION_ID = 1001

        fun start(ctx: Context, number: String) {
            val intent = Intent(ctx, CallService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_NUMBER, number)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, CallService::class.java).apply {
                action = ACTION_END_CALL
            }
            ctx.startService(intent)
        }

        fun toggleMute(ctx: Context) {
            val intent = Intent(ctx, CallService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
            }
            ctx.startService(intent)
        }

        fun toggleSpeaker(ctx: Context) {
            val intent = Intent(ctx, CallService::class.java).apply {
                action = ACTION_TOGGLE_SPEAKER
            }
            ctx.startService(intent)
        }

        fun updateNotif(ctx: Context, number: String, durationSec: Long) {
            val intent = Intent(ctx, CallService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_DURATION, durationSec)
            }
            ctx.startService(intent)
        }
    }
}
