package io.github.dynoz769.debloithome

import android.app.Activity
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Every installed app, including the ones hidden from the home row. */
class AllAppsActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var grid: RecyclerView
    private var focusFirstOnLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        repo = AppRepository(this)
        adapter = AppAdapter(
            onOpen = { tile -> if (tile is Tile.App) launchApp(tile.entry) },
            onOptions = ::showOptions,
        )
        grid = findViewById(R.id.app_grid)
        val tileWidth = resources.getDimension(R.dimen.tile_width) + resources.getDimension(R.dimen.tile_gap)
        val usable = resources.displayMetrics.widthPixels - grid.paddingStart - grid.paddingEnd
        grid.layoutManager = GridLayoutManager(this, (usable / tileWidth).toInt().coerceAtLeast(2))
        grid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        repo.loadAsync { render() }
    }

    private fun render(focusPackage: String? = null) {
        val tiles = repo.allApps().map { Tile.App(it, repo.isHidden(it.packageName)) }
        adapter.submit(tiles)
        val position = when {
            focusFirstOnLoad -> 0
            focusPackage != null -> tiles.indexOfFirst { it.entry.packageName == focusPackage }
            else -> -1
        }
        focusFirstOnLoad = false
        if (position >= 0) grid.post { grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
    }

    private fun showOptions(tile: Tile) {
        if (tile !is Tile.App) return
        val pkg = tile.entry.packageName
        val hidden = repo.isHidden(pkg)
        showActions(
            tile.entry.label,
            listOf(
                getString(if (hidden) R.string.show_on_home else R.string.hide_from_home) to {
                    repo.setHidden(pkg, !hidden)
                    render(focusPackage = pkg)
                },
                getString(R.string.app_info) to { openAppInfo(pkg) },
            ),
        )
    }
}
