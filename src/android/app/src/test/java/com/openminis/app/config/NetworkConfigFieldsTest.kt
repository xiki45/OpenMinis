package com.openminis.app.config

import com.openminis.app.ProductionSources
import com.openminis.app.config.collections.ProxiesCollection
import com.openminis.app.config.fields.NetworkConfigFields
import com.openminis.app.config.fields.NetworkConfigRoute
import com.openminis.app.config.fields.NetworkConfigTailscale
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.network.ProxyServer
import com.openminis.app.network.TailscaleStatus
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [FEAT-261021-03] minis-config for Settings > Network, port of iOS
 * NetworkConfigFields (FEAT-261019-01): the paths, the route / exit-node
 * normalizers and validators, the status shape, the proxies add payload, and
 * the bridge running normalize + validate before the confirmation dialog.
 */
class NetworkConfigFieldsTest {

    private val envVars = EnvVarRepository(
        Files.createTempDirectory("envvars").toFile(),
        object : EnvVarRepository.ValueStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) {}
            override fun remove(key: String) {}
        },
    )

    private val home = ProxyServer(id = "P1", name = "Home", kind = "http", host = "10.0.0.2", port = 8080)
    private val office = ProxyServer(id = "P2", name = "", host = "proxy.example.com", port = 1080)
    private val proxies = listOf(home, office)

    private fun str(v: ConfigValue) = (v as ConfigValue.Str).value

    private fun rejected(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected a rejection containing '$contains'")
        } catch (e: ConfigError) {
            assertTrue("message: ${e.message}", e.message.orEmpty().contains(contains))
        }
    }

    @Test
    fun `the paths match iOS`() {
        val paths = NetworkConfigFields.build(envVars).map { it.path }.toSet()
        assertEquals(
            setOf(
                "network.routingEnabled", "network.defaultRoute",
                "network.routes.shell", "network.routes.browser", "network.routes.mcp", "network.routes.backup",
                "network.status",
                "network.tailscale.enabled", "network.tailscale.hostname", "network.tailscale.acceptRoutes",
                "network.tailscale.exitNodeDNS", "network.tailscale.exitNodeFailClosed",
                "network.tailscale.exitNode", "network.tailscale.loggedIn", "network.tailscale.authKey",
                "network.tailscale.signOutAndClear", "network.tailscale.status",
            ),
            paths,
        )
        assertEquals("proxies", ProxiesCollection(envVars).basePath)
        val providers = File(ProductionSources.mainRoot(), "config/collections/ProvidersCollection.kt").readText()
        assertTrue(providers.contains("path = \"providers.\$id.networkRoute\""))
        val builtins = File(ProductionSources.mainRoot(), "config/ConfigBuiltins.kt").readText()
        assertTrue(builtins.contains("NetworkConfigFields.build(envVarRepo)") && builtins.contains("ProxiesCollection(envVarRepo)"))
    }

    @Test
    fun `routes take keywords, a proxy id or name, and are stored as proxy id`() {
        fun n(s: String) = str(NetworkConfigRoute.normalize(ConfigValue.Str(s), proxies))
        assertEquals("default", n(" Follow Default "))
        assertEquals("direct", n("DIRECT"))
        assertEquals("tailscale", n("Tailscale"))
        assertEquals("proxy:P1", n("proxy:P1"))
        assertEquals("proxy:P1", n("home"))
        assertEquals("proxy:P1", n("proxy:Home"))
        assertEquals("proxy:P2", n("proxy.example.com:1080"))
        assertEquals("unknown text passes through for validate", "nowhere", n("nowhere"))
        // Two proxies with one name: ambiguous, not guessed.
        val twins = proxies + home.copy(id = "P3")
        assertEquals("Home", str(NetworkConfigRoute.normalize(ConfigValue.Str("Home"), twins)))
    }

    @Test
    fun `route validation names the valid choices`() {
        for (ok in listOf("direct", "tailscale", "proxy:P2")) NetworkConfigRoute.validate(ConfigValue.Str(ok), false, proxies)
        NetworkConfigRoute.validate(ConfigValue.Str("default"), allowFollow = true, proxies)
        rejected("use one of: direct, tailscale, proxy:P1 (Home)") {
            NetworkConfigRoute.validate(ConfigValue.Str("default"), allowFollow = false, proxies)
        }
        rejected("unknown route 'proxy:gone'; use one of: default, direct") {
            NetworkConfigRoute.validate(ConfigValue.Str("proxy:gone"), allowFollow = true, proxies)
        }
    }

    private val connected = TailscaleStatus.parse(
        JSONObject()
            .put("running", true).put("backendState", "Running").put("tailnet", "example.ts.net")
            .put("tailscaleIPs", JSONArray(listOf("100.1.1.1")))
            .put(
                "peers",
                JSONArray()
                    .put(JSONObject().put("hostName", "nas").put("dnsName", "nas.example.ts.net.").put("os", "linux")
                        .put("tailscaleIPs", JSONArray(listOf("100.2.2.2", "fd7a::2"))).put("online", true).put("exitNodeOption", true))
                    .put(JSONObject().put("hostName", "phone").put("tailscaleIPs", JSONArray(listOf("100.3.3.3")))),
            ),
    )

    @Test
    fun `an exit node is chosen by IP, name or MagicDNS name among offered exit nodes`() {
        fun n(s: String) = str(NetworkConfigTailscale.normalizeExitNode(ConfigValue.Str(s), connected))
        assertEquals("", n(" none "))
        assertEquals("100.2.2.2", n("NAS"))
        assertEquals("100.2.2.2", n("nas.example.ts.net"))
        assertEquals("100.2.2.2", n("fd7a::2"))
        assertEquals("not an exit node: unchanged", "phone", n("phone"))
        NetworkConfigTailscale.validateExitNode(ConfigValue.Str("100.2.2.2"), connected)
        NetworkConfigTailscale.validateExitNode(ConfigValue.Str(""), TailscaleStatus())
        rejected("choose one of: nas (100.2.2.2), or none") {
            NetworkConfigTailscale.validateExitNode(ConfigValue.Str("100.3.3.3"), connected)
        }
        rejected("Tailscale is not connected") {
            NetworkConfigTailscale.validateExitNode(ConfigValue.Str("100.2.2.2"), TailscaleStatus())
        }
    }

    @Test
    fun `status reports the state, the login link only while logged out, and the devices`() {
        fun state(wanted: Boolean, st: TailscaleStatus) =
            str((NetworkConfigTailscale.statusValue(wanted, st) as ConfigValue.Obj).value.getValue("state"))
        assertEquals("off", state(false, connected))
        assertEquals("connected", state(true, connected))
        assertEquals("starting", state(true, TailscaleStatus()))
        val needsLogin = TailscaleStatus(running = true, backendState = "NeedsLogin", authURL = "https://login.example/a")
        assertEquals("needsLogin", state(true, needsLogin))
        assertEquals("connecting", state(true, TailscaleStatus(running = true, backendState = "Starting")))
        val out = (NetworkConfigTailscale.statusValue(true, needsLogin) as ConfigValue.Obj).value
        assertEquals("https://login.example/a", str(out.getValue("loginURL")))
        val connectedOut = (NetworkConfigTailscale.statusValue(true, connected) as ConfigValue.Obj).value
        assertFalse(connectedOut.containsKey("loginURL"))
        val nas = ((connectedOut.getValue("devices") as ConfigValue.Arr).value.first() as ConfigValue.Obj).value
        assertEquals("nas", str(nas.getValue("name")))
        assertEquals(ConfigValue.Bool(true), nas["exitNodeOption"])
    }

    @Test
    fun `a proxy add payload fills defaults and refuses bad input`() {
        fun obj(vararg kv: Pair<String, ConfigValue>) = ConfigValue.Obj(mapOf(*kv))
        val p = ProxiesCollection.proxyFromPayload(
            obj("type" to ConfigValue.Str("HTTP"), "host" to ConfigValue.Str(" 10.0.0.9 "),
                "bypass" to ConfigValue.Str("*.lan, 192.168.0.0/16\nexample.com")),
        )
        assertEquals("http", p.kind)
        assertEquals("10.0.0.9", p.host)
        assertEquals(8080, p.port)
        assertEquals(listOf("*.lan", "192.168.0.0/16", "example.com"), p.bypass)
        assertEquals(1080, ProxiesCollection.proxyFromPayload(obj("host" to ConfigValue.Str("h"))).port)
        rejected("host is required") { ProxiesCollection.proxyFromPayload(obj("type" to ConfigValue.Str("socks5"))) }
        rejected("type must be one of") { ProxiesCollection.proxyFromPayload(obj("type" to ConfigValue.Str("ftp"), "host" to ConfigValue.Str("h"))) }
        rejected("port must be an integer 1-65535") {
            ProxiesCollection.proxyFromPayload(obj("host" to ConfigValue.Str("h"), "port" to ConfigValue.Int(70000)))
        }
        assertEquals(
            ConfigValue.Arr(listOf(ConfigValue.Str("a"), ConfigValue.Str("b"))),
            ProxiesCollection.normalizeBypass(ConfigValue.Arr(listOf(ConfigValue.Str(" a "), ConfigValue.Str(""), ConfigValue.Str("b")))),
        )
    }

    @Test
    fun `proxy passwords and the auth key are masked like API keys`() {
        assertTrue(ConfigValue.SECRET_KEYS.containsAll(listOf("password", "authKey", "apiKey")))
        val masked = ConfigValue.Obj(mapOf("host" to ConfigValue.Str("h"), "password" to ConfigValue.Str("s3cret"))).redactingSecrets()
        assertFalse(masked.toString().contains("s3cret"))
        // Neither write-only field can be read back.
        for (path in listOf("network.tailscale.authKey")) {
            val f = NetworkConfigFields.build(envVars).single { it.path == path }
            rejected("permission_denied") { f.read() }
        }
    }

    @Test
    fun `the bridge normalizes and validates a set before the confirmation dialog`() {
        val bridge = File(ProductionSources.mainRoot(), "config/ConfigBridge.kt").readText()
        val set = bridge.substringAfter("// [FEAT-261021-03] normalize -> schema -> field checks")
        val block = set.substringBefore("newValue = normalized")
        assertTrue(block.contains("field.normalize(parsedValue)"))
        assertTrue(block.indexOf("field.valueSchema.validate(it)") < block.indexOf("field.validate(it)"))
        // The confirmation is requested after the resolve loop that holds this block.
        assertTrue(bridge.indexOf("newValue = normalized") < bridge.indexOf("ConfigConfirmationGate", bridge.indexOf("newValue = normalized")))
    }
}
