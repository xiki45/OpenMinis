package com.openminis.app.provider

import com.openminis.app.data.model.LLMError

/**
 * [T-android-tls-trust-error] Recognises a TLS handshake that failed because the
 * server's certificate was not accepted, as opposed to a connection that merely
 * dropped during the handshake.
 *
 * Field report: after a while replies got slower and then failed with
 * "java.security.cert.CertPathValidatorException: Trust anchor for certification
 * path not found." That reached the user raw, and only after three same-model
 * retries (1 s, 2 s, 4 s), because every IOException - this one included - was
 * mapped to NetworkError, which is retryable. A certificate the device does not
 * trust is not transient: the same handshake fails the same way every time.
 *
 * The exception is matched on its cause chain, not its top-level type: OkHttp
 * surfaces all of these as SSLHandshakeException (or SSLPeerUnverifiedException
 * for a name mismatch) with the real reason underneath.
 */
object TlsFailure {

    enum class Kind {
        /**
         * The chain does not end at a trusted root: a proxy / VPN / packet-capture
         * tool or a corporate gateway re-signing traffic (user CAs are never
         * trusted for the official provider hosts, see network_security_config),
         * a self-signed endpoint, or a server that omits its intermediate
         * certificate (Android does not fetch missing intermediates).
         */
        UNTRUSTED,
        /** A certificate in the chain is expired or not yet valid - often a wrong device clock. */
        EXPIRED_OR_CLOCK,
        /** The certificate is valid but for another name: a captive portal or a hijacked connection. */
        HOSTNAME_MISMATCH,
    }

    fun classify(error: Throwable): Kind? {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(12).toList()
        if (chain.none { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) return null
        // Most specific first: an expired certificate also arrives wrapped in a
        // CertPathValidatorException.
        if (chain.any {
                it is java.security.cert.CertificateExpiredException ||
                    it is java.security.cert.CertificateNotYetValidException
            }
        ) return Kind.EXPIRED_OR_CLOCK
        if (chain.any { it is javax.net.ssl.SSLPeerUnverifiedException }) return Kind.HOSTNAME_MISMATCH
        if (chain.any {
                it is java.security.cert.CertPathValidatorException ||
                    it.message?.contains("Trust anchor for certification path not found") == true
            }
        ) return Kind.UNTRUSTED
        return null
    }

    /**
     * The provider-side mapping: a certificate failure becomes
     * [LLMError.CertificateError] naming the host, anything else returns null so
     * the caller keeps its NetworkError mapping. Logs once per failure so a
     * field log shows which host failed and why.
     */
    fun toLLMError(error: Throwable, baseUrl: String): LLMError? {
        val kind = classify(error) ?: return null
        val host = hostOf(baseUrl)
        val root = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(12).last()
        com.openminis.app.logging.AppLogger.warning(
            "TLS",
            "[TLS] certificate rejected host=$host kind=$kind sdk=${android.os.Build.VERSION.SDK_INT} " +
                "top=${error.javaClass.simpleName}: ${error.message} root=${root.javaClass.simpleName}: ${root.message}",
        )
        return LLMError.CertificateError(host, kind, error)
    }

    fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl
}
