package com.openminis.app.config

import com.openminis.app.config.collections.SubAgentsCollection
import com.openminis.app.data.model.SubAgentDefinition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-minis-config-coverage] The settings added to minis-config in this round
 * bind to the store and key their Settings row uses, keep the iOS paths, and
 * leave the locked kinds of value (credentials, raw request overrides) out.
 *
 * Source-level, like ChatConfigPrefsBindingTest: the Prefs fields need a real
 * SharedPreferences, and the defect this guards against - a field that writes
 * a value nothing reads - is fully visible at the registration site.
 */
class ConfigCoverageBindingTest {

    private fun source(relative: String): String {
        val f = File("src/main/java/com/openminis/app/$relative")
        assertTrue("source not found: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private val builtins by lazy { source("config/ConfigBuiltins.kt") }

    /** The registration block of [path] in ConfigBuiltins. */
    private fun registration(path: String, text: String = builtins): String {
        val at = text.indexOf("path = \"$path\"")
        assertTrue("nothing registers $path", at >= 0)
        val end = text.indexOf("\n        )", at)
        return text.substring(at, if (end > at) end else at + 900)
    }

    private fun assertBinds(path: String, vararg needles: String) {
        val reg = registration(path)
        for (n in needles) assertTrue("$path must contain `$n`:\n$reg", reg.contains(n))
    }

    @Test
    fun `user_ask settings use the tool-switch store and the spec keys`() {
        assertBinds("agentTools.userAsk.enabled", "prefs = prefs", "key = spec.ENABLED_KEY", "spec.DEFAULT_ENABLED")
        assertBinds(
            "agentTools.userAsk.timeoutMinutes",
            "key = spec.TIMEOUT_MINUTES_KEY", "minValue = 0", "maxValue = spec.MAX_TIMEOUT_MINUTES",
        )
        assertBinds("agentTools.userAsk.notifyInBackground", "key = spec.NOTIFY_IN_BACKGROUND_KEY")
        // The store those three share is the one AgentToolSwitch reads.
        val fn = builtins.substringAfter("private fun registerAgentTools").substringBefore("private fun registerSpeech")
        assertTrue(fn.contains("AgentToolSwitch.PREFS"))
    }

    @Test
    fun `optional tool switches bind the AgentToolSwitch keys`() {
        assertBinds("chat.tools.browserUse", "prefs = toolPrefs", "AgentToolSwitch.BROWSER.key", "AgentToolSwitch.BROWSER.defaultValue")
        assertBinds("chat.tools.subAgents", "prefs = toolPrefs", "AgentToolSwitch.AGENTS.key", "AgentToolSwitch.AGENTS.defaultValue")
    }

    @Test
    fun `appearance-backed switches use the shared KEY constants`() {
        assertBinds("chat.autoExpandThinking", "prefs = appearancePrefs", "KEY_AUTO_EXPAND_THINKING", "defaultValue = true")
        assertBinds("appearance.appFontSize", "KEY_FONT_APP_BASE")
        assertBinds("chat.autoGrouping", "prefs = appearancePrefs", "KEY_AUTO_GROUPING", "defaultValue = true")
        assertBinds("files.showHidden", "vm.PREFS_NAME", "vm.PREF_KEY_SHOW_HIDDEN", "defaultValue = false")
    }

    @Test
    fun `the speed tier goes through FastModePrefs with the iOS tokens`() {
        assertBinds("chat.speedTier", "FastModePrefs.tier()", "FastModePrefs.setTier(")
        assertTrue(builtins.contains("SpeedTier.STANDARD to \"standard\""))
        assertTrue(builtins.contains("SpeedTier.FAST to \"fast\""))
        assertTrue(builtins.contains("SpeedTier.ULTRAFAST to \"ultrafast\""))
    }

    @Test
    fun `store-backed fields write through their store, not a private key`() {
        assertBinds("speech.volume", "state.setVolume(", "ConfigSchema.Double(min = 0.0, max = 2.0)")
        assertBinds("scheduled.showHomeBadge", "store.showHomeBadge()", "store.setShowHomeBadge(")
        assertBinds("updates.channel", "store.setChannel(", "ConfigRisk.SENSITIVE")
        assertBinds("updates.autoCheck", "store.setAutoEnabled(")
        assertBinds("backup.deviceName", "identity.setCustomName(", "identity.customName(")
        // A build without automatic checks keeps the path but refuses it.
        assertTrue(builtins.contains("UnavailableField(autoCheck"))
    }

    @Test
    fun `the idle timeout keeps the UI gate and range`() {
        val providers = source("config/collections/ProvidersCollection.kt")
        val reg = providers.substringAfter("path = \"providers.\$id.responseIdleTimeout\"").substringBefore("Write-only API key")
        assertTrue(reg.contains("setting.MIN_SECONDS") && reg.contains("setting.MAX_SECONDS"))
        assertTrue(reg.contains("ProviderType.openAI") && reg.contains("ProviderCredential.oauth"))
        assertTrue(reg.contains("setting.set(context, id, i)"))
    }

    @Test
    fun `model tuning exposes temperature and topP only`() {
        val models = source("config/collections/ModelsCollection.kt")
        assertTrue(models.contains("\"temperatureOverride\", \"Temperature\""))
        assertTrue(models.contains("\"topPOverride\", \"Top P\""))
        // Headers can carry credentials and raw body JSON can rewrite a request.
        assertFalse(Regex("""path = "models\.[^"]*(customHeaders|extraBodyParams)""").containsMatchIn(models))
        assertFalse(models.contains("\"customHeaders\","))
        assertFalse(models.contains("\"extraBodyParams\","))
    }

    @Test
    fun `no new field reads back a secret or a permission level`() {
        val added = listOf(
            "agentTools.userAsk.enabled", "agentTools.userAsk.timeoutMinutes", "agentTools.userAsk.notifyInBackground",
            "chat.tools.browserUse", "chat.tools.subAgents", "chat.autoExpandThinking", "appearance.appFontSize",
            "chat.autoGrouping", "chat.speedTier", "files.showHidden",
            "speech.volume", "scheduled.showHomeBadge", "updates.channel", "updates.autoCheck", "backup.deviceName",
        )
        for (p in added) {
            val reg = registration(p).lowercase()
            for (bad in listOf("apikey", "token", "password", "secret", "offload_permissions")) {
                assertFalse("$p touches `$bad`", reg.contains(bad))
            }
        }
    }

    // -- Sub agents: same surface as iOS --

    private val subAgents by lazy { source("config/collections/SubAgentsCollection.kt") }

    @Test
    fun `sub agent paths match the iOS collection`() {
        val ios = File("../../ios/Shared/Config/Collections/SubAgentsCollection.swift")
        assertTrue("iOS source not found: ${ios.absolutePath}", ios.isFile)
        val iosLeaves = Regex("""path: "subagents\.\\\(seg\)\.(\w+)"""").findAll(ios.readText()).map { it.groupValues[1] }.toSet()
        val androidLeaves = Regex("""path = "subagents\.\${'$'}seg\.(\w+)"""").findAll(subAgents).map { it.groupValues[1] }.toSet()
        assertTrue("iOS leaves: $iosLeaves", iosLeaves.size >= 6)
        assertEquals(iosLeaves, androidLeaves)
        assertTrue(subAgents.contains("path = \"subagents.order\""))
        assertTrue(subAgents.contains("path = \"subagents\""))
    }

    @Test
    fun `the built-in is addressed by the iOS alias and by its stored id`() {
        assertEquals("general", SubAgentsCollection.BUILT_IN_ALIAS)
        assertEquals("general", SubAgentsCollection.pathSegment(SubAgentDefinition.BUILT_IN_ID))
        assertEquals("abc-123", SubAgentsCollection.pathSegment("abc-123"))
        // The stored id has a dot; the path splitter keeps it whole.
        assertEquals(
            Triple("subagents", SubAgentDefinition.BUILT_IN_ID, "instructions"),
            ConfigRegistry.splitCollectionPath("subagents.${SubAgentDefinition.BUILT_IN_ID}.instructions"),
        )
    }

    @Test
    fun `the built-in refuses name, description and removal`() {
        assertTrue(subAgents.contains("if (isBuiltIn) throw builtInFieldDenied(\"name\")"))
        assertTrue(subAgents.contains("if (isBuiltIn) throw builtInFieldDenied(\"description\")"))
        assertTrue(Regex("""access = if \(isBuiltIn\) ConfigAccess\.READONLY""").findAll(subAgents).count() == 2)
        val remove = subAgents.substringAfter("override fun remove(id: String)").substringBefore("override val readableSubjectFormat")
        assertTrue(remove.contains("if (d.isBuiltIn)") && remove.contains("ConfigError.PermissionDenied"))
    }

    @Test
    fun `the roster is registered with its order and summary fields`() {
        assertTrue(builtins.contains("SubAgentsCollection(providerRepo)"))
        assertTrue(builtins.contains("SubAgentsCollection.orderField(providerRepo)"))
        assertTrue(builtins.contains("SubAgentsCollection.summaryField(providerRepo)"))
    }
}
