package io.github.dynoz769.debloithome

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class AppEntry(
    val packageName: String,
    val label: String,
    val launchIntent: Intent,
    val banner: Drawable?,
    val icon: Drawable,
)

/**
 * Loads launchable apps and keeps the user's home row order and hidden apps
 * in shared preferences.
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val prefs = appContext.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var apps: List<AppEntry> = emptyList()
        private set

    fun loadAsync(done: () -> Unit) {
        EXECUTOR.execute {
            val loaded = load()
            main.post {
                apps = loaded
                done()
            }
        }
    }

    private fun load(): List<AppEntry> {
        val found = LinkedHashMap<String, AppEntry>()
        // TV entry points first, so an app with both keeps its TV activity.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg == appContext.packageName || pkg in found) continue
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, activity.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                found[pkg] = AppEntry(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    launchIntent = intent,
                    banner = activity.loadBanner(pm),
                    icon = info.loadIcon(pm),
                )
            }
        }
        return found.values.toList()
    }

    /** Apps shown in the home row, in the user's order. */
    fun homeApps(): List<AppEntry> {
        val hidden = hidden()
        val order = savedOrder()
        val byPackage = apps.associateBy { it.packageName }
        val ordered = order.mapNotNull { byPackage[it] }
        val rest = apps.filter { it.packageName !in order }.sortedBy { it.label.lowercase() }
        return (ordered + rest).filter { it.packageName !in hidden }
    }

    fun allApps(): List<AppEntry> = apps.sortedBy { it.label.lowercase() }

    fun isHidden(packageName: String) = packageName in hidden()

    fun setHidden(packageName: String, hide: Boolean) {
        val set = hidden().toMutableSet()
        if (hide) set += packageName else set -= packageName
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    /** Moves an app in the home row and returns its new position. */
    fun move(packageName: String, delta: Int): Int {
        val visible = homeApps().map { it.packageName }.toMutableList()
        val from = visible.indexOf(packageName)
        if (from < 0) return 0
        val to = if (delta == TO_FRONT) 0 else (from + delta).coerceIn(0, visible.lastIndex)
        visible.add(to, visible.removeAt(from))
        // Keep the saved position of apps that are hidden or not installed right now.
        val rest = savedOrder().filter { it !in visible }
        prefs.edit().putString(KEY_ORDER, (visible + rest).joinToString(",")).apply()
        return to
    }

    private fun savedOrder(): List<String> =
        prefs.getString(KEY_ORDER, null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_ORDER

    private fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, null) ?: DEFAULT_HIDDEN

    companion object {
        const val TO_FRONT = Int.MIN_VALUE

        private const val KEY_ORDER = "order"
        private const val KEY_HIDDEN = "hidden"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        private val DEFAULT_ORDER = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android",
        )

        // Reachable from the Settings chip, so it doesn't need a tile.
        private val DEFAULT_HIDDEN = setOf("com.android.tv.settings")
    }
}
