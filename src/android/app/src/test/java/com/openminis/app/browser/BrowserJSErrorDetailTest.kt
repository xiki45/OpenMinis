package com.openminis.app.browser

import com.openminis.app.ProductionSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [T-android-js-error-detail] Port of iOS adb784ed2. On a document with no
 * body, get_text / get_readable / get_page_info / scroll returned the success
 * text "null" and get_backbone "Value null of type org.json.JSONObject$1
 * cannot be converted to JSONObject" (reproduced on a Pixel 4a): the scripts
 * threw, and `WebView.evaluateJavascript` swallows the exception and hands the
 * callback a bare null.
 *
 *   [1] The shipped scripts, run in Node against a fake `document`: clear
 *       errors without a body, "Script threw: <name>: <message>" for a throw,
 *       success output unchanged. Skipped when no `node` is installed.
 *   [2] BrowserJSResult: null is "no result" for must-return scripts.
 *   [3] Source guards on BrowserUseManager's call sites.
 */
class BrowserJSErrorDetailTest {

    companion object {
        private var node: String? = null

        @BeforeClass @JvmStatic
        fun findNode() {
            val home = System.getProperty("user.home")
            val nvm = File(home, ".nvm/versions/node").listFiles()?.map { File(it, "bin/node").path } ?: emptyList()
            node = (listOfNotNull(System.getenv("NODE"), "node", "/opt/homebrew/bin/node", "/usr/local/bin/node") + nvm)
                .firstOrNull { runCatching { ProcessBuilder(it, "-e", "0").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        }

        private val FAKE_DOM = """
            var console = {log: function(){}};
            var window = {innerHeight: 800, innerWidth: 400, scrollY: 0, scrollX: 0, scrollBy: function(){},
              getComputedStyle: function(){ return {display: 'block', visibility: 'visible', opacity: '1', overflowY: 'visible'}; }};
            var location = {href: 'https://example.com/logo.svg'};
            window.location = location;
            var bodyEl = {tagName: 'BODY', id: '', className: '', innerText: 'Hello world', textContent: 'Hello world',
              scrollHeight: 120, firstElementChild: {}, children: [], querySelectorAll: function(){ return []; }};
            var document = {readyState: 'complete', title: 'T', contentType: 'image/svg+xml', body: null,
              documentElement: {scrollHeight: 44, scrollTop: 0}, forms: [], links: [], images: [],
              querySelector: function(){ return null; }, querySelectorAll: function(){ return []; }};
        """.trimIndent()

        private const val WITH_BODY = "document.body = bodyEl; document.contentType = 'text/html';"
    }

    /** Evaluates each (setup, script) pair in a fresh context; "THROWS …" on an exception. */
    private fun runJs(cases: List<Pair<String, String>>): List<String> {
        val bin = node
        assumeTrue("node is not installed", bin != null)
        val driver = File.createTempFile("jserr", ".js")
        try {
            val input = JSONArray(cases.map { JSONObject().put("setup", it.first).put("script", it.second) })
            driver.writeText(
                """
                const vm = require('vm');
                const cases = $input;
                const dom = ${JSONObject.quote(FAKE_DOM)};
                const out = cases.map(c => {
                  try {
                    const v = vm.runInNewContext(dom + '\n' + c.setup + '\n;' + c.script);
                    return v === null ? '<null>' : v === undefined ? '<undefined>' : String(v);
                  } catch (e) { return 'THROWS ' + e.name + ': ' + e.message; }
                });
                process.stdout.write(JSON.stringify(out));
                """.trimIndent(),
            )
            val p = ProcessBuilder(bin, driver.path).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            val arr = JSONArray(text)
            return (0 until arr.length()).map { arr.getString(it) }
        } finally {
            driver.delete()
        }
    }

    private fun one(script: String, setup: String = ""): String = runJs(listOf(setup to script)).single()

    // [1] The scripts as shipped

    @Test fun `the unguarded body read throws, which WebView turned into null`() {
        // What get_text was before the guard.
        assertTrue(one("(function(){ return document.body.innerText; })()").startsWith("THROWS TypeError"))
    }

    @Test fun `a document with no body gets a clear error from every read action`() {
        val svg = "{\"error\":\"This document has no <body> (readyState=complete, contentType=image/svg+xml, url=https://example.com/logo.svg)"
        val results = runJs(
            listOf(
                "" to BrowserUseJS.getText(null),
                "" to BrowserUseJS.getText("#x"),
                "" to BrowserUseJS.getReadable(),
                "" to BrowserUseJS.getBackbone(5),
            ),
        )
        results.forEach { assertTrue(it, it.startsWith(svg)) }
        assertTrue(results[0].endsWith("Use execute_js with document.documentElement instead.\"}"))
    }

    @Test fun `a page still loading says to wait, empty body included`() {
        val wait = "The page has no body yet (readyState=loading, url=https://example.com/logo.svg). " +
            "Wait for it to finish loading - e.g. call wait_for_dom_stable or get_page_info first - then retry."
        assertEquals("{\"error\":\"$wait\"}", one(BrowserUseJS.getText(null), "document.readyState = 'loading';"))
        assertEquals(
            "{\"error\":\"$wait\"}",
            one(BrowserUseJS.getText(null), "document.readyState = 'loading'; document.body = {firstElementChild: null, textContent: ''};"),
        )
    }

    @Test fun `with a body the success output is unchanged`() {
        assertEquals(
            "{\"text\":\"Hello world\",\"length\":11,\"url\":\"https://example.com/logo.svg\",\"title\":\"T\"}",
            one(BrowserUseJS.getText(null), WITH_BODY),
        )
        val inner = "(function(){ return JSON.stringify({a: document.body.innerText, n: 1}); })()"
        assertEquals(one(inner, WITH_BODY), one(BrowserUseJS.guardedRead(inner), WITH_BODY))
        assertEquals(one(inner, WITH_BODY), one(BrowserUseJS.guardedRead(inner, requiresBody = false), WITH_BODY))
    }

    @Test fun `a throw inside is reported with its name and message`() {
        assertEquals(
            "{\"error\":\"Script threw: RangeError: boom\"}",
            one(BrowserUseJS.getText(null), "$WITH_BODY Object.defineProperty(bodyEl, 'innerText', {get: function(){ throw new RangeError('boom'); }});"),
        )
    }

    @Test fun `get_page_info works without a body`() {
        val info = JSONObject(one(BrowserUseJS.getPageInfo()))
        assertEquals(44, info.getInt("scrollHeight"))
        assertFalse(info.has("error"))
    }

    @Test fun `write actions get the try-catch but no body requirement`() {
        // scroll walks document.body: it now says why instead of returning null.
        val scroll = one(BrowserUseJS.scroll(ScrollDirection.DOWN, 300, null))
        assertTrue(scroll, scroll.startsWith("{\"error\":\"Script threw: TypeError: "))
        // click needs no body: an element-not-found answer is unchanged.
        assertEquals("{\"error\":\"Element not found: #b\"}", one(BrowserUseJS.click("#b")))
    }

    @Test fun `execute_js names the exception, and a syntax error is described`() {
        assertEquals(
            "Script threw: TypeError: boom",
            one("(function(){ try { throw new TypeError('boom'); } catch (e) { return ${BrowserUseJS.SCRIPT_THREW_JS}; } })()"),
        )
        val bad = one(BrowserUseJS.syntaxCheck("return (1 +;"))
        assertTrue(bad, bad.startsWith("SyntaxError: "))
        assertEquals("", one(BrowserUseJS.syntaxCheck("var a = await Promise.resolve(1); return a + \"'\\n\";")))
        assertTrue(BrowserJSResult.syntaxErrorMessage(bad).endsWith("so it never ran - $bad"))
        assertTrue(BrowserJSResult.syntaxErrorMessage("EvalError: Refused to evaluate").contains("does not report the details"))
        assertTrue(BrowserJSResult.syntaxErrorMessage(null).contains("does not report the details"))
        assertTrue("compiles alone → not called a syntax error", BrowserJSResult.syntaxErrorMessage("").contains("the script did not run. It compiles on its own"))
    }

    // [2] Result classification

    @Test fun `decode unquotes strings and keeps JS null apart from the string null`() {
        assertEquals(null, BrowserJSResult.decode(null))
        assertEquals(null, BrowserJSResult.decode("null"))
        assertEquals("null", BrowserJSResult.decode("\"null\""))
        assertEquals("{\"a\":\"b\\\"c\"}", BrowserJSResult.decode("\"{\\\"a\\\":\\\"b\\\\\\\"c\\\"}\""))
        assertEquals("42", BrowserJSResult.decode("42"))
        assertEquals("{}", BrowserJSResult.decode("{}"))
    }

    @Test fun `classify reports no result instead of the success text null`() {
        assertEquals(BrowserJSResult.Outcome.NoResult, BrowserJSResult.classify(null))
        assertEquals(BrowserJSResult.Outcome.ScriptError("x"), BrowserJSResult.classify("{\"error\":\"x\"}"))
        assertEquals(BrowserJSResult.Outcome.Text("plain"), BrowserJSResult.classify("plain"))
        assertEquals(BrowserJSResult.Outcome.Text("null"), BrowserJSResult.classify("null"))
        val json = BrowserJSResult.classify("{\"text\":\"t\"}") as BrowserJSResult.Outcome.Json
        assertEquals("t", json.json.getString("text"))
        assertTrue(BrowserJSResult.NO_RESULT_ERROR.startsWith("JavaScript error: the page script stopped without returning a result."))
    }

    // [3] Call sites

    private val manager = ProductionSources.read("browser/BrowserUseManager.kt")

    @Test fun `every must-return action goes through the classifier`() {
        assertTrue(manager.contains("private suspend fun evaluateAndReturn(js: String): BrowserActionResult =\n        evaluateActionScript(js)"))
        assertTrue(manager.contains("private suspend fun evaluateJSAndParse(js: String): BrowserActionResult =\n        evaluateActionScript(js)"))
        assertTrue(manager.contains("return evaluateActionScript(BrowserUseJS.getBackbone(depth))"))
        assertTrue(manager.contains("BrowserJSResult.Outcome.NoResult -> BrowserActionResult.error(BrowserJSResult.NO_RESULT_ERROR)"))
        val backbone = manager.substringAfter("private suspend fun getBackbone(").substringBefore("// -- Fetch --")
        assertFalse("get_backbone no longer parses the raw result itself", backbone.contains("JSONObject(raw)"))
    }

    @Test fun `every read script is guarded, page info without the body requirement`() {
        val js = ProductionSources.read("browser/BrowserUseJS.kt")
        // click, clickCoordinate, type, get_text x2, get_readable, scroll, hover, find_elements, get_page_info, get_backbone
        assertEquals(11, Regex("guardedRead\\(\"\"\"").findAll(js).count())
        assertTrue(js.contains("fun getPageInfo(): String = guardedRead(\"\"\""))
        assertTrue(js.substringAfter("fun getPageInfo()").substringBefore("// -- Get Backbone --").contains("\"\"\".trimIndent(), requiresBody = false)"))
        assertTrue(js.substringAfter("fun getBackbone(").substringBefore("// -- Fetch").trimEnd().endsWith("\"\"\".trimIndent())"))
    }

    @Test fun `execute_js keeps a legitimate null and detects a script that did not parse`() {
        val exec = manager.substringAfter("private suspend fun executeJS(").substringBefore("// -- Find Elements --")
        assertTrue("return null still resolves as the text null", exec.contains("__minis__.resolve(String(__v__));"))
        assertTrue(exec.contains("__minis__.reject(\${BrowserUseJS.SCRIPT_THREW_JS});"))
        assertTrue(exec.contains("if (BrowserJSResult.decode(value) == null) deferred.complete(EXECUTE_JS_DID_NOT_PARSE)"))
        assertTrue(exec.contains("BrowserJSResult.syntaxErrorMessage(detail)"))
        assertTrue("timeout text unchanged", exec.contains("\"JavaScript execution timed out (30s)\""))
    }
}
