package com.assistant.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSchemeTest {
    @Test fun wsAndHttpMappingStripsStaleEndpointPaths() {
        assertEquals("ws://192.168.0.200:80/api/orchestrator/chat", UrlScheme.wsUrl("ws://192.168.0.200:80", WsEndpoint.ORCHESTRATOR))
        assertEquals("ws://192.168.0.200:80/api/sessions/chat", UrlScheme.wsUrl("http://192.168.0.200:80/", WsEndpoint.AGENT))
        assertEquals("wss://h/api/sessions/chat", UrlScheme.wsUrl("https://h/api/orchestrator/chat", WsEndpoint.AGENT))
        assertEquals("http://192.168.0.200:80", UrlScheme.httpBase("ws://192.168.0.200:80/api/orchestrator/chat"))
        assertEquals("https://h:8443", UrlScheme.httpBase("wss://h:8443/api/orchestrator"))
        assertEquals("http://10.0.0.5:8765", UrlScheme.httpBase("10.0.0.5:8765"))
    }

    @Test fun hostPortAndSecurity() {
        assertEquals("192.168.0.200:443", UrlScheme.hostPort("wss://192.168.0.200"))
        assertEquals("192.168.0.200:80", UrlScheme.hostPort("ws://192.168.0.200/api/sessions/chat"))
        assertEquals("h:8443", UrlScheme.hostPort("https://h:8443"))
        assertTrue(UrlScheme.isSecure("wss://x")); assertFalse(UrlScheme.isSecure("ws://x"))
    }
}
