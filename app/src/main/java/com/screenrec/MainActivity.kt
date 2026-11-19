package com.screenrec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var wantMic = false
    private var wantInternal = true

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
                .putExtra(RecorderService.EXTRA_RESULT_CODE, r.resultCode)
                .putExtra(RecorderService.EXTRA_DATA, data)
                .putExtra(RecorderService.EXTRA_MIC, wantMic)
                .putExtra(RecorderService.EXTRA_INTERNAL, wantInternal)
                .putExtra(
                    RecorderService.EXTRA_LANDSCAPE,
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                )
            ContextCompat.startForegroundService(this, i)
            moveTaskToBack(true) // ไปใช้แอปอื่นต่อได้เลย
        } else {
            toast("ยกเลิกการจับภาพหน้าจอ")
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if ((wantMic || wantInternal) && !granted(Manifest.permission.RECORD_AUDIO)) {
            toast("ต้องอนุญาตสิทธิ์ไมโครโฟน/เสียง ถึงจะอัดเสียงได้")
            return@registerForActivityResult
        }
        requestCapture()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startFlow(mic: Boolean, internal: Boolean) {
        wantMic = mic
        wantInternal = internal
        if (!Settings.canDrawOverlays(this)) {
            toast("อนุญาต \"แสดงทับแอปอื่น\" แล้วกลับมากด Record ใหม่")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val need = mutableListOf<String>()
        if ((mic || internal) && !granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (!granted(Manifest.permission.READ_PHONE_STATE)) need += Manifest.permission.READ_PHONE_STATE
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray()) else requestCapture()
    }

    private fun stopFromUi() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
    }

    private fun openLast(uri: Uri) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { toast("ไม่พบแอปเปิดวิดีโอ ดูได้ที่ Movies/ScreenRecorder") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    @Composable
    private fun Screen() {
        var mic by rememberSaveable { mutableStateOf(false) }
        var internal by rememberSaveable { mutableStateOf(true) }
        val st by RecorderService.state.collectAsState()
        val last by RecorderService.lastFile.collectAsState()
        val msg by RecorderService.message.collectAsState()
        val idle = st == RecorderService.State.IDLE

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Screen Recorder", fontSize = 28.sp)
            Spacer(Modifier.height(32.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("เสียงภายใน (จากแอปอื่น)", Modifier.weight(1f))
                Switch(internal, { internal = it }, enabled = idle)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ไมโครโฟน", Modifier.weight(1f))
                Switch(mic, { mic = it }, enabled = idle)
            }
            Spacer(Modifier.height(32.dp))

            if (idle) {
                Button(
                    onClick = { startFlow(mic, internal) },
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("● Record", fontSize = 24.sp) }
                Spacer(Modifier.height(8.dp))
                Text("หมุนจอให้ตรงทิศที่ต้องการก่อนกด Record (ล็อกทิศตลอดไฟล์)", fontSize = 12.sp)
            } else {
                Text(
                    if (st == RecorderService.State.PAUSED) "หยุดชั่วคราว" else "กำลังบันทึก...",
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { stopFromUi() },
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) { Text("■ Stop", fontSize = 20.sp) }
            }

            msg?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = Color(0xFFD32F2F))
            }
            val file = last
            if (idle && file != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { openLast(file) }, modifier = Modifier.fillMaxWidth()) {
                    Text("เปิดไฟล์ล่าสุด")
                }
                Text("บันทึกที่ Movies/ScreenRecorder", fontSize = 12.sp)
            }
        }
    }
}
