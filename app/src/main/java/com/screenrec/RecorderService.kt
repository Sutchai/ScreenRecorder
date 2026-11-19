package com.screenrec

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

class RecorderService : Service() {

    enum class State { IDLE, RECORDING, PAUSED }

    companion object {
        val state = MutableStateFlow(State.IDLE)
        val lastFile = MutableStateFlow<Uri?>(null)
        val message = MutableStateFlow<String?>(null)

        const val ACTION_START = "com.screenrec.START"
        const val ACTION_PAUSE = "com.screenrec.PAUSE"
        const val ACTION_RESUME = "com.screenrec.RESUME"
        const val ACTION_STOP = "com.screenrec.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"
        const val EXTRA_INTERNAL = "internal"
        const val EXTRA_LANDSCAPE = "landscape"

        private const val CHANNEL = "recorder"
        private const val NOTIF_ID = 1
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
    }

    private val main = Handler(Looper.getMainLooper())
    private var manager: RecordingManager? = null
    private var floating: FloatingControl? = null
    private lateinit var storage: StorageManager

    private var segmentStart = 0L
    private var accumulated = 0L
    private var stopping = false

    private var screenOffReceiver: BroadcastReceiver? = null
    private var phoneListener: PhoneStateListener? = null
    private var phoneCallback: TelephonyCallback? = null

    private fun elapsed(): Long =
        accumulated + if (state.value == State.RECORDING) SystemClock.elapsedRealtime() - segmentStart else 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        storage = StorageManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRec()
            ACTION_RESUME -> resumeRec()
            ACTION_STOP -> stopRec()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ start

    private fun startRecording(intent: Intent) {
        if (state.value != State.IDLE) return
        stopping = false
        message.value = null

        val mic = intent.getBooleanExtra(EXTRA_MIC, false)
        val internal = intent.getBooleanExtra(EXTRA_INTERNAL, true)
        val landscape = intent.getBooleanExtra(EXTRA_LANDSCAPE, false)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { fail("ไม่ได้รับอนุญาตจับภาพหน้าจอ"); return }

        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if ((mic || internal) && audioGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        try {
            startForeground(NOTIF_ID, buildNotification(State.RECORDING), type)
        } catch (e: Exception) {
            fail("เริ่ม Foreground Service ไม่ได้: ${e.message}"); return
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            manager = RecordingManager(
                this, projection, mic && audioGranted, internal && audioGranted, landscape,
                onProjectionStopped = { main.post { stopRec() } },
                onError = { msg -> main.post { message.value = msg; stopRec() } }
            )
            manager!!.start()
        } catch (e: Exception) {
            manager = null
            fail(e.message ?: "เริ่มบันทึกไม่ได้"); return
        }

        accumulated = 0L
        segmentStart = SystemClock.elapsedRealtime()
        state.value = State.RECORDING

        floating = FloatingControl(
            this,
            onPauseResume = { if (state.value == State.RECORDING) pauseRec() else resumeRec() },
            onStop = { stopRec() },
            elapsedMs = { elapsed() }
        ).also { runCatching { it.show() } }

        registerTriggers()
        main.postDelayed(storageCheck, 5000)
    }

    private fun fail(msg: String) {
        message.value = msg
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ pause / resume / stop

    private fun pauseRec() {
        if (state.value != State.RECORDING) return
        manager?.pause()
        accumulated += SystemClock.elapsedRealtime() - segmentStart
        state.value = State.PAUSED
        refreshUi()
    }

    private fun resumeRec() {
        if (state.value != State.PAUSED) return
        manager?.resume()
        segmentStart = SystemClock.elapsedRealtime()
        state.value = State.RECORDING
        refreshUi()
    }

    private fun stopRec() {
        if (state.value == State.IDLE || stopping) return
        stopping = true
        main.removeCallbacks(storageCheck)
        unregisterTriggers()
        floating?.hide(); floating = null
        val m = manager
        Thread {
            runCatching { m?.stop() }
            val uri = m?.outputUri
            main.post {
                manager = null
                if (uri != null) lastFile.value = uri
                state.value = State.IDLE
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun refreshUi() {
        floating?.setPaused(state.value == State.PAUSED)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(state.value))
    }

    // ------------------------------------------------------------ auto pause / storage

    private val storageCheck = object : Runnable {
        override fun run() {
            if (storage.freeBytes() < MIN_FREE_BYTES) {
                message.value = "พื้นที่เครื่องเหลือน้อย หยุดบันทึกและปิดไฟล์แล้ว"
                Toast.makeText(this@RecorderService, message.value, Toast.LENGTH_LONG).show()
                stopRec()
            } else {
                main.postDelayed(this, 5000)
            }
        }
    }

    private fun registerTriggers() {
        // ล็อกหน้าจอ -> Pause
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { pauseRec() }
        }
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // สายเข้า -> Pause (ต้องมี READ_PHONE_STATE ถ้าไม่ให้ก็ข้ามไป)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (Build.VERSION.SDK_INT >= 31) {
                    val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(s: Int) {
                            if (s != TelephonyManager.CALL_STATE_IDLE) pauseRec()
                        }
                    }
                    phoneCallback = cb
                    tm.registerTelephonyCallback(mainExecutor, cb)
                } else {
                    @Suppress("DEPRECATION")
                    val l = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(s: Int, n: String?) {
                            if (s != TelephonyManager.CALL_STATE_IDLE) pauseRec()
                        }
                    }
                    phoneListener = l
                    @Suppress("DEPRECATION")
                    tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                }
            }
        }
    }

    private fun unregisterTriggers() {
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        runCatching {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31) {
                phoneCallback?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        phoneCallback = null; phoneListener = null
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Screen Recorder", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun action(act: String, label: String, code: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, RecorderService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun buildNotification(s: State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen Recorder")
            .setContentText(if (s == State.PAUSED) "หยุดชั่วคราว" else "กำลังบันทึกหน้าจอ")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (s == State.PAUSED) b.addAction(action(ACTION_RESUME, "Resume", 2))
        else b.addAction(action(ACTION_PAUSE, "Pause", 1))
        b.addAction(action(ACTION_STOP, "Stop", 3))
        return b.build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterTriggers()
        floating?.hide()
        super.onDestroy()
    }
}
