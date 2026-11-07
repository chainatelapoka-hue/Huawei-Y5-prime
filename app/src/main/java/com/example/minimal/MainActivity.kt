package com.example.minimal

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AlphaAnimation
import android.view.animation.LayoutAnimationController
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

private data class AppItem(val label: String, val pkg: String)

private sealed class Row {
    data class AppRow(val item: AppItem) : Row()
    data class FolderRow(val group: Group) : Row()
}

class MainActivity : Activity() {

    private val allApps = mutableListOf<AppItem>()
    private var rows: List<Row> = emptyList()
    private var currentTab = "All"
    private var query = ""
    private var lastBadges: Map<String, Int> = emptyMap()
    private var drawerOpen = false

    private val gestureKeys = listOf("up", "down", "double", "pinch_in")
    private val gestureLabels = mapOf(
        "up" to "Swipe up", "down" to "Swipe down",
        "double" to "Double tap", "pinch_in" to "Pinch in"
    )

    // home
    private lateinit var rootFrame: FrameLayout
    private lateinit var homePage: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var dateView: TextView
    private lateinit var batteryView: TextView
    private lateinit var gridBox: LinearLayout
    private lateinit var dockBox: LinearLayout

    // drawer
    private lateinit var drawerPage: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var sortButton: TextView
    private lateinit var list: ListView
    private lateinit var appAdapter: ArrayAdapter<String>

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateInfo()
            if (Badges.counts != lastBadges) {
                lastBadges = Badges.counts
                applyFilter(query)
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        buildHome()
        buildDrawer()

        rootFrame = FrameLayout(this).apply {
            addView(homePage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            addView(drawerPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
        drawerPage.visibility = View.GONE
        setContentView(rootFrame)

        attachHomeGestures()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        loadWallpaper()
        sortButton.text = if (Store.usageSort()) "most used" else "A-Z"
        lastBadges = Badges.counts
        refreshHome()
        applyFilter(query)
        updateInfo()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onBackPressed() {
        if (drawerOpen) showHome()
    }

    // ================= HOME =================

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun pill(color: String, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun buildHome() {
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

        val overlay = View(this).apply { setBackgroundColor(Color.parseColor("#59000000")) }

        dateView = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
        }
        batteryView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
        }

        gridBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        dockBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill("#CC141C26", 36)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val appsButton = TextView(this).apply {
            text = "Apps"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pill("#CC141C26", 24)
            setOnClickListener { showDrawer(false) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(20))
            addView(dateView)
            addView(batteryView)
            addView(gridBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(24) })
            addView(dockBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(appsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
        }

        homePage = FrameLayout(this).apply {
            addView(wallpaperView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            setBackgroundColor(Color.BLACK)
        }
    }

    private fun loadWallpaper() {
        try {
            wallpaperView.setImageDrawable(WallpaperManager.getInstance(this).drawable)
        } catch (e: Exception) {
            wallpaperView.setImageDrawable(null)
        }
    }

    private fun updateInfo() {
        dateView.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(java.util.Date())
        batteryView.text = batteryText()
    }

    private fun batteryText(): String {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return ""
        return "Battery ${level * 100 / scale}%"
    }

    private fun iconTile(pkg: String, onTap: () -> Unit): ImageView {
        val tile = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = pill("#B3141C26", 22)
        }
        try {
            val icon = packageManager.getApplicationIcon(pkg)
            icon.setColorFilter(PorterDuffColorFilter(Color.parseColor("#9CCBF2"), PorterDuff.Mode.SRC_IN))
            tile.setImageDrawable(icon)
        } catch (e: Exception) {
            tile.setImageDrawable(null)
        }
        val swipe = SwipeTracker { dx, dy ->
            if (dy < -150 && abs(dy) > abs(dx)) Store.swipeApp(pkg)?.let { launch(it) }
        }
        tile.setOnClickListener { onTap() }
        tile.setOnLongClickListener { showAppMenu(pkg); true }
        tile.setOnTouchListener { _, e -> swipe.onEvent(e); false }
        return tile
    }

    private fun refreshHome() {
        gridBox.removeAllViews()
        val pinned = Store.grid().filter { p -> allApps.any { it.pkg == p } }
        for (rowPkgs in pinned.chunked(3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            for (pkg in rowPkgs) {
                row.addView(iconTile(pkg) { launch(pkg) }, LinearLayout.LayoutParams(dp(78), dp(78))
                    .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            gridBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dockBox.removeAllViews()
        val docked = Store.favorites().filter { p -> allApps.any { it.pkg == p } }
        for (pkg in docked) {
            dockBox.addView(iconTile(pkg) { launch(pkg) },
                LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
    }

    private fun toggleGrid(pkg: String) {
        val g = Store.grid()
        if (g.contains(pkg)) {
            g.remove(pkg)
        } else if (g.size >= Store.MAX_GRID) {
            toast("Home-এ সর্বোচ্চ ${Store.MAX_GRID} টি অ্যাপ")
            return
        } else {
            g.add(pkg)
        }
        Store.saveGrid(g)
        refreshHome()
    }

    private fun toggleDock(pkg: String) {
        val d = Store.favorites()
        if (d.contains(pkg)) {
            d.remove(pkg)
        } else if (d.size >= Store.MAX_DOCK) {
            toast("Dock-এ সর্বোচ্চ ${Store.MAX_DOCK} টি অ্যাপ")
            return
        } else {
            d.add(pkg)
        }
        Store.saveFavorites(d)
        refreshHome()
    }

    private fun showHome() {
        drawerOpen = false
        drawerPage.visibility = View.GONE
        homePage.visibility = View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
    }

    private fun showDrawer(focusSearch: Boolean) {
        drawerOpen = true
        homePage.visibility = View.GONE
        drawerPage.visibility = View.VISIBLE
        if (focusSearch) {
            search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
        }
    }

    // ================= DRAWER =================

    private fun buildDrawer() {
        val back = TextView(this).apply {
            text = "‹ Home"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), dp(16), dp(8))
            setOnClickListener { showHome() }
        }

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }

        search = EditText(this).apply {
            hint = "search"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                rows.firstOrNull { it is Row.AppRow }?.let { launch((it as Row.AppRow).item.pkg) }
                true
            }
        }

        sortButton = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(24, 16, 0, 16)
            setOnClickListener { toggleSort() }
        }

        appAdapter = object : ArrayAdapter<String>(
            this@MainActivity,
            android.R.layout.simple_list_item_1,
            ArrayList<String>()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#D0D0D0"))
                tv.textSize = 20f
                tv.setPadding(0, 28, 0, 28)
                return tv
            }
        }

        list = ListView(this).apply {
            divider = null
            setBackgroundColor(Color.BLACK)
            adapter = appAdapter
            setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onRowLong(rows[pos]); true }
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sortButton)
        }

        drawerPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(56), dp(20), dp(20))
            addView(back)
            addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(12) })
        }
    }

    // ================= DATA =================

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = mutableListOf<AppItem>()
        for (ri in packageManager.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            found.add(AppItem(ri.loadLabel(packageManager).toString(), pkg))
        }
        allApps.clear()
        allApps.addAll(found.distinctBy { it.pkg }.sortedBy { it.label.lowercase(Locale.getDefault()) })
    }

    private fun buildRows(): List<Row> {
        val hiddenSet = Store.hidden()
        val groups = Store.groups()
        val folders = groups.filter { it.folder }
        val folderPkgs = folders.flatMap { it.pkgs }.toSet()
        val tab = groups.firstOrNull { !it.folder && it.name == currentTab }
        val visible = allApps.filter { it.pkg !in hiddenSet }

        var apps: List<AppItem> = if (tab != null) {
            visible.filter { it.pkg in tab.pkgs }
        } else {
            visible.filter { it.pkg !in folderPkgs }
        }
        apps = apps.filter { it.label.contains(query, ignoreCase = true) }
        if (Store.usageSort()) {
            apps = apps.sortedWith(
                compareByDescending<AppItem> { Store.count(it.pkg) }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
        }

        val out = mutableListOf<Row>()
        if (tab == null && query.isEmpty()) {
            folders.forEach { out.add(Row.FolderRow(it)) }
        }
        apps.forEach { out.add(Row.AppRow(it)) }
        return out
    }

    private fun applyFilter(q: String) {
        query = q
        refreshTabs()
        rows = buildRows()
        appAdapter.clear()
        appAdapter.addAll(rows.map { labelFor(it) })
        appAdapter.notifyDataSetChanged()
        applyAnimation()
    }

    private fun labelFor(row: Row): String = when (row) {
        is Row.FolderRow -> "▸ ${row.group.name} (${row.group.pkgs.size})"
        is Row.AppRow -> {
            val n = Badges.counts[row.item.pkg] ?: 0
            if (n > 0) "${row.item.label}  •$n" else row.item.label
        }
    }

    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        Store.bumpCount(pkg)
        startActivity(intent)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun refreshTabs() {
        tabRow.removeAllViews()
        val names = mutableListOf("All") + Store.groups().filter { !it.folder }.map { it.name }
        for (t in names) {
            tabRow.addView(TextView(this).apply {
                text = t
                textSize = 14f
                setPadding(24, 12, 24, 12)
                setTextColor(if (t == currentTab) Color.WHITE else Color.GRAY)
                setOnClickListener {
                    currentTab = t
                    applyFilter(query)
                }
            })
        }
    }

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.AppRow -> launch(row.item.pkg)
            is Row.FolderRow -> showFolder(row.group)
        }
    }

    private fun onRowLong(row: Row) {
        when (row) {
            is Row.AppRow -> showAppMenu(row.item.pkg)
            is Row.FolderRow -> confirmDeleteFolder(row.group)
        }
    }

    private fun showFolder(g: Group) {
        val apps = allApps.filter { it.pkg in g.pkgs }
        if (apps.isEmpty()) {
            toast("ফোল্ডার খালি")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(apps.map { it.label }.toTypedArray()) { _, w -> launch(apps[w].pkg) }
            .show()
    }

    private fun confirmDeleteFolder(g: Group) {
        AlertDialog.Builder(this)
            .setTitle("Delete folder '${g.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                Store.saveGroups(Store.groups().filter { it.name != g.name })
                applyFilter(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= APP MENU (shared by home, dock, drawer) =================

    private fun showAppMenu(pkg: String) {
        val label = allApps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        val onGrid = Store.grid().contains(pkg)
        val onDock = Store.favorites().contains(pkg)
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf(
                if (onGrid) "Remove from home" else "Add to home",
                if (onDock) "Remove from dock" else "Add to dock",
                "Set swipe-up app",
                "Clear swipe-up app",
                "Hide app",
                "App info",
                "Uninstall"
            )) { _, w ->
                when (w) {
                    0 -> toggleGrid(pkg)
                    1 -> toggleDock(pkg)
                    2 -> pickApp { target ->
                        Store.setSwipeApp(pkg, target)
                        toast("Swipe-up সেট হয়েছে")
                    }
                    3 -> Store.setSwipeApp(pkg, null)
                    4 -> hideApp(pkg)
                    5 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    )
                    6 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }
            .show()
    }

    private fun hideApp(pkg: String) {
        val set = Store.hidden()
        set.add(pkg)
        Store.saveHidden(set)
        Store.saveFavorites(Store.favorites().filter { it != pkg })
        Store.saveGrid(Store.grid().filter { it != pkg })
        refreshHome()
        applyFilter(query)
    }

    private fun pickApp(onPicked: (String) -> Unit) {
        val apps = allApps.toList()
        AlertDialog.Builder(this)
            .setTitle("Choose app")
            .setItems(apps.map { it.label }.toTypedArray()) { _, w -> onPicked(apps[w].pkg) }
            .show()
    }

    // ================= SETTINGS MENU =================

    private fun showMainMenu() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf(
                "Gestures",
                "Hide apps",
                "Tabs & folders",
                "Scroll animation",
                "Notification badges (grant access)"
            )) { _, w ->
                when (w) {
                    0 -> showGestureSettings()
                    1 -> showHideApps()
                    2 -> showGroupManager()
                    3 -> showAnimationPicker()
                    4 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            .show()
    }

    private fun showHideApps() {
        val apps = allApps.toList()
        val hiddenNow = Store.hidden()
        val checked = BooleanArray(apps.size) { apps[it].pkg in hiddenNow }
        AlertDialog.Builder(this)
            .setTitle("Hide apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, w, c ->
                checked[w] = c
            }
            .setPositiveButton("Save") { _, _ ->
                val set = mutableSetOf<String>()
                for (i in apps.indices) if (checked[i]) set.add(apps[i].pkg)
                Store.saveHidden(set)
                Store.saveFavorites(Store.favorites().filter { it !in set })
                Store.saveGrid(Store.grid().filter { it !in set })
                refreshHome()
                applyFilter(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupManager() {
        AlertDialog.Builder(this)
            .setTitle("Tabs & folders")
            .setItems(arrayOf("Add tab", "Add folder", "Delete a tab or folder")) { _, w ->
                when (w) {
                    0 -> createGroup(false)
                    1 -> createGroup(true)
                    2 -> deleteGroup()
                }
            }
            .show()
    }

    private fun createGroup(folder: Boolean) {
        val input = EditText(this).apply { hint = "name" }
        AlertDialog.Builder(this)
            .setTitle(if (folder) "Folder name" else "Tab name")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val name = input.text.toString().replace('|', ' ').replace(',', ' ')
                    .replace('\n', ' ').trim()
                if (name.isNotEmpty()) selectAppsFor(name, folder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectAppsFor(name: String, folder: Boolean) {
        val apps = allApps.toList()
        val checked = BooleanArray(apps.size)
        AlertDialog.Builder(this)
            .setTitle("Select apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, w, c ->
                checked[w] = c
            }
            .setPositiveButton("Save") { _, _ ->
                val pkgs = apps.indices.filter { checked[it] }.map { apps[it].pkg }.toMutableList()
                val groups = Store.groups()
                groups.removeAll { it.name == name }
                groups.add(Group(name, folder, pkgs))
                Store.saveGroups(groups)
                applyFilter(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGroup() {
        val groups = Store.groups()
        if (groups.isEmpty()) {
            toast("কোনো tab বা folder নেই")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setItems(groups.map { it.name }.toTypedArray()) { _, w ->
                val gone = groups[w].name
                Store.saveGroups(groups.filter { it.name != gone })
                if (currentTab == gone) currentTab = "All"
                applyFilter(query)
            }
            .show()
    }

    private fun showAnimationPicker() {
        AlertDialog.Builder(this)
            .setTitle("Scroll animation")
            .setItems(arrayOf("None", "Fade", "Slide up", "Zoom")) { _, w ->
                Store.setAnimation(w)
                applyFilter(query)
            }
            .show()
    }

    private fun applyAnimation() {
        val anim: Animation? = when (Store.animation()) {
            1 -> AlphaAnimation(0f, 1f)
            2 -> TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0f
            )
            3 -> ScaleAnimation(
                0.8f, 1f, 0.8f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f
            )
            else -> null
        }
        if (anim == null) {
            list.layoutAnimation = null
            return
        }
        anim.duration = 300
        list.layoutAnimation = LayoutAnimationController(anim, 0.05f)
        list.startLayoutAnimation()
    }

    private fun toggleSort() {
        Store.setUsageSort(!Store.usageSort())
        sortButton.text = if (Store.usageSort()) "most used" else "A-Z"
        applyFilter(query)
    }

    // ================= GESTURES =================

    private fun attachHomeGestures() {
        val swipe = SwipeTracker { dx, dy ->
            if (abs(dy) >= 150 && abs(dy) > abs(dx)) runGesture(if (dy < 0) "up" else "down")
        }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                runGesture("double")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showMainMenu()
            }
        })
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var fired = false
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                fired = false
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (!fired && d.scaleFactor < 0.9f) {
                    fired = true
                    runGesture("pinch_in")
                }
                return true
            }
        })
        homePage.setOnTouchListener { _, e ->
            swipe.onEvent(e)
            sd.onTouchEvent(e)
            gd.onTouchEvent(e)
            true
        }
    }

    private fun defaultAction(key: String): String = when (key) {
        "up" -> "drawer"
        "down" -> "notif"
        else -> "none"
    }

    private fun runGesture(key: String) {
        runAction(Store.gesture(key, defaultAction(key)))
    }

    private fun runAction(action: String) {
        when {
            action == "drawer" -> showDrawer(true)
            action == "notif" -> expandNotifications()
            action == "lock" -> lockScreen()
            action.startsWith("app:") -> launch(action.removePrefix("app:"))
        }
    }

    private fun expandNotifications() {
        try {
            val svc = getSystemService("statusbar") ?: return
            svc.javaClass.getMethod("expandNotificationsPanel").invoke(svc)
        } catch (e: Exception) {
            toast("Notification panel খোলা যায়নি")
        }
    }

    private fun lockScreen() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "ডাবল-ট্যাপে স্ক্রিন লক করতে")
            )
        }
    }

    private fun showGestureSettings() {
        val labels = gestureKeys.map {
            "${gestureLabels[it]}: ${actionLabel(Store.gesture(it, defaultAction(it)))}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Gestures")
            .setItems(labels) { _, w ->
                val key = gestureKeys[w]
                pickAction(gestureLabels[key] ?: key) { Store.setGesture(key, it) }
            }
            .show()
    }

    private fun pickAction(title: String, onPick: (String) -> Unit) {
        val names = arrayOf("Nothing", "Focus search", "Notifications", "Lock screen", "Open an app…")
        val values = arrayOf("none", "drawer", "notif", "lock", "app")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, w ->
                if (values[w] == "app") {
                    pickApp { pkg -> onPick("app:$pkg") }
                } else {
                    onPick(values[w])
                }
            }
            .show()
    }

    private fun actionLabel(a: String): String = when {
        a == "drawer" -> "Focus search"
        a == "notif" -> "Notifications"
        a == "lock" -> "Lock screen"
        a.startsWith("app:") -> allApps.firstOrNull { it.pkg == a.removePrefix("app:") }?.label ?: "App"
        else -> "Nothing"
    }
}

private class SwipeTracker(private val onSwipe: (dx: Float, dy: Float) -> Unit) {
    private var x0 = 0f
    private var y0 = 0f

    fun onEvent(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x
                y0 = e.y
            }
            MotionEvent.ACTION_UP -> onSwipe(e.x - x0, e.y - y0)
        }
    }
}
