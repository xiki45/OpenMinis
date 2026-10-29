package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.ProxyServer

/**
 * [FEAT-261021-03] `proxies.<id>.*` - Settings > Network > Proxies (port of
 * iOS ProxiesCollection, FEAT-261019-01). Passwords go to the encrypted store
 * through NetworkHub.saveProxy and are write-only here, like provider API
 * keys; `password` is in ConfigValue.SECRET_KEYS, so an add payload and a
 * password write are masked in the confirmation dialog and the audit log.
 */
class ProxiesCollection(private val envVars: EnvVarRepository) : ConfigCollection {
    override val basePath: String get() = "proxies"
    override val displayName: String get() = "Proxies"
    override val description: String get() =
        "Upstream HTTP, HTTPS or SOCKS5 proxies (Settings › Network › Proxies). Use one as a route with proxy:<id> in " +
            "network.defaultRoute, network.routes.<scope> or providers.<id>.networkRoute. Removing a proxy sends routes " +
            "that used it back to direct / default."
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    /** {"type": "socks5|http|https", "host": "…", "port": 1080, "name"?, "username"?, "password"?, "bypass"?: […]} */
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> = NetworkHub.settings.value.proxies.map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        if (proxy(forId) == null) return emptyList()
        val id = forId
        return listOf(
            stringField(id, "name", "Name", "Shown in route pickers; empty = host:port.", 100,
                ConfigReadable(ConfigValueFormatter.Text(empty = "(empty)")), { it.name }) { p, s -> p.copy(name = s) },
            ClosureField(
                path = "proxies.$id.type",
                displayName = "Type",
                description = "socks5, http or https.",
                valueSchema = ConfigSchema.StrEnum(ProxyServer.KINDS),
                risk = ConfigRisk.SENSITIVE,
                readable = ConfigReadable(
                    ConfigValueFormatter.EnumLabels(mapOf("socks5" to "SOCKS5", "http" to "HTTP", "https" to "HTTPS")),
                ),
                reader = { proxy(id)?.let { ConfigValue.Str(it.kind) } ?: ConfigValue.Null },
                writer = { v -> mutate(id) { it.copy(kind = (v as ConfigValue.Str).value) } },
            ),
            stringField(id, "host", "Server", "Proxy host name or IP address.", 253,
                ConfigReadable(ConfigValueFormatter.Text()), { it.host }) { p, s -> p.copy(host = s) },
            ClosureField(
                path = "proxies.$id.port",
                displayName = "Port",
                description = "1-65535.",
                valueSchema = ConfigSchema.Int(min = 1, max = 65535),
                risk = ConfigRisk.SENSITIVE,
                readable = ConfigReadable(ConfigValueFormatter.Number()),
                reader = { proxy(id)?.let { ConfigValue.Int(it.port) } ?: ConfigValue.Null },
                writer = { v -> mutate(id) { it.copy(port = (v as ConfigValue.Int).value) } },
            ),
            stringField(id, "username", "Username", "Empty = no authentication.", 256,
                ConfigReadable(ConfigValueFormatter.Text(empty = "(empty)")), { it.username }) { p, s -> p.copy(username = s) },
            ClosureField(
                path = "proxies.$id.password",
                displayName = "Password",
                description = "Write-only. The proxy password (or a `\$\$ENV_VAR` reference); empty clears it. Kept in " +
                    "the encrypted store; cannot be read back.",
                valueSchema = ConfigSchema.Str(maxLength = 1000),
                risk = ConfigRisk.SENSITIVE,
                revertable = false,
                readable = ConfigReadable(ConfigValueFormatter.Secret),
                reader = { throw ConfigError.PermissionDenied("Proxy passwords are kept encrypted and never exposed to minis-config") },
                writer = { v ->
                    val raw = (v as ConfigValue.Str).value
                    val p = proxy(id) ?: throw ConfigError.UnknownPath("proxies.$id")
                    NetworkHub.saveProxy(p, if (raw.isEmpty()) "" else resolveEnvReference(envVars, raw, "password"))
                },
            ),
            ClosureField(
                path = "proxies.$id.bypass",
                displayName = "Bypass Hosts",
                description = "Hosts that connect directly instead of through this proxy: exact host, *.example.com (also " +
                    "matches subdomains), CIDR or IP. Accepts a JSON array or one comma/newline-separated list; " +
                    ".append/.remove take one entry. Local addresses always bypass the proxy.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str(maxLength = 253)),
                risk = ConfigRisk.NORMAL,
                readable = ConfigReadable(ConfigValueFormatter.StringList(ordered = false)),
                reader = { proxy(id)?.let { p -> ConfigValue.Arr(p.bypass.map { ConfigValue.Str(it) }) } ?: ConfigValue.Null },
                writer = { v ->
                    val list = (v as ConfigValue.Arr).value.mapNotNull { (it as? ConfigValue.Str)?.value }
                    mutate(id) { it.copy(bypass = list) }
                },
                normalizer = ::normalizeBypass,
            ),
        )
    }

    override fun add(payload: ConfigValue): String {
        val p = proxyFromPayload(payload)
        val password = ((payload as ConfigValue.Obj).value["password"] as? ConfigValue.Str)?.value?.trim()
            ?.let { resolveEnvReference(envVars, it, "password") }
        NetworkHub.saveProxy(p, password)
        return p.id
    }

    override fun remove(id: String) {
        if (proxy(id) == null) throw ConfigError.UnknownPath("proxies.$id")
        NetworkHub.deleteProxy(id)
    }

    // -- Readable descriptions --

    override val readableSubjectFormat: String get() = "Proxy “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = proxy(id)?.displayName

    /** The payload is already masked: a password shows only as given or not. */
    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        fun text(key: String) = (d[key] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }
        val port = (d["port"] as? ConfigValue.Int)?.value?.toString().orEmpty()
        val kind = ProxyServer.kindLabel(text("type")?.lowercase() ?: "socks5")
        val server = if (port.isEmpty()) text("host") ?: "?" else "${text("host") ?: "?"}:$port"
        val lines = mutableListOf(
            ReadableLine("Add %1\$@ proxy %2\$@", listOf(ReadableArg.Text(kind), ReadableArg.Text(text("name") ?: server))),
        )
        if (text("password") != null) lines.add(ReadableLine("Password provided (value hidden)"))
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val p = proxy(id) ?: return emptyList()
        return listOf(ReadableLine("Delete proxy “%@”; routes that used it connect directly", listOf(ReadableArg.Text(p.displayName))))
    }

    // -- Helpers --

    private fun proxy(id: String): ProxyServer? = NetworkHub.settings.value.proxies.firstOrNull { it.id == id }

    /** Saves through the hub, keeping the stored password (null = untouched). */
    private fun mutate(id: String, change: (ProxyServer) -> ProxyServer) {
        val p = proxy(id) ?: throw ConfigError.UnknownPath("proxies.$id")
        NetworkHub.saveProxy(change(p), null)
    }

    private fun stringField(
        id: String,
        leaf: String,
        displayName: String,
        description: String,
        max: Int,
        readable: ConfigReadable,
        get: (ProxyServer) -> String,
        set: (ProxyServer, String) -> ProxyServer,
    ): ConfigField = ClosureField(
        path = "proxies.$id.$leaf",
        displayName = displayName,
        description = description,
        valueSchema = ConfigSchema.Str(maxLength = max),
        risk = ConfigRisk.SENSITIVE,
        readable = readable,
        reader = { proxy(id)?.let { ConfigValue.Str(get(it)) } ?: ConfigValue.Null },
        writer = { v -> mutate(id) { p -> set(p, (v as ConfigValue.Str).value) } },
        normalizer = { v -> if (v is ConfigValue.Str) ConfigValue.Str(v.value.trim()) else v },
        validator = { v ->
            if (leaf == "host" && (v as? ConfigValue.Str)?.value.isNullOrEmpty()) {
                throw ConfigError.InvalidValue("host cannot be empty")
            }
        },
    )

    companion object {
        /** A proxy from an `add` payload (pure; the password is handled by [add]). */
        internal fun proxyFromPayload(payload: ConfigValue): ProxyServer {
            val d = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.TypeMismatch("object, e.g. {\"type\":\"socks5\",\"host\":\"10.0.0.2\",\"port\":1080}")
            fun string(key: String): String? = when (val v = d[key]) {
                null, ConfigValue.Null -> null
                is ConfigValue.Str -> v.value.trim()
                else -> throw ConfigError.InvalidValue("$key must be a string")
            }
            val kind = string("type")?.lowercase()?.also {
                if (it !in ProxyServer.KINDS) throw ConfigError.InvalidValue("type must be one of: socks5, http, https")
            } ?: "socks5"
            val host = string("host")?.takeIf { it.isNotEmpty() } ?: throw ConfigError.InvalidValue("host is required")
            val port = when (val v = d["port"]) {
                null, ConfigValue.Null -> ProxyServer.defaultPort(kind)
                is ConfigValue.Int -> v.value.takeIf { it in 1..65535 }
                    ?: throw ConfigError.InvalidValue("port must be an integer 1-65535")
                else -> throw ConfigError.InvalidValue("port must be an integer 1-65535")
            }
            val bypass = when (val v = d["bypass"]) {
                null, ConfigValue.Null -> emptyList()
                is ConfigValue.Str -> splitList(v.value)
                is ConfigValue.Arr -> v.value.mapNotNull { (it as? ConfigValue.Str)?.value?.trim() }.filter { it.isNotEmpty() }
                else -> throw ConfigError.InvalidValue("bypass must be an array of strings or a comma-separated string")
            }
            return ProxyServer(
                name = string("name").orEmpty(),
                kind = kind,
                host = host,
                port = port,
                username = string("username").orEmpty(),
                bypass = bypass,
            )
        }

        /** A comma/newline-separated string or an array -> trimmed, non-empty entries. */
        internal fun normalizeBypass(v: ConfigValue): ConfigValue = when (v) {
            is ConfigValue.Str -> ConfigValue.Arr(splitList(v.value).map { ConfigValue.Str(it) })
            is ConfigValue.Arr -> ConfigValue.Arr(
                v.value.map { item ->
                    ConfigValue.Str((item as? ConfigValue.Str)?.value?.trim() ?: throw ConfigError.TypeMismatch("string"))
                }.filter { it.value.isNotEmpty() },
            )
            else -> throw ConfigError.TypeMismatch("array of strings, or a comma/newline-separated string")
        }

        private fun splitList(s: String): List<String> =
            s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
