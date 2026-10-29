package com.openminis.app.ui.chat.share

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-share-preview-close-crash] Closing the Share Chat… preview crashed (Pixel 6,
 * NPE in ViewGroup.dispatchDetachedFromWindow under Dialog.dismiss). The render
 * stage's layer sits on the dialog window's decor view, and the stage is disposed
 * from inside that decor view's detach loop (the Compose dialog content disposes
 * on detach), so it must not take the layer out of the host synchronously.
 */
class ChatShareStageDisposeTest {
    private val renderer = ProductionSources.read("ui/chat/share/ChatExportRenderer.kt")
    private val preview = ProductionSources.read("ui/chat/share/ChatSharePreviewScreen.kt")

    private val dispose = renderer.substringAfter("    fun dispose() {").substringBefore("\n    }\n")

    @Test fun `the stage is disposed from the preview's composition, during the dialog teardown`() {
        assertTrue(preview.contains("ChatExportStage(view.rootView as ViewGroup, view, parent)"))
        assertTrue(preview.contains("DisposableEffect(stage) { onDispose { stage.dispose() } }"))
    }

    @Test fun `dispose never removes the layer from the host synchronously`() {
        assertFalse("synchronous removal from the host", dispose.lines().any { it.trim() == "host.removeView(layer)" })
        val post = dispose.indexOf("Handler(android.os.Looper.getMainLooper()).post {")
        assertTrue("the layer leaves the host on a later main-loop turn", post >= 0)
        assertTrue(dispose.indexOf("(layer.parent as? ViewGroup)?.removeView(layer)") > post)
    }

    @Test fun `the rendered group views still go at once`() {
        val clear = dispose.indexOf("layer.removeAllViews()")
        assertTrue(clear >= 0 && clear < dispose.indexOf(".post {"))
    }
}
