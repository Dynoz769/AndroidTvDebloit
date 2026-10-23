package io.github.dynoz769.debloithome

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Starts the first intent that something on the TV can handle. */
fun Context.startFirst(vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            // Try the next one.
        } catch (e: SecurityException) {
            // Try the next one.
        }
    }
    return false
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun Context.launchApp(entry: AppEntry) {
    if (!startFirst(Intent(entry.launchIntent))) toast(getString(R.string.cannot_open, entry.label))
}

fun Context.openAppInfo(packageName: String) {
    startFirst(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

fun Context.openSettings(action: String) {
    startFirst(Intent(action), Intent(Settings.ACTION_SETTINGS))
}

/** A simple list dialog where each row runs an action. */
fun Activity.showActions(title: String, actions: List<Pair<String, () -> Unit>>) {
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
        .show()
}
