package com.example.minimal

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private data class AppItem(val label: String, val pkg: String)

    private val allApps = mutableListOf<AppItem>()
    private val shownApps = mutableListOf<AppItem>()
    private lateinit var appAdapter: ArrayAdapter<String>
    private lateinit var clock: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        clock = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
        }

        val search = EditText(this).apply {
            hint = "search"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
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
            setOnItemClickListener { _, _, pos, _ ->
                packageManager.getLaunchIntentForPackage(shownApps[pos].pkg)
                    ?.let { startActivity(it) }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(56, 140, 56, 56)
            addView(clock)
            addView(
                search,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24 }
            )
            addView(
                list,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    .apply { topMargin = 32 }
            )
        }
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
        applyFilter("")
    }

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps.clear()
        for (ri in packageManager.queryIntentActivities(intent, 0)) {
            if (ri.activityInfo.packageName == packageName) continue
            allApps.add(AppItem(ri.loadLabel(packageManager).toString(), ri.activityInfo.packageName))
        }
        val unique = allApps.distinctBy { it.pkg }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        allApps.clear()
        allApps.addAll(unique)
    }

    private fun applyFilter(query: String) {
        shownApps.clear()
        shownApps.addAll(allApps.filter { it.label.contains(query, ignoreCase = true) })
        appAdapter.clear()
        appAdapter.addAll(shownApps.map { it.label })
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onBackPressed() {}
}
