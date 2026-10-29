package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [T-android-badge-clip] An [IconButton] with a badge that is never clipped.
 *
 * Material3's IconButton clips its content to its own shape: the modifier
 * chain is `minimumInteractiveComponentSize().size(40dp).clip(CircleShape)`
 * (confirmed in material3 1.3.2's IconButtonKt). A [BadgedBox] placed INSIDE
 * the button anchors the badge at the 24dp icon's top-end corner and pushes
 * it out from there, i.e. into the corner of the button where the 40dp circle
 * has already curved away - so a count badge ("18", "99+") lost its top-right
 * corner to the circle (user report, home toolbar scheduled-tasks clock).
 *
 * Here the BadgedBox wraps the button instead, so nothing above the badge
 * clips it. Its anchor is then the 48dp button rather than the 24dp icon,
 * whose top-end corner sits [ANCHOR_INSET] further right and up; the badge is
 * offset back by exactly that, so it lands where it always did. The icon and
 * the 48dp touch target are unchanged.
 *
 * [badge] null = a plain IconButton.
 */
@Composable
fun BadgedIconButton(
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    if (badge == null) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { icon() }
        return
    }
    BadgedBox(
        badge = { Box(Modifier.offset(x = -ANCHOR_INSET, y = ANCHOR_INSET)) { badge() } },
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) { icon() }
    }
}

/** (48dp minimum touch target - 24dp icon) / 2: how far the icon sits inside the button. */
internal val ANCHOR_INSET: Dp = 12.dp
