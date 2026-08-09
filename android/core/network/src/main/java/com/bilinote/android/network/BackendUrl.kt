package com.bilinote.android.network

import java.net.URI

object BackendUrl {
    fun normalize(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Backend URL must not be blank" }

        val uri = try {
            URI(value)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid backend URL", exception)
        }

        require(uri.scheme?.equals("http", ignoreCase = true) == true ||
            uri.scheme?.equals("https", ignoreCase = true) == true) {
            "Backend URL must use http or https"
        }
        require(uri.host != null) { "Backend URL must include a host" }

        return value.trimEnd('/')
    }
}
