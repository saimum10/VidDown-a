package com.viddown.app

import android.content.Context
import org.json.JSONObject

/** The pinned SHA-256 values for the packaged native files, generated at build time from native/bundle.lock.json. */
class NativeManifest(private val files: Map<String, Map<String, String>>) {
    fun hashesFor(abi: String): Map<String, String> =
        files[abi] ?: throw IllegalStateException("native-manifest.json has no entry for ABI $abi")

    companion object {
        fun load(context: Context): NativeManifest {
            val text = context.assets.open("native-manifest.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val result = LinkedHashMap<String, Map<String, String>>()
            val abis = root.keys()
            while (abis.hasNext()) {
                val abi = abis.next()
                val entry = root.getJSONObject(abi)
                val map = LinkedHashMap<String, String>()
                val names = entry.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    map[name] = entry.getString(name)
                }
                result[abi] = map
            }
            return NativeManifest(result)
        }
    }
}
