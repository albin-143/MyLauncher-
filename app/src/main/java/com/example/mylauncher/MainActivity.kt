package com.example.mylauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val label: String, val pkg: String, val icon: Drawable)

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .sortedBy { it.label.lowercase() }

        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 4)
            clipToPadding = false
            setPadding(16, 96, 16, 96)
            adapter = AppAdapter(apps) { app ->
                pm.getLaunchIntentForPackage(app.pkg)?.let { startActivity(it) }
            }
        }
        setContentView(grid)
    }

    override fun onBackPressed() { }
}

class AppAdapter(
    private val apps: List<AppInfo>,
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(val root: LinearLayout, val icon: ImageView, val name: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
        }
        val name = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(icon)
            addView(name)
        }
        return VH(root, icon, name)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val app = apps[position]
        h.icon.setImageDrawable(app.icon)
        h.name.text = app.label
        h.root.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = apps.size
}
