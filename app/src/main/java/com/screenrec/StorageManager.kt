package com.screenrec

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageManager(private val ctx: Context) {

    class Output(val uri: Uri, val pfd: ParcelFileDescriptor)

    /** สร้างไฟล์ใน Movies/ScreenRecorder ชื่อ ScreenRec_yyyy-MM-dd_HH-mm-ss.mp4 */
    fun create(): Output {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRec_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("สร้างไฟล์ไม่ได้")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("เปิดไฟล์ไม่ได้")
        return Output(uri, pfd)
    }

    /** ปิดไฟล์ให้สมบูรณ์ แล้วให้แกลเลอรี่เห็นไฟล์ */
    fun finish(o: Output) {
        runCatching { o.pfd.close() }
        val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { ctx.contentResolver.update(o.uri, v, null, null) }
    }

    fun delete(o: Output) {
        runCatching { o.pfd.close() }
        runCatching { ctx.contentResolver.delete(o.uri, null, null) }
    }

    fun freeBytes(): Long =
        runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
}
