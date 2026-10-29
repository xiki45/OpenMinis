package com.openminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which part of an ACTION_SEND the share receiver stages. */
class SingleSendSourceTest {

    @Test
    fun `text plain with text is the text`() {
        assertEquals(SingleSendSource.TEXT, singleSendSource("text/plain", hasText = true))
    }

    @Test
    fun `a text plain file with no text is the file - Minis's own log and preview shares`() {
        assertEquals(SingleSendSource.STREAM, singleSendSource("text/plain", hasText = false))
    }

    @Test
    fun `any other type is the file, caption or not`() {
        assertEquals(SingleSendSource.STREAM, singleSendSource("image/png", hasText = true))
        assertEquals(SingleSendSource.STREAM, singleSendSource("application/pdf", hasText = false))
        assertEquals(SingleSendSource.STREAM, singleSendSource("", hasText = false))
    }
}
