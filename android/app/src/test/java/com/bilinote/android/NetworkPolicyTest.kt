package com.bilinote.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkPolicyTest {
    @Test
    fun applicationAllowsConfiguredHttpBackend() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val manifest = factory
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0)
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        assertEquals(
            "true",
            application.attributes.getNamedItemNS(androidNamespace, "usesCleartextTraffic")?.nodeValue,
            "Configured http:// backends must be allowed by the app network policy",
        )
    }
}
