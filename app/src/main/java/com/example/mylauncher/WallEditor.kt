package com.example.mylauncher

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
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

fun wallEditor(a: Activity, uri: Uri) {
    val dm = a.resources.displayMetrics
    val sw = dm.widthPixels
    val sh = dm.heightPixels
    val bmp: Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= 2000 || o.outHeight / (s * 2) >= 2000) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Exception) {
        null
    }
    if (bmp == null) {
        Toast.makeText(a, "Failed", Toast.LENGTH_SHORT).show()
        return
    }
    val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val m = Matrix()
    val minS = maxOf(sw.toFloat() / bmp.width, sh.toFloat() / bmp.height)
    m.setScale(minS, minS)
    m.postTranslate((sw - bmp.width * minS) / 2f, (sh - bmp.height * minS) / 2f)
    val iv = ImageView(a)
    iv.scaleType = ImageView.ScaleType.MATRIX
    iv.setImageBitmap(bmp)
    iv.imageMatrix = m

    fun fix() {
        val v = FloatArray(9)
        m.getValues(v)
        var s = v[Matrix.MSCALE_X]
        if (s < minS) {
            m.postScale(minS / s, minS / s, sw / 2f, sh / 2f)
            m.getValues(v)
            s = v[Matrix.MSCALE_X]
        }
        val w = bmp.width * s
        val h = bmp.height * s
        v[Matrix.MTRANS_X] = maxOf(minOf(v[Matrix.MTRANS_X], 0f), sw - w)
        v[Matrix.MTRANS_Y] = maxOf(minOf(v[Matrix.MTRANS_Y], 0f), sh - h)
        m.setValues(v)
        iv.imageMatrix = m
    }

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

    val hint = TextView(a).apply {
        text = "Drag to move, pinch to zoom"
        setTextColor(-1); textSize = 14f; setPadding(40, 90, 40, 20)
        setShadowLayer(4f, 0f, 1f, -16777216)
    }
    val bar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, 20, 0, 70)
        addView(Button(a).apply {
            text = "Cancel"
            setOnClickListener { dlg.dismiss() }
        })
        addView(Button(a).apply {
            text = "Set wallpaper"
            setOnClickListener {
                val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(bmp, m, null)
                java.io.File(a.filesDir, "wall.jpg").outputStream().use {
                    out.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
                dlg.dismiss()
                Toast.makeText(a, "Wallpaper set", Toast.LENGTH_SHORT).show()
                a.recreate()
            }
        })
    }
    val frame = FrameLayout(a)
    frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
    frame.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP))
    frame.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    dlg.setContentView(frame)
    dlg.show()
}
