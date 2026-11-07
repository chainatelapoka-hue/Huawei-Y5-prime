package com.example.minimal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private data class AppItem(val label: String, val pkg: String)

    private val maxDock = 5
    private val allApps = mutableListOf<AppItem>()
    private var shownApps: List<AppItem> = emptyList()
    private var query = ""

    private lateinit var prefs: SharedPreferences
    private lateinit var appAdapter: ArrayAdapter<String>
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var sortButton: TextView
    private lateinit var dock: LinearLayout
    private lateinit var search: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
            dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        clock = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
            setOnLongClickListener { showHiddenDialog(); true }
        }

        dateView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
        }

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
                shownApps.firstOrNull()?.let { launch(it.pkg) }
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

        val list = ListView(this).apply {
            divider = null
            setBackgroundColor(Color.BLACK)
            adapter = appAdapter
            setOnItemClickListener { _, _, pos, _ -> launch(shownApps[pos].pkg) }
            setOnItemLongClickListener { _, _, pos, _ ->
                showAppMenu(shownApps[pos])
                true
            }
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sortButton)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(56, 140, 56, 56)
            addView(clock)
            addView(dateView)
            addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 })
            addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = 24 })
        }
        setContentView(root)

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
        sortButton.text = if (usageSort()) "most used" else "A-Z"
        refreshDock()
        applyFilter(query)
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    // Home screen-এ Back চাপলে কিছু হবে না
    override fun onBackPressed() {}

    // ---------- data ----------

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps.clear()
        for (ri in packageManager.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            allApps.add(AppItem(ri.loadLabel(packageManager).toString(), pkg))
        }
        val unique = allApps.distinctBy { it.pkg }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        allApps.clear()
        allApps.addAll(unique)
    }

    private fun applyFilter(q: String) {
        query = q
        val hiddenSet = hidden()
        var list: List<AppItem> = allApps.filter {
            it.pkg !in hiddenSet && it.label.contains(q, ignoreCase = true)
        }
        if (usageSort()) {
            list = list.sortedWith(
                compareByDescending<AppItem> { prefs.getInt("count_${it.pkg}", 0) }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
        }
        shownApps = list
        appAdapter.clear()
        appAdapter.addAll(list.map { it.label })
        appAdapter.notifyDataSetChanged()
    }

    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        prefs.edit().putInt("count_$pkg", prefs.getInt("count_$pkg", 0) + 1).apply()
        startActivity(intent)
    }

    // ---------- favorites (dock) ----------

    private fun favorites(): MutableList<String> =
        prefs.getString("favorites", "").orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()

    private fun saveFavorites(list: List<String>) {
        prefs.edit().putString("favorites", list.joinToString(",")).apply()
    }

    private fun togglePin(pkg: String) {
        val list = favorites()
        if (list.contains(pkg)) {
            list.remove(pkg)
        } else {
            if (list.size >= maxDock) {
                Toast.makeText(this, "Dock-এ সর্বোচ্চ $maxDock টি অ্যাপ", Toast.LENGTH_SHORT).show()
                return
            }
            list.add(pkg)
        }
        saveFavorites(list)
        refreshDock()
    }

    private fun refreshDock() {
        dock.removeAllViews()
        val pkgs = favorites().filter { pkg -> allApps.any { it.pkg == pkg } }
        for (pkg in pkgs) {
            val label = allApps.first { it.pkg == pkg }.label
            dock.addView(TextView(this).apply {
                text = label.take(10)
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(8, 16, 8, 16)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { launch(pkg) }
                setOnLongClickListener { togglePin(pkg); true }
            })
        }
    }

    // ---------- hide / unhide ----------

    private fun hidden(): MutableSet<String> =
        prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()

    private fun hide(pkg: String) {
        val set = hidden()
        set.add(pkg)
        prefs.edit().putStringSet("hidden", set).apply()
        val pinned = favorites()
        if (pinned.remove(pkg)) saveFavorites(pinned)
        refreshDock()
        applyFilter(query)
    }

    private fun unhide(pkg: String) {
        val set = hidden()
        set.remove(pkg)
        prefs.edit().putStringSet("hidden", set).apply()
        applyFilter(query)
    }

    private fun showHiddenDialog() {
        val pkgs = hidden().toList()
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "কোনো লুকানো অ্যাপ নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = pkgs.map { p -> allApps.firstOrNull { it.pkg == p }?.label ?: p }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tap to unhide")
            .setItems(labels) { _, which -> unhide(pkgs[which]) }
            .show()
    }

    // ---------- app menu ----------

    private fun showAppMenu(app: AppItem) {
        val pinned = favorites().contains(app.pkg)
        val options = arrayOf(
            if (pinned) "Unpin from dock" else "Pin to dock",
            "Hide app",
            "App info",
            "Uninstall"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> togglePin(app.pkg)
                    1 -> hide(app.pkg)
                    2 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${app.pkg}"))
                    )
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            }
            .show()
    }

    // ---------- sorting ----------

    private fun usageSort(): Boolean = prefs.getBoolean("usage_sort", false)

    private fun toggleSort() {
        val next = !usageSort()
        prefs.edit().putBoolean("usage_sort", next).apply()
        sortButton.text = if (next) "most used" else "A-Z"
        applyFilter(query)
    }
}
