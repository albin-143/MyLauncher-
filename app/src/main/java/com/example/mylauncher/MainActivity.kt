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
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && n != "APPS") ok(n)
            }.show()
    }

    private fun newFolder() {
        askFolder { n ->
            val m = Prefs.folders(this)
            if (!m.containsKey(n)) {
                m[n] = mutableListOf(); Prefs.save(this, m); curName = n; refresh()
            }
        }
    }

    private fun renameFolder(name: String) {
        if (name == "APPS") {
            val cur = Prefs.sp(this).getString("appsname", "APPS")
            val e2 = EditText(this).apply { setText(cur) }
            AlertDialog.Builder(this).setTitle("Rename").setView(e2)
                .setPositiveButton("OK") { _, _ ->
                    val t = e2.text.toString().trim()
                    if (t.isNotEmpty()) {
                        Prefs.sp(this).edit().putString("appsname", t).apply(); refresh()
                    }
                }.show()
            return
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

    private fun moveDialog(pkgs: List<String>, home: Boolean) {
        val names = Prefs.folders(this).keys.toList()
        val acts = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, f: () -> Unit) { acts.add(Pair(t, f)) }
        add("New folder…") { askFolder { n -> doMove(pkgs, n) } }
        names.forEach { n -> add("→ $n") { doMove(pkgs, n) } }
        add(if (home) "Remove from Home" else "Move to APPS (no folder)") { doMove(pkgs, null) }
        if (Prefs.gb(this, "dock", true)) {
            add("Add to Dock") {
                val dk = Prefs.dock(this).toMutableList()
                pkgs.forEach { p -> if (p !in dk) dk.add(p) }
                Prefs.setDock(this, dk.take(Prefs.gi(this, "dockn", 5)))
                sel.clear(); refresh()
            }
            add("Remove from Dock") {
                Prefs.setDock(this, Prefs.dock(this).filter { p -> p !in pkgs })
                sel.clear(); refresh()
            }
        }
        AlertDialog.Builder(this).setTitle("Selected: " + pkgs.size)
            .setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
            .show()
    }

    private fun doMove(pkgs: List<String>, name: String?) {
        pkgs.forEach { Prefs.move(this, it, name); if (name == null) Prefs.markSeen(this, it) }
        sel.clear()
        refresh()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
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
                        else if (dy < 0 && sy > dm.heightPixels * 0.85) closeDrawer()
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
    private lateinit var col: LinearLayout
    private val gest = listOf(
        "up1" to "1 finger: swipe up from bottom",
        "down1" to "1 finger: swipe down from top",
        "up2" to "2 fingers: swipe up from bottom",
        "down2" to "2 fingers: swipe down from top",
        "pin1" to "Pinch in (two fingers close)",
        "pout1" to "Pinch out (two fingers apart)"
    )

    override fun onResume() { super.onResume(); show() }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data
        if (req == 7 && res == RESULT_OK && u != null) saveWall(this, u)
    }

    private fun sp() = Prefs.sp(this)

    private fun open(p: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", p))
    }

    private fun head(t: String) {
        col.addView(TextView(this).apply {
            text = t; textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF")); setPadding(48, 40, 48, 8)
        })
    }

    private fun row(t: String, s: String?, f: () -> Unit) {
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 28)
            addView(TextView(this@SettingsActivity).apply { text = t; textSize = 18f })
            if (s != null) addView(TextView(this@SettingsActivity).apply {
                text = s; textSize = 14f; setTextColor(Color.parseColor("#9CA3AF"))
            })
            setOnClickListener { f() }
        })
    }

    private fun check(t: String, k: String, d: Boolean) {
        col.addView(CheckBox(this).apply {
            text = t; textSize = 18f; setPadding(48, 28, 48, 28)
            isChecked = sp().getBoolean(k, d)
            setOnCheckedChangeListener { _, v -> sp().edit().putBoolean(k, v).apply() }
        })
    }

    private fun choice(t: String, k: String, names: Array<String>, vals: IntArray, d: Int) {
        val i = vals.indexOf(sp().getInt(k, d)).coerceAtLeast(0)
        row(t, names[i]) {
            AlertDialog.Builder(this).setTitle(t).setItems(names) { _, w ->
                sp().edit().putInt(k, vals[w]).apply(); show()
            }.show()
        }
    }

    private fun pickApp(label: String, key: String, apps: List<Item>) {
        val none = if (key == "up1") "App drawer (default)" else "None"
        val cur = sp().getString("g_$key", null)
        row(label, "→ " + (apps.firstOrNull { it.pkg == cur }?.label ?: none)) {
            AlertDialog.Builder(this).setTitle(label)
                .setItems((listOf(none) + apps.map { it.label }).toTypedArray()) { _, w ->
                    val ed = sp().edit()
                    if (w == 0) ed.remove("g_$key") else ed.putString("g_$key", apps[w - 1].pkg)
                    ed.apply(); show()
                }.show()
        }
    }

    private fun show() {
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 48, 0, 64) }
        when (intent.getStringExtra("page")) {
            "general" -> {
                head("GENERAL")
                row("Set default launcher", "Choose My Launcher as Home") {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }
                row("Choose wallpaper", null) {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 7)
                }
                row("Use system wallpaper", null) {
                    java.io.File(filesDir, "wall.jpg").delete()
                    Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show()
                }
                row("System settings", null) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            "drawer" -> {
                head("LAYOUT")
                choice("Drawer columns", "dcols",
                    arrayOf("3", "4", "5", "6", "7", "8"), intArrayOf(3, 4, 5, 6, 7, 8), 5)
                choice("Drawer rows", "drows",
                    arrayOf("Auto (scroll)", "3", "4", "5", "6", "7", "8", "9", "10"),
                    intArrayOf(0, 3, 4, 5, 6, 7, 8, 9, 10), 0)
                head("OTHER")
                choice("Background transparency", "dalpha",
                    arrayOf("Solid", "Slight", "Medium", "High"), intArrayOf(255, 230, 179, 128), 230)
            }
            "dock" -> {
                head("DOCK")
                check("Enable dock", "dock", true)
                choice("Dock icons", "dockn", arrayOf("4", "5", "6", "7"), intArrayOf(4, 5, 6, 7), 5)
                row("Clear dock", "Remove all apps from the dock") {
                    sp().edit().remove("dockapps").apply()
                    Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show()
                }
            }
            "look" -> {
                head("ICONS")
                choice("Icon size", "isize",
                    arrayOf("Small", "Medium", "Large", "Extra large"), intArrayOf(40, 52, 64, 76), 52)
                choice("App spacing", "gap",
                    arrayOf("None", "Small", "Medium", "Large", "Extra large"),
                    intArrayOf(0, 4, 10, 20, 30), 10)
                head("LABELS")
                check("Hide icon labels", "hide", false)
                check("Labels in single line", "single", false)
                choice("Text size", "tsize", arrayOf("Small", "Medium", "Large"), intArrayOf(10, 12, 15), 12)
                choice("Icon label color", "lcol",
                    arrayOf("White", "Black", "Yellow", "Cyan"),
                    intArrayOf(Color.WHITE, Color.BLACK, Color.YELLOW, Color.CYAN), Color.WHITE)
            }
            "gest" -> {
                head("EDGE GESTURES")
                row("Enable edge gestures",
                    "Left/right edge = Back, bottom up = Home, bottom up + hold = Recents") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                head("HOME GESTURES")
                val apps = launchables(this)
                gest.forEach { (k, l) -> pickApp(l, k, apps) }
            }
            else -> {
                head("LAUNCHER SETTINGS")
                row("General Settings", null) { open("general") }
                row("Drawer Settings", null) { open("drawer") }
                row("Dock Settings", null) { open("dock") }
                row("Appearance Settings", null) { open("look") }
                row("Gestures & Keys", null) { open("gest") }
            }
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
        var ax = 0f
        var ay = 0f
        var done = false
        var armed = false
        val hold = Runnable { done = true; performGlobalAction(GLOBAL_ACTION_RECENTS) }
        val v = View(this)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; done = false; armed = false
                    if (kind == 2) h.postDelayed(hold, 600)
                }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    val dx = e.rawX - x0
                    val dy = e.rawY - y0
                    when (kind) {
                        0 -> if (dx > 60 * d) { done = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                        1 -> if (-dx > 60 * d) { done = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                        else -> {
                            if (!armed) {
                                if (Math.abs(dy) > 20 * d) h.removeCallbacks(hold)
                                if (-dy > 80 * d) {
                                    armed = true; ax = e.rawX; ay = e.rawY
                                    h.postDelayed(hold, 450)
                                }
                            } else if (Math.abs(e.rawX - ax) > 12 * d || Math.abs(e.rawY - ay) > 12 * d) {
                                ax = e.rawX; ay = e.rawY
                                h.removeCallbacks(hold); h.postDelayed(hold, 450)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(hold)
                    if (kind == 2 && armed && !done) {
                        done = true
                        val f = Bus.onUp
                        if (f != null) f() else performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
                MotionEvent.ACTION_CANCEL -> h.removeCallbacks(hold)
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
