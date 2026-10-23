package io.github.dynoz769.debloithome

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build

/** HDMI and other external inputs of the TV, for the Inputs chip. */
object TvInputs {

    fun list(context: Context): List<TvInputInfo> {
        // Null on TV boxes and sticks that have no inputs.
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            manager.tvInputList.filter {
                it.isPassthroughInput && it.parentId == null &&
                    (Build.VERSION.SDK_INT < 24 || !it.isHidden(context))
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    fun label(context: Context, input: TvInputInfo): String {
        val custom = if (Build.VERSION.SDK_INT >= 24) input.loadCustomLabel(context)?.toString() else null
        return custom?.takeIf { it.isNotBlank() } ?: input.loadLabel(context).toString()
    }

    fun open(context: Context, input: TvInputInfo): Boolean =
        context.startFirst(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)))
}
