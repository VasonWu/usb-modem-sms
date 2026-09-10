package dev.usbsms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab { CALL, LOG, SMS, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var modem: Modem

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 权限结果不需要特别处理，没给权限时通话会提示
    }

    private val smsList = mutableStateListOf<Sms>()
    private val storages = mutableStateListOf<Storage>()
    private val supported = mutableStateListOf<String>()

    private var status by mutableStateOf("未连接")
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var connecting = false
    /** busy 期间到达的 +CMTI 记在这里，操作结束后补一次刷新 */
    private var refreshPending = false
    private var current by mutableStateOf("ME")
    private var tele by mutableStateOf(Telemetry())
    private var netMode by mutableStateOf<Int?>(null)
    private var modeLoading by mutableStateOf(false)
    private var console by mutableStateOf("")
    private var backup by mutableStateOf<ConfigBackup?>(null)
    private var identity by mutableStateOf<Identity?>(null)
    private val ifaces = mutableStateListOf<IfaceInfo>()

    // ---------- 通话相关状态 ----------
    private var call by mutableStateOf(ActiveCall())
    private val callLog = mutableStateListOf<CallLogEntry>()
    private var callDurationSec by mutableStateOf(0)
    private var showDialPad by mutableStateOf(false)
    private var showCallScreen by mutableStateOf(false)
    private var currentTab by mutableStateOf(Tab.SMS)

    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (call.state == CallState.CONNECTED) {
                callDurationSec++
                CallService.updateNotif(this@MainActivity, call.number, callDurationSec.toLong())
                callTimerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("modemsms", MODE_PRIVATE)
    }
    private var atIf by mutableStateOf(-1)

    private val snackbar = SnackbarHostState()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else status = "已拒绝 USB 授权"

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    connecting = false
                    modem.close()
                    connected = false
                    smsList.clear(); storages.clear(); supported.clear()
                    tele = Telemetry(); atIf = -1
                    identity = null; backup = null
                    if (!status.startsWith("已切换到")) {
                        status = "模块已断开。若是突然掉线，多半是 OTG 供电不足。"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge() 默认按系统的浅色/深色模式决定系统栏图标颜色，
        // 而本应用界面恒为深色。系统处于浅色模式时会被设成黑色图标，
        // 压在深靛蓝背景上完全看不见。这里显式声明「深色背景」，
        // 强制系统栏使用浅色图标，与系统主题无关。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        modem = Modem(this)
        modem.onNewSms = { lifecycleScope.launch { refresh() } }
        modem.onRinging = { lifecycleScope.launch { handleIncomingCall() } }
        modem.onCallEnded = { lifecycleScope.launch { handleCallEnded() } }

        // 启动时请求录音权限（通话必须）
        requestAudioPermissions()

        // 加载通话记录
        loadCallLog()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            DjiSmsTheme {
                Screen(
                    status = status,
                    connected = connected,
                    busy = busy,
                    tele = tele,
                    netMode = netMode,
                    modeLoading = modeLoading,
                    console = console,
                    backup = backup,
                    identity = identity,
                    ifaces = ifaces,
                    atIf = atIf,
                    sms = smsList,
                    storages = storages,
                    supported = supported,
                    current = current,
                    snackbar = snackbar,
                    // 通话相关
                    currentTab = currentTab,
                    onTabChange = { currentTab = it },
                    call = call,
                    callDurationSec = callDurationSec,
                    callLog = callLog,
                    showDialPad = showDialPad,
                    onToggleDialPad = { showDialPad = !showDialPad },
                    showCallScreen = showCallScreen,
                    onCall = { n -> lifecycleScope.launch { startCall(n) } },
                    onAnswer = { lifecycleScope.launch { answerCall() } },
                    onHangup = { lifecycleScope.launch { endCall() } },
                    onToggleMute = { lifecycleScope.launch { toggleMute() } },
                    onToggleSpeaker = { lifecycleScope.launch { toggleSpeaker() } },
                    onSendDtmf = { d -> lifecycleScope.launch { sendDtmf(d) } },
                    onDismissCallScreen = { showCallScreen = false },
                    onClearCallLog = { clearCallLog() },
                    // 原有功能
                    onConnect = ::connect,
                    onRefresh = { lifecycleScope.launch { refresh() } },
                    onPickStorage = { s -> lifecycleScope.launch { pickStorage(s) } },
                    onSend = { n, t -> lifecycleScope.launch { send(n, t) } },
                    onDeleteOne = { i -> lifecycleScope.launch { deleteOne(i) } },
                    onDeleteBulk = { f -> lifecycleScope.launch { deleteBulk(f) } },
                    onSetMode = { m -> lifecycleScope.launch { setMode(m) } },
                    onReadMode = { lifecycleScope.launch { readMode() } },
                    onRunAt = { c -> lifecycleScope.launch { runAt(c) } },
                    onClearConsole = { console = "" },
                    onRefreshIfaces = ::refreshIfaces,
                    onCopied = { c ->
                        lifecycleScope.launch { snackbar.showSnackbar("已复制 $c") }
                    },
                    onRestore = { lifecycleScope.launch { restore() } },
                    onSaveBackup = { lifecycleScope.launch { saveBackupManual() } },
                )
            }
        }

        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbReceiver) }
        callTimerHandler.removeCallbacks(callTimerRunnable)
        // 如果通话中退出，确保挂断
        if (call.state != CallState.IDLE) {
            lifecycleScope.launch {
                runCatching { modem.hangup() }
                CallService.stop(this@MainActivity)
            }
        }
        modem.release()
    }

    // ---------- 动作 ----------

    private fun connect() {
        // onCreate 与 USB 广播都会调这里。不加锁会让两个协程在
        // supported / storages 列表上竞争，界面出现重复的存储区按钮。
        if (connecting) return
        connecting = true

        lifecycleScope.launch {
            try {
                val dev = modem.findDevice()
                if (dev == null) {
                    status = "没找到模块。确认已插好，并使用带外部供电的 OTG 转接头。"
                    connected = false
                    return@launch
                }
                if (!modem.hasPermission(dev)) {
                    status = "正在请求 USB 授权…"
                    modem.requestPermission(dev)
                    return@launch
                }

                status = "正在探测 AT 口…"
                val err = modem.open(dev) { p -> runOnUiThread { status = p } }
                if (err != null) {
                    status = err
                    connected = false
                    return@launch
                }

                connected = true
                atIf = modem.atIfIndex
                status = "初始化中…"
                modem.init()

                // 先确认这颗模块的身份，备份才能按模块区分
                val id = modem.identity()
                identity = id
                loadBackup(id.key)
                if (backup == null) saveBackup()

                // 先取到完整结果再一次性替换，避免中间态被界面读到
                val st = modem.supportedStorages()
                supported.clear()
                supported.addAll(st)

                current = modem.storage
                netMode = modem.usbnetMode()
                refreshIfaces()
                refresh()
            } finally {
                connecting = false
            }
        }
    }

    // ---------- 诊断 / 备份 ----------

    private fun refreshIfaces() {
        ifaces.clear()
        modem.findDevice()?.let { ifaces.addAll(modem.describe(it)) }
    }

    // 备份按 IMEI 分键存储。两颗同型号模块的 VID/PID 完全相同，
    // 不按 IMEI 区分就会把 A 的配置恢复到 B 上。
    private fun bk(key: String, field: String) = "bk.$key.$field"

    private fun loadBackup(key: String) {
        val net = prefs.getString(bk(key, "usbnet"), null)
        if (net == null) {
            backup = null
            return
        }
        backup = ConfigBackup(
            imei = prefs.getString(bk(key, "imei"), "") ?: "",
            model = prefs.getString(bk(key, "model"), "") ?: "",
            usbnet = net,
            usbcfg = prefs.getString(bk(key, "usbcfg"), "") ?: "",
            savedAt = prefs.getLong(bk(key, "at"), 0L),
        )
    }

    /** 用户主动把当前配置设为新的恢复点。 */
    private suspend fun saveBackupManual() {
        if (!connected || busy) return
        busy = true
        val ok = runCatching { saveBackup(); true }.getOrDefault(false)
        busy = false
        snackbar.showSnackbar(if (ok) "已把当前配置存为恢复点" else "读取配置失败")
        drainRefresh()
    }

    private suspend fun saveBackup() {
        val b = runCatching { modem.readBackup() }.getOrNull() ?: return
        val key = b.imei.ifBlank { "unknown" }
        prefs.edit()
            .putString(bk(key, "imei"), b.imei)
            .putString(bk(key, "model"), b.model)
            .putString(bk(key, "usbnet"), b.usbnet)
            .putString(bk(key, "usbcfg"), b.usbcfg)
            .putLong(bk(key, "at"), b.savedAt)
            .apply()
        backup = b
    }

    /** 按备份里的 usbnet 值恢复。 */
    private suspend fun restore() {
        val b = backup ?: return
        val m = Regex("""(\d+)\s*$""").find(b.usbnet.trim())
            ?.groupValues?.get(1)?.toIntOrNull()
        if (m == null) {
            snackbar.showSnackbar("备份里解析不出模式值：${b.usbnet}")
            return
        }
        if (m == netMode) {
            snackbar.showSnackbar("当前已是备份中的模式（$m），无需恢复")
            return
        }
        setMode(m)
    }

    /** 直接下发任意 AT 指令，原样回显。排查用。 */
    private suspend fun runAt(cmd: String) {
        if (!connected || busy) return
        busy = true
        val r = runCatching { modem.raw(cmd) }.getOrElse { "异常：${it.message}" }
        busy = false
        val shown = r.replace("\r\n", "\n").trim().ifBlank { "（无响应）" }
        console = buildString {
            append(console)
            if (isNotEmpty()) append("\n\n")
            append("> ").append(cmd).append('\n').append(shown)
        }.takeLast(8000)
        drainRefresh()
    }

    /** 单独重读一次 USB 模式，不用重新插拔 */
    private suspend fun readMode() {
        if (!connected || modeLoading) return
        modeLoading = true
        netMode = runCatching { modem.usbnetMode() }.getOrNull()
        modeLoading = false
    }

    private suspend fun setMode(mode: Int) {
        if (!connected || busy) return
        busy = true
        val err = modem.setUsbnetMode(mode)
        busy = false
        if (err != null) {
            snackbar.showSnackbar(err)
        } else {
            connected = false
            smsList.clear(); storages.clear(); supported.clear()
            status = "已切换到 ${NET_MODES.first { it.code == mode }.name}，模块重启中，约 30 秒后自动重连。"
            snackbar.showSnackbar("模块正在重启")
        }
    }

    /**
     * +CMTI 的刷新请求走的也是这里，而模块只有一条 AT 通道，忙的时候没法插队。
     * 早先直接 `if (busy) return`，于是发短信或切存储区期间到的新短信被静默丢掉，
     * 界面上要等用户手动点刷新才出现——正是「插着当验证码接收器」最怕的失效方式。
     * 现在忙就记账，等当前操作让出通道再补。
     */
    private suspend fun refresh() {
        if (!connected) return
        if (busy) {
            refreshPending = true
            return
        }
        busy = true
        try {
            // 拉取期间又来 +CMTI，说明还有更新的短信没收进来，再跑一轮
            do {
                refreshPending = false
                tele = runCatching { modem.telemetry() }.getOrDefault(Telemetry())
                val list = runCatching { modem.listSms() }.getOrDefault(emptyList())
                smsList.clear(); smsList.addAll(list)
                storages.clear()
                storages.addAll(runCatching { modem.storageInfo() }.getOrDefault(emptyList()))
                current = modem.storage
            } while (refreshPending && connected)
        } finally {
            refreshPending = false
            busy = false
        }
    }

    /** 占用 AT 通道的操作收尾时调用，把攒下的刷新请求补上。 */
    private suspend fun drainRefresh() {
        if (refreshPending) refresh()
    }

    private suspend fun pickStorage(name: String) {
        if (!connected || busy) return
        busy = true
        val err = modem.setStorage(name)
        busy = false
        if (err != null) { snackbar.showSnackbar(err); drainRefresh() } else refresh()
    }

    private suspend fun send(number: String, text: String) {
        if (!connected) return
        busy = true
        val err = runCatching { modem.sendSms(number, text) }
            .getOrElse { it.message ?: "发送异常" }
        busy = false
        snackbar.showSnackbar(err ?: "已发送")
        if (err == null) refresh() else drainRefresh()
    }

    private suspend fun deleteOne(index: Int) {
        if (!connected || busy) return
        busy = true
        val err = modem.deleteOne(index)
        busy = false
        if (err != null) { snackbar.showSnackbar(err); drainRefresh() } else refresh()
    }

    private suspend fun deleteBulk(flag: Int) {
        if (!connected || busy) return
        busy = true
        val err = modem.deleteBulk(flag)
        busy = false
        if (err != null) { snackbar.showSnackbar(err); drainRefresh() } else refresh()
    }

    // ---------- 通话功能 ----------

    private fun requestAudioPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** 发起呼出 */
    private suspend fun startCall(number: String) {
        if (!connected || busy) {
            snackbar.showSnackbar("模块未连接")
            return
        }
        if (number.isBlank()) {
            snackbar.showSnackbar("请输入号码")
            return
        }

        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            snackbar.showSnackbar("需要录音权限才能通话")
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        busy = true
        showDialPad = false
        showCallScreen = true

        try {
            // 关键修复：先配置 USB 音频路径
            val audioErr = modem.setupUsbAudio()
            if (audioErr != null) {
                snackbar.showSnackbar("音频配置警告：$audioErr（可能影响通话音质）")
            }

            // 启用来电显示（呼入用）
            modem.enableCallerId()

            // 启动前台服务管理音频路由
            CallService.start(this, number)

            call = ActiveCall(
                state = CallState.DIALING,
                number = number,
                direction = CallDirection.OUTGOING,
            )
            callDurationSec = 0

            val err = modem.dial(number)
            if (err != null) {
                snackbar.showSnackbar(err)
                call = ActiveCall()
                CallService.stop(this)
                showCallScreen = false
                return
            }

            // 等待接通（最多等30秒）
            var connected = false
            repeat(30) {
                delay(1000)
                val status = modem.callStatus()
                if (status == 0) { // 0 = active
                    connected = true
                    return@repeat
                }
            }

            if (connected) {
                call = call.copy(state = CallState.CONNECTED, startTime = System.currentTimeMillis())
                callDurationSec = 0
                callTimerHandler.post(callTimerRunnable)
            } else {
                // 没接通也算一次呼出记录
                addCallLog(number, CallDirection.OUTGOING, 0)
                call = ActiveCall()
                CallService.stop(this)
                showCallScreen = false
            }
        } finally {
            busy = false
        }
    }

    /** 处理来电 */
    private suspend fun handleIncomingCall() {
        if (call.state == CallState.CONNECTED) return // 通话中忽略新来电

        val number = modem.lastIncomingNumber() ?: "未知号码"
        call = ActiveCall(
            state = CallState.RINGING,
            number = number,
            direction = CallDirection.INCOMING,
        )
        showCallScreen = true

        // 配置音频（提前准备好，接听时就有声音）
        modem.setupUsbAudio()
        modem.enableCallerId()
    }

    /** 接听来电 */
    private suspend fun answerCall() {
        if (call.state != CallState.RINGING) return
        busy = true

        try {
            CallService.start(this, call.number)
            val err = modem.answer()
            if (err != null) {
                snackbar.showSnackbar(err)
                return
            }
            call = call.copy(state = CallState.CONNECTED, startTime = System.currentTimeMillis())
            callDurationSec = 0
            callTimerHandler.post(callTimerRunnable)
        } finally {
            busy = false
        }
    }

    /** 结束通话 */
    private suspend fun endCall() {
        val wasConnected = call.state == CallState.CONNECTED
        val number = call.number
        val direction = call.direction
        val duration = callDurationSec

        callTimerHandler.removeCallbacks(callTimerRunnable)

        runCatching { modem.hangup() }
        CallService.stop(this)

        // 记录通话记录
        if (direction == CallDirection.INCOMING && call.state == CallState.RINGING) {
            // 未接来电
            addCallLog(number, direction, 0, missed = true)
        } else if (wasConnected || call.state == CallState.DIALING) {
            addCallLog(number, direction, duration)
        }

        call = ActiveCall()
        callDurationSec = 0
        showCallScreen = false
        busy = false
    }

    /** 通话被对方挂断 */
    private suspend fun handleCallEnded() {
        if (call.state == CallState.IDLE) return
        endCall()
    }

    /** 切换静音 */
    private suspend fun toggleMute() {
        call = call.copy(muted = !call.muted)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isMicrophoneMute = call.muted
        CallService.toggleMute(this)
    }

    /** 切换免提 */
    private suspend fun toggleSpeaker() {
        call = call.copy(speakerOn = !call.speakerOn)
        CallService.toggleSpeaker(this)
    }

    /** 发送DTMF */
    private suspend fun sendDtmf(digit: Char) {
        if (call.state != CallState.CONNECTED) return
        modem.sendDtmf(digit)
    }

    // ---------- 通话记录 ----------

    private fun addCallLog(
        number: String,
        direction: CallDirection,
        durationSec: Int,
        missed: Boolean = false
    ) {
        val entry = CallLogEntry(
            number = number,
            direction = direction,
            startTime = System.currentTimeMillis(),
            durationSec = durationSec,
            missed = missed,
        )
        callLog.add(0, entry)
        saveCallLog()
    }

    private fun clearCallLog() {
        callLog.clear()
        prefs.edit().remove(KEY_CALL_LOG).apply()
    }

    private fun loadCallLog() {
        val raw = prefs.getString(KEY_CALL_LOG, null) ?: return
        runCatching {
            val entries = raw.split("||").mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 4) {
                    CallLogEntry(
                        id = parts[0].toLongOrNull() ?: 0,
                        number = parts[1],
                        direction = if (parts[2] == "0") CallDirection.OUTGOING else CallDirection.INCOMING,
                        startTime = parts[3].toLongOrNull() ?: 0,
                        durationSec = parts.getOrNull(4)?.toIntOrNull() ?: 0,
                        missed = parts.getOrNull(5) == "1",
                    )
                } else null
            }
            callLog.clear()
            callLog.addAll(entries.sortedByDescending { it.id })
        }
    }

    private fun saveCallLog() {
        val raw = callLog.joinToString("||") {
            "${it.id}|${it.number}|${if (it.direction == CallDirection.OUTGOING) 0 else 1}|" +
                "${it.startTime}|${it.durationSec}|${if (it.missed) 1 else 0}"
        }
        prefs.edit().putString(KEY_CALL_LOG, raw).apply()
    }

    companion object {
        private const val KEY_CALL_LOG = "call_log"
    }
}
