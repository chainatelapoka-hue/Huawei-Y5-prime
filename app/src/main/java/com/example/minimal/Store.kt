package com.example.minimal

import android.content.Context
import android.content.SharedPreferences

class Group(val name: String, val folder: Boolean, val pkgs: MutableList<String>)

object Store {
    const val MAX_DOCK = 5
    private lateinit var prefs: SharedPreferences

    fun init(c: Context) {
        if (!::prefs.isInitialized) prefs = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    }

    private fun list(key: String): MutableList<String> =
        prefs.getString(key, "").orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()

    fun favorites(): MutableList<String> = list("favorites")
    fun saveFavorites(v: List<String>) { prefs.edit().putString("favorites", v.joinToString(",")).apply() }

    fun hidden(): MutableSet<String> = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
    fun saveHidden(v: Set<String>) { prefs.edit().putStringSet("hidden", v).apply() }

    fun count(pkg: String): Int = prefs.getInt("count_$pkg", 0)
    fun bumpCount(pkg: String) { prefs.edit().putInt("count_$pkg", count(pkg) + 1).apply() }

    fun usageSort(): Boolean = prefs.getBoolean("usage_sort", false)
    fun setUsageSort(v: Boolean) { prefs.edit().putBoolean("usage_sort", v).apply() }

    fun swipeApp(pkg: String): String? = prefs.getString("swipe_$pkg", null)
    fun setSwipeApp(pkg: String, target: String?) {
        val e = prefs.edit()
        if (target == null) e.remove("swipe_$pkg") else e.putString("swipe_$pkg", target)
        e.apply()
    }

    fun gesture(key: String, default: String): String = prefs.getString("gesture_$key", default) ?: default
    fun setGesture(key: String, action: String) { prefs.edit().putString("gesture_$key", action).apply() }

    fun animation(): Int = prefs.getInt("anim", 0)
    fun setAnimation(v: Int) { prefs.edit().putInt("anim", v).apply() }

    fun groups(): MutableList<Group> {
        val out = mutableListOf<Group>()
        for (line in prefs.getString("groups", "").orEmpty().split("\n")) {
            val p = line.split("|")
            if (p.size < 3) continue
            out.add(Group(p[0], p[1] == "folder", p[2].split(",").filter { it.isNotBlank() }.toMutableList()))
        }
        return out
    }

    fun saveGroups(gs: List<Group>) {
        val s = gs.joinToString("\n") {
            "${it.name}|${if (it.folder) "folder" else "tab"}|${it.pkgs.joinToString(",")}"
        }
        prefs.edit().putString("groups", s).apply()
    }
}
