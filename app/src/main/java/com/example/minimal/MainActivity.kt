package com.example.minimal

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
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

    private val gestureKeys = listOf("up", "down", "double", "pinch_in")
    private val gestureLabels = mapOf(
        "up" to "Swipe up", "down" to "Swipe down",
        "double" to "Double tap", "pinch_in" to "Pinch in"
    )

    private lateinit var root: LinearLayout
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var sortButton: TextView
    private lateinit var dock: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var appAdapter: ArrayAdapter<String>

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
            dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            if (Badges.counts != lastBadges) {
                lastBadges = Badges.counts
                refreshAll()
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        clock = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
        }
        dateView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(56, 140, 56, 56)
            addView(clock)
            addView(dateView)
            addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 })
            addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
            addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = 16 })
        }
        setContentView(root)

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
        sortButton.text = if (Store.usageSort()) "most used" else "A-Z"
        lastBadges = Badges.counts
        refreshAll()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onBackPressed() {}

    // ---------- data ----------

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

    private fun refreshAll() {
        refreshDock()
        applyFilter(query)
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

    // ---------- tabs ----------

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

    // ---------- rows ----------

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.AppRow -> launch(row.item.pkg)
            is Row.FolderRow -> showFolder(row.group)
        }
    }

    private fun onRowLong(row: Row) {
        when (row) {
            is Row.AppRow -> showAppMenu(row.item)
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
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- dock ----------

    private fun togglePin(pkg: String) {
        val pins = Store.favorites()
        if (pins.contains(pkg)) {
            pins.remove(pkg)
        } else if (pins.size >= Store.MAX_DOCK) {
            toast("Dock-এ সর্বোচ্চ ${Store.MAX_DOCK} টি অ্যাপ")
            return
        } else {
            pins.add(pkg)
        }
        Store.saveFavorites(pins)
        refreshDock()
    }

    private fun refreshDock() {
        dock.removeAllViews()
        for (pkg in Store.favorites().filter { p -> allApps.any { it.pkg == p } }) {
            val app = allApps.first { it.pkg == pkg }
            val n = Badges.counts[pkg] ?: 0
            val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent?, vx: Float, vy: Float): Boolean {
                    if (e1 == null || e2 == null) return false
                    if (e2.y - e1.y < -150) Store.swipeApp(pkg)?.let { launch(it) }
                    return false
                }
            })
            dock.addView(TextView(this).apply {
                text = if (n > 0) "${app.label.take(9)} •$n" else app.label.take(10)
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(8, 16, 8, 16)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { launch(pkg) }
                setOnLongClickListener { showDockMenu(pkg); true }
                setOnTouchListener { _, e -> swipe.onTouchEvent(e); false }
            })
        }
    }

    private fun showDockMenu(pkg: String) {
        val label = allApps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf("Unpin from dock", "Set swipe-up app", "Clear swipe-up app")) { _, w ->
                when (w) {
                    0 -> togglePin(pkg)
                    1 -> pickApp { target ->
                        Store.setSwipeApp(pkg, target)
                        toast("Swipe-up সেট হয়েছে")
                    }
                    2 -> Store.setSwipeApp(pkg, null)
                }
            }
            .show()
    }

    // ---------- app menu ----------

    private fun showAppMenu(app: AppItem) {
        val pinned = Store.favorites().contains(app.pkg)
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf(
                if (pinned) "Unpin from dock" else "Pin to dock",
                "Hide app",
                "App info",
                "Uninstall"
            )) { _, w ->
                when (w) {
                    0 -> togglePin(app.pkg)
                    1 -> hideApp(app.pkg)
                    2 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    )
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            }
            .show()
    }

    private fun hideApp(pkg: String) {
        val set = Store.hidden()
        set.add(pkg)
        Store.saveHidden(set)
        Store.saveFavorites(Store.favorites().filter { it != pkg })
        refreshAll()
    }

    private fun pickApp(onPicked: (String) -> Unit) {
        val apps = allApps.toList()
        AlertDialog.Builder(this)
            .setTitle("Choose app")
            .setItems(apps.map { it.label }.toTypedArray()) { _, w -> onPicked(apps[w].pkg) }
            .show()
    }

    // ---------- main settings menu (long-press empty home) ----------

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

    // ---------- hide apps ----------

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
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- tabs & folders ----------

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
                refreshAll()
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
                refreshAll()
            }
            .show()
    }

    // ---------- scroll animation ----------

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

    // ---------- sorting ----------

    private fun toggleSort() {
        Store.setUsageSort(!Store.usageSort())
        sortButton.text = if (Store.usageSort()) "most used" else "A-Z"
        applyFilter(query)
    }

    // ---------- home gestures ----------

    private fun attachHomeGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, vx: Float, vy: Float): Boolean {
                if (e1 == null || e2 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < 150 || abs(dy) < abs(dx)) return false
                runGesture(if (dy < 0) "up" else "down")
                return true
            }

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
        root.setOnTouchListener { _, e ->
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
            action == "drawer" -> {
                search.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
            }
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
