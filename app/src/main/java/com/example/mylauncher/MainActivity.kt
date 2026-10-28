package com.example.mylauncher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

data class Item(val label: String, val icon: Drawable?, val pkg: String?, val folder: String?)

object Prefs {
    fun sp(c: Context) = c.getSharedPreferences("p", 0)

    fun folders(c: Context): LinkedHashMap<String, MutableList<String>> {
        val m = LinkedHashMap<String, MutableList<String>>()
        val o = JSONObject(sp(c).getString("folders", "{}")!!)
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            m[k] = MutableList(a.length()) { a.getString(it) }
        }
        return m
    }

    fun save(c: Context, m: Map<String, List<String>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        sp(c).edit().putString("folders", o.toString()).apply()
    }

    fun move(c: Context, pkg: String, name: String?) {
        val m = folders(c)
        m.values.forEach { it.remove(pkg) }
        if (name != null) m.getOrPut(name) { mutableListOf() }.add(pkg)
        m.entries.removeAll { it.value.isEmpty() }
        save(c, m)
    }
}

fun launchables(c: Context): List<Item> {
    val pm = c.packageManager
    val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(q, 0)
        .map { Item(it.loadLabel(pm).toString(), it.loadIcon(pm), it.activityInfo.packageName, null) }
        .filter { it.pkg != c.packageName }
        .sortedBy { it.label.lowercase() }
}

fun launch(c: Context, pkg: String) {
    c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it) }
}

fun cell(c: Context, item: Item, w: Int, click: (Item) -> Unit, long: (Item) -> Unit): View {
    val d = c.resources.displayMetrics.density
    val icon = ImageView(c).apply {
        layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
        if (item.icon != null) setImageDrawable(item.icon)
        else background = GradientDrawable().apply {
            setColor(Color.parseColor("#99FFFFFF")); cornerRadius = 14 * d
        }
    }
    val name = TextView(c).apply {
        text = item.label; setTextColor(Color.WHITE); textSize = 12f
        maxLines = 1; gravity = Gravity.CENTER
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    return LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        layoutParams = GridLayout.LayoutParams().apply { width = w }
        addView(icon); addView(name)
        setOnClickListener { click(item) }
        setOnLongClickListener { long(item); true }
    }
}

