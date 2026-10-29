package com.openminis.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.openminis.app.data.SpeedTier

/**
 * [T-speed-tier-icons] Monochrome speed-tier glyphs, the Android counterparts
 * of the iOS SF Symbols: Standard `speedometer`, Fast `bolt` (outline),
 * Ultrafast `bolt.fill` (solid).
 *
 * Why local vectors for the bolt: in material-icons-extended, `Outlined.Bolt`
 * and `Filled.Bolt` are the same closed solid shape (identical path data), and
 * so are the two `FlashOn` variants — an "outlined" bolt from the library is
 * visually solid and cannot tell Fast from Ultrafast. Both bolts below are
 * built from ONE polygon, stroked for Fast and filled for Ultrafast, so the
 * only difference the eye sees is outline vs fill. The paint color is a
 * placeholder: Icon's tint recolors fill and stroke alike.
 */
object SpeedTierIcons {
    private fun bolt(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                // Thin for the outline, a hairline for the fill so its edge
                // lines up with the outline version's silhouette.
                strokeLineWidth = if (filled) 0.8f else 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.5f, 2.5f)
                lineTo(4.5f, 13.5f)
                lineTo(11f, 13.5f)
                lineTo(10.5f, 21.5f)
                lineTo(19.5f, 10.5f)
                lineTo(13f, 10.5f)
                close()
            }
        }.build()

    /**
     * [T-android-ultrafast-align] Share of the 24-unit box the bolt's ink fills
     * vertically: the path spans y 2.5..21.5, plus half the outline's 1.8 stroke
     * at each end. Used to size an Icon so the bolt itself, not its box, is a
     * given height.
     */
    const val BOLT_INK_FRACTION = (21.5f - 2.5f + 1.8f) / 24f

    /** The Icon box size whose bolt ink is [inkHeightPx] tall. */
    fun boltBoxForInk(inkHeightPx: Float): Float = inkHeightPx / BOLT_INK_FRACTION

    /** Fast: outline bolt (SF `bolt`). */
    val BoltOutline: ImageVector by lazy { bolt("SpeedBoltOutline", filled = false) }

    /** Ultrafast: solid bolt (SF `bolt.fill`). */
    val BoltFill: ImageVector by lazy { bolt("SpeedBoltFill", filled = true) }

    /** Standard and the "Speed" entry: speedometer (SF `speedometer`). */
    val Speedometer: ImageVector get() = Icons.Outlined.Speed

    fun forTier(tier: SpeedTier): ImageVector = when (tier) {
        SpeedTier.STANDARD -> Speedometer
        SpeedTier.FAST -> BoltOutline
        SpeedTier.ULTRAFAST -> BoltFill
    }
}
