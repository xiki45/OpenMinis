package com.openminis.app.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [B02] Runs the SHARED wireToolId vectors (src/shared/tool-id, the same file
 * the iOS tests read), so both platforms send byte-identical tool ids for the
 * same stored id.
 */
class WireToolIdVectorsTest {

    // Walk up to the repo's src/ and into shared/tool-id, tolerating either CWD.
    private val vectors: JSONObject = sequenceOf(
        File("../shared/tool-id"),         // CWD = src/android
        File("../../shared/tool-id"),      // CWD = src/android/app
    ).firstOrNull { it.isDirectory }
        .let { it ?: File("../../shared/tool-id") }
        .let { JSONObject(File(it, "wire_tool_id_vectors.json").readText()) }

    private val legal = Regex("^[A-Za-z0-9_-]+$")

    @Test
    fun `every shared vector maps to its expected wire id`() {
        val list = vectors.getJSONArray("vectors")
        assertTrue(list.length() >= 10)
        val failures = (0 until list.length()).mapNotNull { i ->
            val v = list.getJSONObject(i)
            val got = wireToolId(v.getString("input"))
            if (got == v.getString("expected")) null else "${v.getString("name")}: expected ${v.getString("expected")}, got $got"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun `every wire id is legal, at most 40 chars, and stable`() {
        val cap = vectors.getInt("cap")
        assertEquals(40, cap)
        val list = vectors.getJSONArray("vectors")
        for (i in 0 until list.length()) {
            val raw = list.getJSONObject(i).getString("input")
            val wire = wireToolId(raw)
            assertTrue("'$wire' is not legal", legal.matches(wire))
            assertTrue("'$wire' is longer than $cap", wire.length <= cap)
            assertEquals("same raw, same wire", wire, wireToolId(raw))
        }
    }

    @Test
    fun `ids over the cap become exactly 40 chars`() {
        assertEquals(40, wireToolId("call_" + "a".repeat(36)).length)
        assertEquals(40, wireToolId("x".repeat(100)).length)
        assertEquals(40, wireToolId("").length)
    }

    @Test
    fun `a collision pair shares a wire id, and the per-request map still tells them apart`() {
        val pairs = vectors.getJSONArray("collision_pairs")
        for (i in 0 until pairs.length()) {
            val a = pairs.getJSONArray(i).getString(0)
            val b = pairs.getJSONArray(i).getString(1)
            assertEquals(wireToolId(a), wireToolId(b))

            val map = WireToolIdMap()
            val wa = map.idFor(a)
            val wb = map.idFor(b)
            assertNotEquals("two raw ids must not share a wire id in one request", wa, wb)
            assertEquals("a result finds its call's id", wa, map.idFor(a))
            assertEquals(wb, map.idFor(b))
            for (w in listOf(wa, wb)) {
                assertTrue(legal.matches(w))
                assertTrue(w.length <= 40)
            }
        }
    }

    @Test
    fun `the map keeps a renamed long id within 40 chars`() {
        val map = WireToolIdMap()
        val first = map.idFor("call_" + "c".repeat(35) + "|fc_1")
        val second = map.idFor("call_" + "c".repeat(35) + "|fc_2")
        assertEquals(40, first.length)
        assertNotEquals(first, second)
        assertTrue(second.length <= 40)
        assertTrue(second.endsWith("-2"))
    }
}