fun pager(
    c: Context, items: List<Item>, perPage: Int, cols: Int, wPx: Int, topPad: Int,
    click: (Item) -> Unit, long: (Item) -> Unit
): RecyclerView {
    val pages = items.chunked(perPage).ifEmpty { listOf(emptyList()) }
    val rv = RecyclerView(c)
    rv.layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false)
    PagerSnapHelper().attachToRecyclerView(rv)
    rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val g = GridLayout(c).apply {
                columnCount = cols
                layoutParams = RecyclerView.LayoutParams(wPx, RecyclerView.LayoutParams.MATCH_PARENT)
                setPadding(0, topPad, 0, 0)
            }
            return object : RecyclerView.ViewHolder(g) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val g = h.itemView as GridLayout
            g.removeAllViews()
            pages[i].forEach { g.addView(cell(c, it, wPx / cols, click, long)) }
        }

        override fun getItemCount() = pages.size
    }
    return rv
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var sx = 0f
    private var sy = 0f
    private var maxP = 1

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); build() }

    override fun onBackPressed() {}

    private fun build() {
        root.removeAllViews()
        val folders = Prefs.folders(this)
        val inFolder = folders.values.flatten().toSet()
        val items = folders.keys.map { Item(it, null, null, it) } +
            launchables(this).filter { it.pkg !in inFolder }
        val w = resources.displayMetrics.widthPixels
        root.addView(pager(this, items, 20, 4, w, 140, { onClick(it) }, { onLong(it) }))
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 24f; setTextColor(Color.WHITE); setPadding(32, 40, 32, 40)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
    }

    private fun onClick(i: Item) {
        if (i.folder != null) showFolder(i.folder) else launch(this, i.pkg!!)
    }

    private fun onLong(i: Item) {
        if (i.folder != null) {
            AlertDialog.Builder(this).setTitle(i.folder)
                .setItems(arrayOf("Delete folder")) { _, _ ->
                    val m = Prefs.folders(this); m.remove(i.folder); Prefs.save(this, m); build()
                }.show()
        } else {
            val names = Prefs.folders(this).keys.toList()
            AlertDialog.Builder(this).setTitle(i.label)
                .setItems((listOf("New folder…") + names).toTypedArray()) { _, w ->
                    if (w == 0) {
                        val et = EditText(this)
                        AlertDialog.Builder(this).setTitle("Folder name").setView(et)
                            .setPositiveButton("OK") { _, _ ->
                                val n = et.text.toString().trim()
                                if (n.isNotEmpty()) { Prefs.move(this, i.pkg!!, n); build() }
                            }.show()
                    } else {
                        Prefs.move(this, i.pkg!!, names[w - 1]); build()
                    }
                }.show()
        }
    }

    private fun showFolder(name: String) {
        val pkgs = Prefs.folders(this)[name] ?: return
        val apps = launchables(this).filter { it.pkg in pkgs }
        val d = resources.displayMetrics.density
        val w = (resources.displayMetrics.widthPixels * 0.9).toInt()
        val dlg = AlertDialog.Builder(this).create()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6222222"))
            addView(TextView(context).apply {
                text = name; textSize = 18f; setTextColor(Color.WHITE); setPadding(40, 32, 40, 8)
            })
            addView(
                pager(context, apps, 12, 4, w, 8,
                    { dlg.dismiss(); launch(this@MainActivity, it.pkg!!) },
                    { x -> Prefs.move(this@MainActivity, x.pkg!!, null); dlg.dismiss(); build() }),
                LinearLayout.LayoutParams(w, (330 * d).toInt())
            )
        }
        dlg.setView(box)
        dlg.show()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; maxP = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> maxP = maxOf(maxP, e.pointerCount)
            MotionEvent.ACTION_UP -> {
                val dm = resources.displayMetrics
                val dy = e.rawY - sy
                val dx = e.rawX - sx
                if (Math.abs(dy) > 100 * dm.density && Math.abs(dy) > 1.5f * Math.abs(dx)) {
                    val dir = when {
                        dy < 0 && sy > dm.heightPixels * 0.85 -> "up"
                        dy > 0 && sy < dm.heightPixels * 0.15 -> "down"
                        else -> null
                    }
                    if (dir != null) {
                        val key = "g_" + dir + (if (maxP > 1) "2" else "1")
                        Prefs.sp(this).getString(key, null)?.let { launch(this, it) }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }
}

class SettingsActivity : Activity() {
    private val rows = listOf(
        "up1" to "1 finger: swipe up from bottom",
        "down1" to "1 finger: swipe down from top",
        "up2" to "2 fingers: swipe up from bottom",
        "down2" to "2 fingers: swipe down from top"
    )

    override fun onResume() { super.onResume(); show() }

    private fun show() {
        val apps = launchables(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 64)
        }
        fun tv(s: String, size: Float = 16f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, 24, 0, 8)
        }
        col.addView(tv("Edge gestures", 22f))
        col.addView(tv("Left/right edge swipe = Back\nBottom swipe up = Home\nBottom long hold = Recents"))
        col.addView(Button(this).apply {
            text = "Enable edge gestures"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(tv("Home gestures", 22f))
        rows.forEach { (key, label) ->
            val cur = Prefs.sp(this).getString("g_$key", null)
            col.addView(Button(this).apply {
                text = label + "\n→ " + (apps.firstOrNull { it.pkg == cur }?.label ?: "None")
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity).setTitle(label)
                        .setItems((listOf("None") + apps.map { it.label }).toTypedArray()) { _, w ->
                            val ed = Prefs.sp(this@SettingsActivity).edit()
                            if (w == 0) ed.remove("g_$key") else ed.putString("g_$key", apps[w - 1].pkg)
                            ed.apply(); show()
                        }.show()
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

class GestureService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val views = mutableListOf<View>()

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        val d = resources.displayMetrics.density
        add(Gravity.START, (18 * d).toInt(), -1, 0)
        add(Gravity.END, (18 * d).toInt(), -1, 1)
        add(Gravity.BOTTOM, -1, (22 * d).toInt(), 2)
    }

    private fun add(g: Int, w: Int, ht: Int, kind: Int) {
        val d = resources.displayMetrics.density
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        var x0 = 0f
        var y0 = 0f
        var done = false
        val hold = Runnable { done = true; performGlobalAction(GLOBAL_ACTION_RECENTS) }
        val v = View(this)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; done = false
                    if (kind == 2) h.postDelayed(hold, 600)
                }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    val dx = e.rawX - x0
                    val dy = e.rawY - y0
                    when (kind) {
                        0 -> if (dx > 60 * d) { done = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                        1 -> if (-dx > 60 * d) { done = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                        else -> {
                            if (Math.abs(dy) > 20 * d) h.removeCallbacks(hold)
                            if (-dy > 80 * d) {
                                done = true; h.removeCallbacks(hold)
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> h.removeCallbacks(hold)
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            w, ht, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        lp.gravity = g
        wm.addView(v, lp)
        views.add(v)
    }

    override fun onUnbind(i: Intent?): Boolean {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
        return super.onUnbind(i)
    }
}
