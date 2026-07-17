package com.xmoyi.nainaisv.network

import java.security.MessageDigest
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl

class WbiSigner {
    fun mixinKey(imageKey: String, subKey: String): String {
        val source = imageKey + subKey
        return buildString {
            MIXIN_KEY_ENC_TAB.forEach { index -> source.getOrNull(index)?.let(::append) }
        }.take(32)
    }

    fun sign(
        baseUrl: String,
        parameters: Map<String, String>,
        imageKey: String,
        subKey: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        val mixin = mixinKey(imageKey, subKey)
        val cleaned = parameters
            .mapValues { (_, value) -> value.filterNot { it in "!'()*" } }
            .toMutableMap()
            .apply { put("wts", timestampSeconds.toString()) }
            .toSortedMap()

        val builder = baseUrl.toHttpUrl().newBuilder()
        cleaned.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val unsigned = builder.build()
        val query = unsigned.encodedQuery.orEmpty()
        val signature = md5(query + mixin)
        return unsigned.newBuilder().addQueryParameter("w_rid", signature).build().toString()
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { String.format(Locale.US, "%02x", it) }

    companion object {
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )
    }
}
