package com.example.mylauncher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

data class Item(val label: String, val icon: Drawable?, val pkg: String?, val folder: String?)
data class Page(val items: List<Item>)

object Bus { var onUp: (() -> Unit)? = null }

object Prefs {
    fun sp(c: Context) = c.getSharedPreferences("p", 0)
    fun gi(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun gb(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)

    fun dock(c: Context): List<String> =
        sp(c).getString("dockapps", "")!!.split(",").filter { it.isNotEmpty() }

    fun setDock(c: Context, l: List<String>) {
        sp(c).edit().putString("dockapps", l.joinToString(",")).apply()
    }

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

    fun move(c: Context, pkg: String, name: String?) {
        val m = folders(c)
        m.values.forEach { it.remove(pkg) }
        if (name != null) { m.getOrPut(name) { mutableListOf() }.add(pkg); markSeen(c, pkg) }
        save(c, m)
    }
}

fun loadApps(c: Context): List<Item> {
    val pm = c.packageManager
    val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(q, 0)
        .map { Item(it.loadLabel(pm).toString(), it.loadIcon(pm), it.activityInfo.packageName, null) }
        .filter { it.pkg != c.packageName }
        .sortedBy { it.label.lowercase() }
}

object Cache {
    @Volatile var items: List<Item>? = null

    @Synchronized
    fun get(c: Context): List<Item> {
        items?.let { return it }
        val l = loadApps(c)
        items = l
        return l
    }

    fun clear() { items = null }
}

fun launchables(c: Context): List<Item> = Cache.get(c.applicationContext)

fun launch(c: Context, pkg: String) {
    c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it) }
}

object Wall {
    var d: Drawable? = null
    var stamp = -1L
}

fun wallpaper(c: Context): Drawable {
    val f = java.io.File(c.filesDir, "wall.jpg")
    val st = if (f.exists()) f.lastModified() else 0L
    Wall.d?.let { if (Wall.stamp == st) return it }
    var r: Drawable? = null
    if (st != 0L) {
        val b = BitmapFactory.decodeFile(f.path)
        if (b != null) r = android.graphics.drawable.BitmapDrawable(c.resources, b)
    }
    if (r == null) {
        try {
            r = WallpaperManager.getInstance(c).drawable
        } catch (e: Throwable) {
        }
    }
    if (r == null) {
        r = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#312E81"), Color.parseColor("#0F172A"))
        )
    }
    Wall.d = r
    Wall.stamp = st
    return r
}

fun saveWall(a: Activity, uri: Uri) = wallEditor(a, uri)

class Style(c: Context) {
    val d = c.resources.displayMetrics.density
    val gap = (Prefs.gi(c, "gap", 10) * d).toInt()
    val isz = (Prefs.gi(c, "isize", 52) * d).toInt()
    val hide = Prefs.gb(c, "hide", false)
    val single = Prefs.gb(c, "single", false)
    val lcol = Prefs.gi(c, "lcol", Color.WHITE)
    val tsize = Prefs.gi(c, "tsize", 12).toFloat()
}

class CellVH(val box: LinearLayout, val icon: ImageView, val name: TextView?) :
    RecyclerView.ViewHolder(box)

fun makeCell(c: Context, st: Style, lp: ViewGroup.LayoutParams): CellVH {
    val icon = ImageView(c).apply { layoutParams = LinearLayout.LayoutParams(st.isz, st.isz) }
    val name = if (st.hide) null else TextView(c).apply {
        setTextColor(st.lcol); textSize = st.tsize
        maxLines = if (st.single) 1 else 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    val box = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, st.gap, 0, st.gap)
        layoutParams = lp
        addView(icon)
        if (name != null) addView(name)
    }
    return CellVH(box, icon, name)
}

fun bindCell(
    h: CellVH, st: Style, item: Item, selected: Boolean,
    click: (Item) -> Unit, long: (Item) -> Unit
) {
    if (item.icon != null) {
        h.icon.background = null
        h.icon.setImageDrawable(item.icon)
    } else {
        h.icon.setImageDrawable(null)
        h.icon.background = GradientDrawable().apply {
            setColor(Color.parseColor("#99FFFFFF")); cornerRadius = 14 * st.d
        }
    }
    h.name?.text = if (selected) "✓ " + item.label else item.label
    h.box.setBackgroundColor(if (selected) Color.parseColor("#55FFFFFF") else Color.TRANSPARENT)
    h.box.setOnClickListener { click(item) }
    h.box.setOnLongClickListener { long(item); true }
}

