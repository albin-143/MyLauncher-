package com.example.mylauncher

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private fun rot(b: Bitmap, deg: Float): Bitmap =
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)

fun wallEditor(a: Activity, uri: Uri) {
    val dm = a.resources.displayMetrics
    val sw = dm.widthPixels
    val sh = dm.heightPixels
    val loaded: Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= 2000 || o.outHeight / (s * 2) >= 2000) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Exception) {
        null
    }
    if (loaded == null) {
        Toast.makeText(a, "Failed", Toast.LENGTH_SHORT).show()
        return
    }
    var cur: Bitmap = loaded
    try {
        val deg = a.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (deg != 0f) cur = rot(cur, deg)
    } catch (e: Exception) {
    }

    val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val m = Matrix()
    val iv = ImageView(a)
    iv.scaleType = ImageView.ScaleType.MATRIX
    iv.setImageBitmap(cur)

    fun place(fill: Boolean) {
        val fx = sw.toFloat() / cur.width
        val fy = sh.toFloat() / cur.height
        val s = if (fill) maxOf(fx, fy) else minOf(fx, fy)
        m.setScale(s, s)
        m.postTranslate((sw - cur.width * s) / 2f, (sh - cur.height * s) / 2f)
        iv.imageMatrix = m
    }

    fun fix() {
        val fitS = minOf(sw.toFloat() / cur.width, sh.toFloat() / cur.height)
        val v = FloatArray(9)
        m.getValues(v)
        var s = v[Matrix.MSCALE_X]
        if (s < fitS) {
            m.postScale(fitS / s, fitS / s, sw / 2f, sh / 2f)
            m.getValues(v)
            s = v[Matrix.MSCALE_X]
        }
        val w = cur.width * s
        val h = cur.height * s
        v[Matrix.MTRANS_X] =
            if (w <= sw) (sw - w) / 2f else maxOf(minOf(v[Matrix.MTRANS_X], 0f), sw - w)
        v[Matrix.MTRANS_Y] =
            if (h <= sh) (sh - h) / 2f else maxOf(minOf(v[Matrix.MTRANS_Y], 0f), sh - h)
        m.setValues(v)
        iv.imageMatrix = m
    }

    place(true)

    var lx = 0f
    var ly = 0f
    val sd = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            fix()
            return true
        }
    })
    iv.setOnTouchListener { _, e ->
        sd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> { lx = e.x; ly = e.y }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !sd.isInProgress) {
                    m.postTranslate(e.x - lx, e.y - ly)
                    fix()
                }
                lx = e.x; ly = e.y
            }
        }
        true
    }

    fun btn(t: String, f: () -> Unit) = Button(a).apply {
        text = t; textSize = 12f; minWidth = 0; minimumWidth = 0
        setOnClickListener { f() }
    }

    val hint = TextView(a).apply {
        text = "Drag to move, pinch to zoom"
        setTextColor(Color.WHITE); textSize = 14f; setPadding(40, 90, 40, 20)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    val bar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(10, 20, 10, 70)
        addView(btn("Cancel") { dlg.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(btn("Rotate") {
            cur = rot(cur, 90f)
            iv.setImageBitmap(cur)
            place(true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(btn("Fit") { place(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(btn("Fill") { place(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(btn("Set") {
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            cv.drawColor(Color.BLACK)
            cv.drawBitmap(cur, m, null)
            java.io.File(a.filesDir, "wall.jpg").outputStream().use {
                out.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            dlg.dismiss()
            Toast.makeText(a, "Wallpaper set", Toast.LENGTH_SHORT).show()
            a.recreate()
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    val frame = FrameLayout(a)
    frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
    frame.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP))
    frame.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    dlg.setContentView(frame)
    dlg.show()
}
