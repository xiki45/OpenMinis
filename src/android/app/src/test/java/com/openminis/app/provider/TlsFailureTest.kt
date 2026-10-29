package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.TlsFailure.Kind
import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * [T-android-tls-trust-error] A rejected certificate is named, explained and not
 * retried; a connection that merely dropped mid-handshake stays a network error.
 */
class TlsFailureTest {

    private fun handshake(cause: Throwable) =
        SSLHandshakeException("java.security.cert.CertPathValidatorException: ${cause.message}").apply { initCause(cause) }

    @Test fun `the field report's trust-anchor failure is UNTRUSTED`() {
        // OkHttp / Conscrypt shape: SSLHandshakeException <- CertificateException <- CertPathValidatorException.
        val e = handshake(CertificateException(CertPathValidatorException("Trust anchor for certification path not found.")))
        assertEquals(Kind.UNTRUSTED, TlsFailure.classify(e))
        // Matched on the message too, for a platform that wraps it differently.
        assertEquals(Kind.UNTRUSTED, TlsFailure.classify(SSLHandshakeException("Trust anchor for certification path not found.")))
    }

    @Test fun `an expired certificate points at the clock, even wrapped in a path failure`() {
        val e = handshake(CertificateException(CertPathValidatorException("timestamp check failed", CertificateExpiredException("expired"))))
        assertEquals(Kind.EXPIRED_OR_CLOCK, TlsFailure.classify(e))
    }

    @Test fun `a certificate for another name is a mismatch`() {
        assertEquals(Kind.HOSTNAME_MISMATCH, TlsFailure.classify(SSLPeerUnverifiedException("Hostname api.example.com not verified")))
    }

    @Test fun `a dropped handshake or any other IO failure is not a certificate problem`() {
        assertNull(TlsFailure.classify(SSLHandshakeException("Connection reset by peer").apply { initCause(java.net.SocketException("reset")) }))
        assertNull(TlsFailure.classify(java.net.SocketTimeoutException("timeout")))
        assertNull(TlsFailure.classify(java.net.UnknownHostException("api.openai.com")))
        assertNull(TlsFailure.classify(IllegalStateException("x")))
    }

    @Test fun `host is taken from the endpoint`() {
        assertEquals("relay.example.cn", TlsFailure.hostOf("https://relay.example.cn:8443/v1"))
        assertEquals("api.openai.com", TlsFailure.hostOf(" https://api.openai.com/v1 "))
        assertEquals("not a url", TlsFailure.hostOf("not a url"))
    }

    @Test fun `the error is not retried on the same model, may fall back, never splits`() {
        val err = LLMError.CertificateError("relay.example.cn", Kind.UNTRUSTED, SSLHandshakeException("Trust anchor for certification path not found."))
        assertFalse("1-2-4 s retries cannot fix a certificate", err.isRetryable)
        assertFalse(err.isNetworkError)
        assertTrue("another group member is usually another host", err.isFallbackable)
        assertFalse(ChatViewModel.shouldSplitOnError(err))
        // The message names the host, says what to check, keeps the raw reason.
        val msg = err.message!!
        assertTrue(msg.contains("relay.example.cn"))
        assertTrue(msg.contains("proxy"))
        assertTrue(msg.contains("Trust anchor for certification path not found."))
        assertEquals("Certificate not trusted (relay.example.cn)", err.fallbackReason)
    }

    @Test fun `every kind has localized text and an English fallback`() {
        for (kind in Kind.values()) assertTrue(com.openminis.app.data.model.CertificateErrorText.english(kind, "h.example").contains("h.example"))
        // mainRoot() = src/main/java/com/openminis/app -> src/main/res
        val res = ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile.resolve("res")
        for (locale in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = res.resolve("$locale/strings.xml").readText()
            for (key in listOf("error_tls_untrusted", "error_tls_expired_or_clock", "error_tls_hostname_mismatch")) {
                assertTrue("$locale/$key", Regex("""<string name="$key">[^<]*%1\${'$'}s[^<]*</string>""").containsMatchIn(xml))
            }
        }
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("com.openminis.app.data.model.CertificateErrorText.resolver = { kind, host ->"))
    }

    @Test fun `every provider maps a certificate failure before the generic network error`() {
        for (path in listOf(
            "provider/openai/OpenAIProvider.kt",
            "provider/anthropic/AnthropicProvider.kt",
            "provider/gemini/GeminiProvider.kt",
        )) {
            val map = ProductionSources.read(path).substringAfter("private fun mapError(error: Throwable): LLMError {").substringBefore("\n    }")
            val tls = map.indexOf("TlsFailure.toLLMError(error, basePath)")
            assertTrue(path, tls >= 0 && tls < map.indexOf("LLMError.NetworkError(error)"))
        }
        // The same-model retry gate stays keyed on the two transient classes.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val isTransient = actual is com.openminis.app.data.model.LLMError.NetworkError ||\n" +
            "                        actual is com.openminis.app.data.model.LLMError.TransientError"))
    }
}