fun cell(
    c: Context, item: Item, w: Int, click: (Item) -> Unit, long: (Item) -> Unit, selected: Boolean
): View {
    val st = Style(c)
    val h = makeCell(c, st, LinearLayout.LayoutParams(w, -2))
    bindCell(h, st, item, selected, click, long)
    return h.box
}

class AppsAdapter(
    val c: Context, val items: List<Item>, val rowH: Int, val st: Style,
    val click: (Item) -> Unit, val long: (Item) -> Unit, val isSel: (Item) -> Boolean
) : RecyclerView.Adapter<CellVH>() {
    override fun onCreateViewHolder(p: ViewGroup, t: Int): CellVH =
        makeCell(c, st, RecyclerView.LayoutParams(-1, if (rowH > 0) rowH else -2))

    override fun onBindViewHolder(h: CellVH, i: Int) {
        val it0 = items[i]
        bindCell(h, st, it0, isSel(it0), click, long)
    }

    override fun getItemCount() = items.size
}

class PageVH(val rv: RecyclerView) : RecyclerView.ViewHolder(rv)

fun pager(
    c: Context, pages: List<Page>, cols: Int, wPx: Int, topPad: Int,
    click: (Item) -> Unit, long: (Item) -> Unit, isSel: (Item) -> Boolean,
    bgLong: (() -> Unit)? = null, rows: Int = 0
): RecyclerView {
    val st = Style(c)
    val rowH = if (rows > 0) (c.resources.displayMetrics.heightPixels - topPad - 100) / rows else 0
    val rv = RecyclerView(c)
    rv.layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false)
    PagerSnapHelper().attachToRecyclerView(rv)
    val pool = RecyclerView.RecycledViewPool()
    rv.adapter = object : RecyclerView.Adapter<PageVH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): PageVH {
            val inner = RecyclerView(c)
            inner.layoutManager = GridLayoutManager(c, cols)
            inner.setRecycledViewPool(pool)
            inner.clipToPadding = false
            inner.setPadding(0, topPad, 0, 260)
            inner.layoutParams = RecyclerView.LayoutParams(wPx, RecyclerView.LayoutParams.MATCH_PARENT)
            if (bgLong != null) inner.setOnLongClickListener { bgLong(); true }
            return PageVH(inner)
        }

        override fun onBindViewHolder(h: PageVH, i: Int) {
            val its = pages[i].items
            val ad = h.rv.adapter as? AppsAdapter
            if (ad != null && ad.items === its) ad.notifyDataSetChanged()
            else h.rv.adapter = AppsAdapter(c, its, rowH, st, click, long, isSel)
        }

        override fun getItemCount() = pages.size
    }
    return rv
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var pagerRv: RecyclerView? = null
    private var drawer: View? = null
    private var drawerRv: RecyclerView? = null
    private var selBar: View? = null
    private var selCnt: TextView? = null
    private val sel = LinkedHashSet<String>()
    private var curName = "APPS"
    private var sx = 0f
    private var sy = 0f
    private var maxP = 1
    private var pinch = 1f

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Cache.clear()
            if (drawer == null) build()
        }
    }

    private val sd by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinch = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { pinch *= d.scaleFactor; return true }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                if (drawer == null) {
                    if (pinch < 0.75f) gesture("pin", 1)
                    else if (pinch > 1.3f) gesture("pout", 1)
                }
            }
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (f) goFull(this)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this)
        setContentView(root)
        Thread { launchables(this) }.start()
        try {
            registerReceiver(pkgRx, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            })
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgRx) } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        Bus.onUp = { if (drawer != null) closeDrawer() else gesture("up", 1) }
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
        if (sel.isNotEmpty()) { sel.clear(); updateSel() }
        else if (drawer != null) closeDrawer()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data
        if (req == 7 && res == RESULT_OK && u != null) saveWall(this, u)
    }

    private fun refresh() {
        val was = drawer != null
        build()
        if (was) openDrawer(curName)
    }

    private fun build() {
        drawer = null; drawerRv = null; selBar = null; selCnt = null
        sel.clear()
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
        val rv = pager(this, listOf(Page(fresh)), 4, w, 140,
            { launch(this, it.pkg!!) },
            { moveDialog(listOf(it.pkg!!), true) },
            { false },
            { homeMenu() })
        pagerRv = rv
        root.addView(rv)
        if (Prefs.gb(this, "dock", true)) {
            val n = Prefs.gi(this, "dockn", 5)
            val byPkg = all.associateBy { it.pkg }
            val items = Prefs.dock(this).mapNotNull { byPkg[it] }.take(n)
            if (items.isNotEmpty()) {
                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.parseColor("#66000000"))
                    setPadding(0, dp(6), 0, dp(18))
                    items.forEach {
                        addView(cell(this@MainActivity, it, w / n,
                            { a -> launch(this@MainActivity, a.pkg!!) },
                            { a -> moveDialog(listOf(a.pkg!!), true) }, false))
                    }
                }
                root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
        }
    }

    private fun homeMenu() {
        val names = arrayOf(
            "Wallpapers", "New folder", "Manage apps", "App drawer",
            "Launcher settings", "System settings"
        )
        AlertDialog.Builder(this).setItems(names) { _, w ->
            when (w) {
                0 -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 7)
                1 -> newFolder()
                2 -> startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                3 -> openDrawer()
                4 -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }.show()
    }

    private fun toggle(i: Item) {
        val p = i.pkg!!
        if (!sel.add(p)) sel.remove(p)
        updateSel()
    }

    private fun updateSel() {
        drawerRv?.adapter?.notifyDataSetChanged()
        selBar?.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE
        selCnt?.text = sel.size.toString() + " selected"
    }

    private fun openDrawer(startAt: String? = null) {
        if (drawer != null) return
        val all = launchables(this)
        val byPkg = all.associateBy { it.pkg }
        val folders = Prefs.folders(this)
        val inF = folders.values.flatten().toSet()
        val names = listOf("APPS") + folders.keys
        val pages = names.map { n ->
            Page(if (n == "APPS") all.filter { it.pkg !in inF } else folders[n]!!.mapNotNull { byPkg[it] })
        }
        val w = resources.displayMetrics.widthPixels
        val dc = Prefs.gi(this, "dcols", 5)
        val dr = Prefs.gi(this, "drows", 0)
        val rv = pager(this, pages, dc, w, dp(86),
            { if (sel.isNotEmpty()) toggle(it) else launch(this, it.pkg!!) },
            { toggle(it) },
            { sel.contains(it.pkg!!) },
            rows = dr)
        drawerRv = rv

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
                text = (if (n == "APPS") Prefs.sp(this@MainActivity).getString("appsname", "APPS")!! else n).uppercase()
                textSize = 15f
                setPadding(dp(20), dp(12), dp(20), dp(8))
                setOnClickListener { rv.smoothScrollToPosition(i) }
                setOnLongClickListener { renameFolder(n); true }
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

        val cnt = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        val selView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2222233"))
            setPadding(dp(16), dp(10), dp(16), dp(28))
            visibility = View.GONE
            addView(cnt, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Move"
                setOnClickListener { moveDialog(sel.toList(), false) }
            })
            addView(Button(this@MainActivity).apply {
                text = "Cancel"
                setOnClickListener { sel.clear(); updateSel() }
            })
        }
        selBar = selView; selCnt = cnt

        val alpha = Prefs.gi(this, "dalpha", 230)
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(alpha, 16, 16, 24))
            addView(rv)
            addView(top, FrameLayout.LayoutParams(-1, -2))
            addView(selView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        drawer = frame
        root.addView(frame)
    }

    private fun closeDrawer() {
        sel.clear()
        drawer?.let { root.removeView(it) }
        drawer = null; drawerRv = null; selBar = null; selCnt = null
    }

    private fun gesture(dir: String, f: Int) {
        val pkg = Prefs.sp(this).getString("g_$dir$f", null)
        if (pkg != null) launch(this, pkg)
        else if (dir == "up" && f == 1) openDrawer()
    }

    private fun askFolder(ok: (String) -> Unit) {
        val et = EditText(this)
        AlertDialog.Builder(this).setTitle("Folder name").setView(et)
            .setPositiveButton("OK") { _, _ ->
