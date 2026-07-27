package com.anmi.devworkspace.markmap

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkmapWebViewProtocolTest {
    @Test
    fun `render script preserves base64 webview protocol`() {
        assertEquals(
            "window.updateMarkmapFromBase64('IyBUb3BpYw==', 'e30=')",
            MarkmapWebViewProtocol.renderScript("# Topic", "{}"),
        )
    }
}
