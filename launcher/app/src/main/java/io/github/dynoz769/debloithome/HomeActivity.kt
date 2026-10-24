package io.github.dynoz769.debloithome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HomeActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var row: RecyclerView
    private var focusedTitle: TextView? = null
    private var tv = true
    private lateinit var networkChip: TextView
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var focusedKey: String? = null
    private var focusFirstOnLoad = true

    private var intro: IntroView? = null
    private var introPending = false
    private var resumed = false
    private var wokeAt = 0L

    // Most TVs only go to standby when switched off, so play the intro on wake too.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Tablets wake many times a day, so only TVs replay the intro on wake.
            if (!tv) return
            wokeAt = SystemClock.elapsedRealtime()
            if (resumed) playIntro()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = isTv()
        setContentView(if (tv) R.layout.activity_home else R.layout.activity_home_tablet)
        repo = AppRepository(this)
        row = findViewById(R.id.app_row)
        focusedTitle = findViewById(R.id.focused_title)
        networkChip = findViewById(R.id.chip_network)

        adapter = AppAdapter(
            onOpen = ::open,
            onOptions = ::showOptions,
            onFocused = { tile ->
                focusedKey = tile.key
                focusedTitle?.text = tile.title(this)
            },
            compact = !tv,
        )
        row.layoutManager = if (tv) {
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        } else {
            GridLayoutManager(this, columnsFor(resources.displayMetrics.widthPixels, TABLET_CELL_DP))
        }
        row.adapter = adapter

        val inputsChip = findViewById<View>(R.id.chip_inputs)
        inputsChip.visibility = if (TvInputs.list(this).isEmpty()) View.GONE else View.VISIBLE
        inputsChip.setOnClickListener { showInputs() }
        networkChip.setOnClickListener { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        findViewById<View>(R.id.chip_settings).setOnClickListener { openSettings(Settings.ACTION_SETTINGS) }

        watchNetwork()
        ContextCompat.registerReceiver(
            this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        introPending = intent.getBooleanExtra(EXTRA_INTRO, false) || IntroGate.firstTimeThisBoot(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateNetwork()
        repo.loadAsync { render() }
        val justWoke = wokeAt > 0 && SystemClock.elapsedRealtime() - wokeAt < WAKE_WINDOW_MS
        if (introPending || justWoke) playIntro()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    // The home screen handles rotation itself (see configChanges), so refit the tablet grid.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (row.layoutManager as? GridLayoutManager)?.spanCount =
            columnsFor((newConfig.screenWidthDp * resources.displayMetrics.density).toInt(), TABLET_CELL_DP)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_INTRO, false)) {
            introPending = true
        } else {
            // Home pressed while already home: jump back to the first app.
            focusPosition(0)
        }
    }

    override fun onDestroy() {
        networkCallback?.let { connectivity().unregisterNetworkCallback(it) }
        unregisterReceiver(screenOnReceiver)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val playing = intro ?: return super.dispatchKeyEvent(event)
        // Any button skips the intro.
        if (event.action == KeyEvent.ACTION_UP) playing.skip()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The home screen has nothing to go back to.
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun playIntro() {
        introPending = false
        wokeAt = 0L
        if (intro != null) return
        val content = findViewById<ViewGroup>(android.R.id.content)
        val view = IntroView(this)
        view.onFinished = {
            content.removeView(view)
            intro = null
            if (row.focusedChild == null) focusPosition(0)
        }
        content.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        intro = view
        view.start()
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

    companion object {
        /** Intent extra that asks the home screen to play the intro now. */
        const val EXTRA_INTRO = "intro"

        private const val WAKE_WINDOW_MS = 20_000L
        const val TABLET_CELL_DP = 112f
    }
}
