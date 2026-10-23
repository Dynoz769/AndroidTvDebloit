package io.github.dynoz769.debloithome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HomeActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var row: RecyclerView
    private lateinit var focusedTitle: TextView
    private lateinit var networkChip: TextView
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var focusedKey: String? = null
    private var focusFirstOnLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        repo = AppRepository(this)
        row = findViewById(R.id.app_row)
        focusedTitle = findViewById(R.id.focused_title)
        networkChip = findViewById(R.id.chip_network)

        adapter = AppAdapter(
            onOpen = ::open,
            onOptions = ::showOptions,
            onFocused = { tile ->
                focusedKey = tile.key
                focusedTitle.text = tile.title(this)
            },
        )
        row.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        row.adapter = adapter

        val inputsChip = findViewById<View>(R.id.chip_inputs)
        inputsChip.visibility = if (TvInputs.list(this).isEmpty()) View.GONE else View.VISIBLE
        inputsChip.setOnClickListener { showInputs() }
        networkChip.setOnClickListener { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        findViewById<View>(R.id.chip_settings).setOnClickListener { openSettings(Settings.ACTION_SETTINGS) }

        watchNetwork()
    }

    override fun onResume() {
        super.onResume()
        updateNetwork()
        repo.loadAsync { render() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the first app.
        focusPosition(0)
    }

    override fun onDestroy() {
        networkCallback?.let { connectivity().unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The home screen has nothing to go back to.
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun render(focusKey: String? = null) {
        val tiles = repo.homeApps().map { Tile.App(it) } + Tile.AllApps
        val changed = adapter.submit(tiles)
        when {
            focusFirstOnLoad -> {
                focusFirstOnLoad = false
                focusPosition(0)
            }
            focusKey != null -> focusPosition(tiles.indexOfFirst { it.key == focusKey })
            changed && row.focusedChild == null && focusedKey != null ->
                focusPosition(tiles.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0))
        }
    }

    private fun focusPosition(position: Int) {
        if (position < 0) return
        row.post {
            row.scrollToPosition(position)
            row.post { row.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
        }
    }

    private fun open(tile: Tile) {
        when (tile) {
            is Tile.App -> launchApp(tile.entry)
            Tile.AllApps -> startActivity(Intent(this, AllAppsActivity::class.java))
        }
    }

    private fun showOptions(tile: Tile) {
        if (tile !is Tile.App) return
        val pkg = tile.entry.packageName
        showActions(
            tile.entry.label,
            listOf(
                getString(R.string.move_left) to { move(tile, -1) },
                getString(R.string.move_right) to { move(tile, 1) },
                getString(R.string.move_first) to { move(tile, AppRepository.TO_FRONT) },
                getString(R.string.hide_from_home) to {
                    repo.setHidden(pkg, true)
                    render()
                },
                getString(R.string.app_info) to { openAppInfo(pkg) },
            ),
        )
    }

    private fun move(tile: Tile.App, delta: Int) {
        repo.move(tile.entry.packageName, delta)
        render(focusKey = tile.key)
    }

    private fun showInputs() {
        val inputs = TvInputs.list(this)
        if (inputs.isEmpty()) {
            toast(getString(R.string.no_inputs))
            return
        }
        showActions(
            getString(R.string.inputs),
            inputs.map { input ->
                TvInputs.label(this, input) to {
                    if (!TvInputs.open(this, input)) toast(getString(R.string.cannot_switch_input))
                }
            },
        )
    }

    private fun connectivity() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            private fun refresh() = runOnUiThread { updateNetwork() }
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                connectivity().registerDefaultNetworkCallback(callback)
            } else {
                connectivity().registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            networkCallback = callback
        } catch (e: RuntimeException) {
            // The chip is still refreshed on every resume.
        }
    }

    private fun updateNetwork() {
        val cm = connectivity()
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val (label, icon) = when {
            caps == null -> R.string.net_offline to R.drawable.ic_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.net_ethernet to R.drawable.ic_ethernet
            else -> R.string.net_wifi to R.drawable.ic_wifi
        }
        networkChip.setText(label)
        networkChip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }
}
