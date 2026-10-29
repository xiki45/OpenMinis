package com.openminis.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [T-android-picker-anf] Launch a system file / document / folder / media
 * picker without crashing when the device has none.
 *
 * The SAF contracts (OpenDocument, OpenMultipleDocuments, CreateDocument,
 * GetContent, OpenDocumentTree, and PickVisualMedia when it falls back to
 * ACTION_OPEN_DOCUMENT) call startActivityForResult inside `launch()`. Some
 * OEM ROMs ship without a resolvable DocumentsUI, and there the launch throws
 * ActivityNotFoundException and takes the process down (field crash: OnePlus
 * 8T / ColorOS on the mount picker, T-android-mount-picker-anf). Catch it, log
 * it, and tell the user instead.
 *
 * A resolveActivity / queryIntentActivities pre-check is deliberately NOT used:
 * under package-visibility filtering it can report nothing on devices where the
 * picker launches fine, which would disable the picker for everyone.
 *
 * Returns true when the picker was launched, false when it was unavailable.
 */
fun <I> ActivityResultLauncher<I>.safeLaunchPicker(
    input: I,
    context: Context,
    tag: String,
    @StringRes message: Int = R.string.picker_unavailable,
): Boolean = launchPickerSafely(
    launch = { launch(input) },
    log = { AppLogger.warning(tag, it) },
    notify = { Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show() },
)

/**
 * The part of [safeLaunchPicker] that decides what happens, kept free of
 * Android UI so it runs in a plain JVM test. Only ActivityNotFoundException is
 * caught; anything else still propagates as it did before.
 */
internal fun launchPickerSafely(
    launch: () -> Unit,
    log: (String) -> Unit,
    notify: () -> Unit,
): Boolean {
    try {
        launch()
        return true
    } catch (e: ActivityNotFoundException) {
        log("picker unavailable: ${e.message}")
        notify()
        return false
    }
}
