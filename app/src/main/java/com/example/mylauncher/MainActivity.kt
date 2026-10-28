package com.example.mylauncher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

data class Item(val label: String, val icon: Drawable?, val pkg: String?, val folder: String?)
data class Page(val title: String?, val items: List<Item>)

object Bus { var onUp: (() -> Unit)? = null }

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

    fun initSeen(c: Context, all: List<String>) {
        if (!sp(c).contains("seen")) sp(c).edit().putStringSet("seen", all.toSet()).apply()
    }

    fun seenSet(c: Context): Set<String> = sp(c).getStringSet("seen", emptySet())!!

    fun markSeen(c: Context, pkg: String) {
        val s = HashSet(seenSet(c)); s.add(pkg)
        sp(c).edit().putStringSet("seen", s).apply()
    }

    // An app lives in only one folder
    fun move(c: Context, pkg: String, name: String?) {
        val m = folders(c)
        m.values.forEach { it.remove(pkg) }
        if (name != null) { m.getOrPut(name) { mutableListOf() }.add(pkg); markSeen(c, pkg) }
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

fun wallpaper(c: Context): Drawable {
    val f = java.io.File(c.filesDir, "wall.jpg")
    if (f.exists()) {
        val b = BitmapFactory.decodeFile(f.path)
        if (b != null) return android.graphics.drawable.BitmapDrawable(c.resources, b)
    }
    try {
        val d = WallpaperManager.getInstance(c).drawable
        if (d != null) return d
    } catch (e: Throwable) {
    }
    return GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#312E81"), Color.parseColor("#0F172A"))
    )
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
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
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

class PageVH(sv: ScrollView, val tv: TextView, val g: GridLayout) : RecyclerView.ViewHolder(sv)

fun pager(
    c: Context, pages: List<Page>, cols: Int, wPx: Int, topPad: Int,
    click: (Item) -> Unit, long: (Item) -> Unit, onTitle: (String) -> Unit
): RecyclerView {
    val rv = RecyclerView(c)
    rv.layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false)
    PagerSnapHelper().attachToRecyclerView(rv)
    rv.adapter = object : RecyclerView.Adapter<PageVH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): PageVH {
            val tv = TextView(c).apply {
                textSize = 20f; setTextColor(Color.WHITE); setPadding(48, 0, 48, 16)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
            val g = GridLayout(c).apply { columnCount = cols }
            val col = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, topPad, 0, 200)
                addView(tv); addView(g)
            }
            val sv = ScrollView(c).apply {
                layoutParams = RecyclerView.LayoutParams(wPx, RecyclerView.LayoutParams.MATCH_PARENT)
                addView(col)
            }
            return PageVH(sv, tv, g)
        }

        override fun onBindViewHolder(h: PageVH, i: Int) {
            val pg = pages[i]
            h.tv.text = pg.title ?: ""
            h.tv.visibility = if (pg.title == null) View.GONE else View.VISIBLE
            h.tv.setOnLongClickListener { pg.title?.let(onTitle); true }
            h.g.removeAllViews()
            pg.items.forEach { h.g.addView(cell(c, it, wPx / cols, click, long)) }
        }

        override fun getItemCount() = pages.size
    }
    return rv
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var pagerRv: RecyclerView? = null
    private var drawer: View? = null
    private var curName = "APPS"
    private var sx = 0f
    private var sy = 0f
    private var maxP = 1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Bus.onUp = { gesture("up", 1) }
        build()
    }

    override fun onPause() {
        super.onPause()
        Bus.onUp = null
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (drawer != null) closeDrawer() else pagerRv?.scrollToPosition(0)
    }

    override fun onBackPressed() {
        if (drawer != null) closeDrawer()
    }

    private fun refresh() {
        val was = drawer != null
        build()
        if (was) openDrawer(curName)
    }

    // Home: wallpaper + only newly installed apps
    private fun build() {
        drawer = null
        root.removeAllViews()
        val all = launchables(this)
        Prefs.initSeen(this, all.map { it.pkg!! })
        val seen = Prefs.seenSet(this)
        val inFolder = Prefs.folders(this).values.flatten().toSet()
        val fresh = all.filter { it.pkg !in inFolder && it.pkg !in seen }
        val w = resources.displayMetrics.widthPixels
        root.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(wallpaper(this@MainActivity))
        })
        val rv = pager(this, listOf(Page(null, fresh)), 4, w, 140,
            { launch(this, it.pkg!!) }, { onLong(it) }, {})
        pagerRv = rv
        root.addView(rv)
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 24f; setTextColor(Color.WHITE); setPadding(32, 40, 32, 40)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
    }

    // Drawer: tabs = APPS + folders, menu at top right
    private fun openDrawer(startAt: String? = null) {
        if (drawer != null) return
        val all = launchables(this)
        val byPkg = all.associateBy { it.pkg }
        val folders = Prefs.folders(this)
        val names = listOf("APPS") + folders.keys
        val pages = names.map { n ->
            Page(null, if (n == "APPS") all else folders[n]!!.mapNotNull { byPkg[it] })
        }
        val w = resources.displayMetrics.widthPixels
        val rv = pager(this, pages, 5, w, dp(86), { launch(this, it.pkg!!) }, { onLong(it) }, {})

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        val labels = mutableListOf<TextView>()
        val marks = mutableListOf<View>()

        fun select(i: Int) {
            curName = names[i]
            labels.forEachIndexed { j, t ->
                t.setTextColor(if (j == i) Color.WHITE else Color.parseColor("#99FFFFFF"))
            }
            marks.forEachIndexed { j, m -> m.visibility = if (j == i) View.VISIBLE else View.INVISIBLE }
            bar.getChildAt(i)?.let { hsv.smoothScrollTo(it.left - dp(40), 0) }
        }

        names.forEachIndexed { i, n ->
            val t = TextView(this).apply {
                text = n.uppercase(); textSize = 15f
                setPadding(dp(20), dp(12), dp(20), dp(8))
                setOnClickListener { rv.smoothScrollToPosition(i) }
                if (i > 0) setOnLongClickListener { renameFolder(n); true }
            }
            val m = View(this).apply { setBackgroundColor(Color.WHITE) }
            labels.add(t); marks.add(m)
            bar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(t); addView(m, LinearLayout.LayoutParams(-1, dp(3)))
            })
        }

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(r: RecyclerView, s: Int) {
                if (s == RecyclerView.SCROLL_STATE_IDLE) {
                    val p = (r.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                    if (p >= 0) select(p)
                }
            }
        })

        val idx = names.indexOf(startAt ?: "APPS").coerceAtLeast(0)
        select(idx)
        rv.post { rv.scrollToPosition(idx) }

        val menu = TextView(this).apply {
            text = "⋮"; textSize = 26f; setTextColor(Color.WHITE)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            setOnClickListener { v ->
                val pm = PopupMenu(this@MainActivity, v)
                pm.menu.add(0, 1, 0, "New folder")
                pm.menu.add(0, 2, 1, "Rename folder")
                pm.menu.add(0, 3, 2, "Delete folder")
                pm.menu.add(0, 4, 3, "Settings")
                pm.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> newFolder()
                        2 -> renameFolder(curName)
                        3 -> deleteFolder(curName)
                        else -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    true
                }
                pm.show()
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2101018"))
            setPadding(0, dp(30), 0, 0)
            addView(hsv, LinearLayout.LayoutParams(0, -2, 1f))
            addView(menu)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E6101018"))
            addView(rv)
            addView(top, FrameLayout.LayoutParams(-1, -2))
        }
        drawer = frame
        root.addView(frame)
    }

    private fun closeDrawer() {
        drawer?.let { root.removeView(it) }
        drawer = null
    }

    private fun gesture(dir: String, f: Int) {
        val pkg = Prefs.sp(this).getString("g_$dir$f", null)
        if (pkg != null) launch(this, pkg)
        else if (dir == "up" && f == 1) openDrawer()
    }

    private fun newFolder() {
        val et = EditText(this)
        AlertDialog.Builder(this).setTitle("Folder name").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                val m = Prefs.folders(this)
                if (n.isNotEmpty() && n != "APPS" && !m.containsKey(n)) {
                    m[n] = mutableListOf(); Prefs.save(this, m); curName = n; refresh()
                }
            }.show()
    }

    private fun renameFolder(name: String) {
        if (name == "APPS") {
            Toast.makeText(this, "Open a folder tab first", Toast.LENGTH_SHORT).show(); return
        }
        val et = EditText(this).apply { setText(name) }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                val m = Prefs.folders(this)
                if (n.isNotEmpty() && n != name && n != "APPS" && !m.containsKey(n)) {
                    val r = LinkedHashMap<String, List<String>>()
                    m.forEach { (k, v) -> r[if (k == name) n else k] = v }
                    Prefs.save(this, r); curName = n; refresh()
                }
            }.show()
    }

    private fun deleteFolder(name: String) {
        if (name == "APPS") {
            Toast.makeText(this, "Open a folder tab first", Toast.LENGTH_SHORT).show(); return
        }
        val m = Prefs.folders(this)
        m.remove(name); Prefs.save(this, m); curName = "APPS"; refresh()
    }

    private fun onLong(i: Item) {
        val p = i.pkg!!
        val m = Prefs.folders(this)
        val names = m.keys.toList()
        val cur = names.firstOrNull { m[it]!!.contains(p) }
        val isNew = p !in Prefs.seenSet(this)
        val opts = listOf("New folder…") + names.map { "→ $it" } +
            (if (cur != null) listOf("Remove from $cur")
            else if (isNew) listOf("Remove from Home") else emptyList())
        AlertDialog.Builder(this).setTitle(i.label)
            .setItems(opts.toTypedArray()) { _, w ->
                when {
                    w == 0 -> {
                        val et = EditText(this)
                        AlertDialog.Builder(this).setTitle("Folder name").setView(et)
                            .setPositiveButton("OK") { _, _ ->
                                val n = et.text.toString().trim()
                                if (n.isNotEmpty() && n != "APPS") {
                                    Prefs.move(this, p, n); curName = n; refresh()
                                }
                            }.show()
                    }
                    w <= names.size -> { Prefs.move(this, p, names[w - 1]); refresh() }
                    else -> {
                        if (cur != null) Prefs.move(this, p, null) else Prefs.markSeen(this, p)
                        refresh()
                    }
                }
            }.show()
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
                    val f = if (maxP > 1) 2 else 1
                    if (drawer != null) {
                        if (dy > 0 && sy < dm.heightPixels * 0.15) closeDrawer()
                    } else if (dy < 0 && sy > dm.heightPixels * 0.85) {
                        gesture("up", f)
                    } else if (dy > 0 && sy < dm.heightPixels * 0.15) {
                        gesture("down", f)
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

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == 7 && res == RESULT_OK && uri != null) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (o.outWidth / (s * 2) >= 1440) s *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                if (bmp != null) {
                    java.io.File(filesDir, "wall.jpg").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun show() {
        val apps = launchables(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 64)
        }
        fun tv(s: String, size: Float = 16f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, 24, 0, 8)
        }
        col.addView(tv("Wallpaper", 22f))
        col.addView(Button(this).apply {
            text = "Choose wallpaper"
            setOnClickListener {
                this@SettingsActivity.startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 7
                )
            }
        })
        col.addView(Button(this).apply {
            text = "Use system wallpaper"
            setOnClickListener {
                java.io.File(this@SettingsActivity.filesDir, "wall.jpg").delete()
                Toast.makeText(this@SettingsActivity, "Done", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(tv("Edge gestures", 22f))
        col.addView(tv("Left/right edge swipe = Back\nBottom swipe up = Home\nBottom long hold = Recents"))
        col.addView(Button(this).apply {
            text = "Enable edge gestures"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(tv("Home gestures", 22f))
        rows.forEach { (key, label) ->
            val none = if (key == "up1") "App drawer (default)" else "None"
            val cur = Prefs.sp(this).getString("g_$key", null)
            col.addView(Button(this).apply {
                text = label + "\n→ " + (apps.firstOrNull { it.pkg == cur }?.label ?: none)
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity).setTitle(label)
                        .setItems((listOf(none) + apps.map { it.label }).toTypedArray()) { _, w ->
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
                                val f = Bus.onUp
                                if (f != null) f() else performGlobalAction(GLOBAL_ACTION_HOME)
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
