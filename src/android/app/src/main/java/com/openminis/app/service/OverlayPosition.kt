package com.openminis.app.service

/**
 * [T-overlay-position-restore] Where the floating capsule's window goes: the
 * position remembered from the user's last drag, kept reachable, or the
 * default when nothing was ever remembered.
 *
 * Coordinates are the window's top-left corner in pixels (gravity TOP|START).
 * The window is FLAG_LAYOUT_NO_LIMITS and a drag is not clamped, so a
 * remembered position may be partly off-screen on any side - x is NEGATIVE
 * when the capsule hangs off the left edge. A negative coordinate is a real
 * position, never a "nothing saved" marker: the old reader tested
 * `savedX >= 0 && savedY >= 0`, so a capsule parked off the left (or top)
 * edge was thrown back to the default corner the next time it was attached,
 * while one parked off the right edge, whose x is a large positive number,
 * was restored.
 *
 * [T-overlay-no-top-hide] What is SHOWN is never half past the top edge any
 * more (see [clamp]); the saved drag itself is still kept as dragged.
 */
internal object OverlayPosition {

    data class Point(val x: Int, val y: Int)

    /**
     * [T-overlay-orientation-position] Each orientation keeps its own
     * position. With one shared position a rotation clamped it to the other
     * orientation's screen and saved that, so a capsule left low in portrait
     * came back 850 px higher after a trip through landscape (Alice Q2).
     */
    enum class Orientation {
        PORTRAIT, LANDSCAPE;

        companion object {
            /** A square screen counts as portrait. */
            fun of(screenW: Int, screenH: Int): Orientation = if (screenW > screenH) LANDSCAPE else PORTRAIT
        }
    }

    /**
     * Screen edges the capsule must not cover at all, in pixels: the
     * navigation bar or gesture inset on that side. Zero on the left, right or
     * bottom keeps the half-on-screen rule there.
     *
     * [T-overlay-no-top-hide] The top is different: the capsule is always
     * fully below it, [top] or 0 when [top] is 0. The half-hidden top that
     * [T-overlay-position-restore] allowed put the capsule in the system's
     * pull-down gesture strip, a suspected cause of "it won't drag" (Alice,
     * inferred, not reproduced). [top] is the status bar / cutout height.
     */
    data class Avoid(val left: Int = 0, val right: Int = 0, val bottom: Int = 0, val top: Int = 0) {
        companion object {
            val NONE = Avoid()
        }
    }

    /** The saved positions, one per orientation. */
    interface Store {
        fun get(orientation: Orientation): Point?
        fun set(orientation: Orientation, point: Point)
    }

    /**
     * System bar avoidance for [orientation]. Left, right and bottom are
     * landscape only: there the capsule was clamped onto the home gesture bar,
     * where touching it starts a system gesture; portrait keeps the edge
     * behaviour it already had on those sides. [top] (status bar / cutout,
     * [T-overlay-no-top-hide]) applies in both orientations.
     */
    fun avoidFor(orientation: Orientation, left: Int, right: Int, bottom: Int, top: Int = 0): Avoid =
        if (orientation == Orientation.LANDSCAPE) {
            Avoid(left.coerceAtLeast(0), right.coerceAtLeast(0), bottom.coerceAtLeast(0), top.coerceAtLeast(0))
        } else {
            Avoid(top = top.coerceAtLeast(0))
        }

    /**
     * Where the capsule is shown on a screen of this size: the position saved
     * for its orientation, clamped, or [default] when that orientation has
     * none. Reads only; the clamp is for display and is never saved, so the
     * next rotation back finds the user's position intact.
     */
    fun displayed(store: Store, default: Point, screenW: Int, screenH: Int, capsuleW: Int, capsuleH: Int, avoid: Avoid): Point =
        restore(store.get(Orientation.of(screenW, screenH)), default, screenW, screenH, capsuleW, capsuleH, avoid)

    /** A drag ended at [point]: the only write, and only to the current orientation. */
    fun dragEnded(store: Store, screenW: Int, screenH: Int, point: Point) =
        store.set(Orientation.of(screenW, screenH), point)

    /**
     * Position to attach at. [saved] is null when no drag was ever stored,
     * which is the only case that gets [default].
     */
    fun restore(
        saved: Point?,
        default: Point,
        screenW: Int,
        screenH: Int,
        capsuleW: Int,
        capsuleH: Int,
        avoid: Avoid = Avoid.NONE,
    ): Point {
        val at = saved ?: default
        // The default is computed for an unobstructed screen; it goes through
        // the avoidance too, so it does not land on a landscape gesture bar.
        if (saved == null && avoid == Avoid.NONE) return default
        return clamp(at.x, at.y, screenW, screenH, capsuleW, capsuleH, avoid)
    }

    /**
     * Keep at least half of the capsule on screen on the left, right and
     * bottom. A position the user could see half of is left exactly where it
     * was - parking the capsule against (or partly past) a side edge is how
     * people use it - while one saved on a larger or rotated screen, or
     * dragged almost entirely away, is pulled back until half of it shows and
     * it can be grabbed.
     *
     * A side with a non-zero [avoid] inset is the exception: there the
     * capsule stays entirely clear of the inset instead.
     *
     * [T-overlay-no-top-hide] The top always keeps the whole capsule on
     * screen: y >= [Avoid.top], and y >= 0 when that is 0.
     */
    fun clamp(x: Int, y: Int, screenW: Int, screenH: Int, capsuleW: Int, capsuleH: Int, avoid: Avoid = Avoid.NONE): Point =
        Point(
            clampAxis(x, screenW, capsuleW, avoidStart = avoid.left, avoidEnd = avoid.right),
            clampAxis(y, screenH, capsuleH, avoidStart = avoid.top, avoidEnd = avoid.bottom, wholeAtStart = true),
        )

    /** [wholeAtStart]: the start edge keeps the whole capsule on screen even with no inset there. */
    private fun clampAxis(pos: Int, screen: Int, size: Int, avoidStart: Int, avoidEnd: Int, wholeAtStart: Boolean = false): Int {
        val min = if (avoidStart > 0 || wholeAtStart) avoidStart.coerceAtLeast(0) else -(size / 2)
        val max = (if (avoidEnd > 0) screen - avoidEnd - size else screen - (size - size / 2)).coerceAtLeast(min)
        return pos.coerceIn(min, max)
    }
}
