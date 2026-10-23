package io.github.dynoz769.debloithome

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class Tile {
    data class App(val entry: AppEntry, val hidden: Boolean = false) : Tile()
    data object AllApps : Tile()

    val key: String
        get() = when (this) {
            is App -> "app:${entry.packageName}:$hidden"
            AllApps -> "all"
        }

    fun title(context: Context): String = when (this) {
        is App -> entry.label
        AllApps -> context.getString(R.string.all_apps)
    }
}

class AppAdapter(
    private val onOpen: (Tile) -> Unit,
    private val onOptions: (Tile) -> Unit,
    private val onFocused: (Tile) -> Unit = {},
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    var tiles: List<Tile> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    /** Returns true when the list changed and the views were rebound. */
    fun submit(newTiles: List<Tile>): Boolean {
        val changed = newTiles.map { it.key } != tiles.map { it.key }
        tiles = newTiles
        if (changed) notifyDataSetChanged()
        return changed
    }

    override fun getItemCount() = tiles.size

    override fun getItemId(position: Int) = tiles[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        view.clipToOutline = true
        val holder = Holder(view)
        val focusLift = parent.resources.displayMetrics.density * 12

        view.setOnClickListener { holder.tile()?.let(onOpen) }
        view.setOnLongClickListener {
            holder.tile()?.let(onOptions)
            true
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                holder.tile()?.let(onOptions)
                true
            } else {
                false
            }
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.1f else 1f
            v.animate().scaleX(scale).scaleY(scale)
                .translationZ(if (hasFocus) focusLift else 0f)
                .setDuration(150).start()
            if (hasFocus) holder.tile()?.let(onFocused)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val view = holder.itemView
        if (!view.isFocused) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationZ = 0f
        }
        when (val tile = tiles[position]) {
            is Tile.App -> {
                val banner = tile.entry.banner
                if (banner != null) {
                    holder.banner.setImageDrawable(banner)
                    holder.banner.visibility = View.VISIBLE
                    holder.iconGroup.visibility = View.GONE
                } else {
                    holder.icon.setImageDrawable(tile.entry.icon)
                    holder.label.text = tile.entry.label
                    holder.banner.visibility = View.GONE
                    holder.iconGroup.visibility = View.VISIBLE
                }
                view.alpha = if (tile.hidden) 0.45f else 1f
                view.contentDescription = tile.entry.label
            }
            Tile.AllApps -> {
                holder.icon.setImageResource(R.drawable.ic_apps)
                holder.label.setText(R.string.all_apps)
                holder.banner.visibility = View.GONE
                holder.iconGroup.visibility = View.VISIBLE
                view.alpha = 1f
                view.contentDescription = view.context.getString(R.string.all_apps)
            }
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val banner: ImageView = view.findViewById(R.id.banner)
        val iconGroup: View = view.findViewById(R.id.icon_group)
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)

        fun tile(): Tile? = tiles.getOrNull(bindingAdapterPosition)
    }
}
