package com.notifyhub.utils

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

object NotificationMetadata {

    private const val MAX_KEYS = 120
    private const val MAX_ITEMS = 100
    private const val MAX_VALUE_CHARS = 2500
    private const val MAX_DEPTH = 4

    fun conversationTitle(extras: Bundle?): String? =
        extras?.getCharSequence("android.conversationTitle")?.toString()

    fun serialize(extras: Bundle?): String? {
        if (extras == null) return null
        return try {
            val output = JSONObject()
            val keys = extras.keySet().sorted().take(MAX_KEYS)
            for (key in keys) {
                putValue(output, key, extras.get(key), 0)
            }
            output.toString().take(80_000)
        } catch (_: Exception) {
            null
        }
    }

    private fun putValue(output: JSONObject, key: String, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (value) {
            null -> output.put(key, JSONObject.NULL)
            is CharSequence -> output.put(key, value.toString().take(MAX_VALUE_CHARS))
            is String -> output.put(key, value.take(MAX_VALUE_CHARS))
            is Boolean, is Number -> output.put(key, value)
            is Bundle -> {
                val nested = JSONObject()
                for (nestedKey in value.keySet().sorted().take(MAX_KEYS)) {
                    putValue(nested, nestedKey, value.get(nestedKey), depth + 1)
                }
                output.put(key, nested)
            }
            is Array<*> -> {
                val array = JSONArray()
                for (item in value.take(MAX_ITEMS)) putValue(array, item, depth + 1)
                output.put(key, array)
            }
            is IntArray -> output.put(key, JSONArray(value.take(MAX_ITEMS)))
            is LongArray -> output.put(key, JSONArray(value.take(MAX_ITEMS)))
            is FloatArray -> output.put(key, JSONArray(value.take(MAX_ITEMS)))
            is DoubleArray -> output.put(key, JSONArray(value.take(MAX_ITEMS)))
            is BooleanArray -> output.put(key, JSONArray(value.take(MAX_ITEMS)))
            is ShortArray -> output.put(key, JSONArray(value.take(MAX_ITEMS)))
            is ByteArray -> Unit
            else -> Unit
        }
    }

    private fun putValue(array: JSONArray, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (value) {
            null -> array.put(JSONObject.NULL)
            is CharSequence -> array.put(value.toString().take(MAX_VALUE_CHARS))
            is String -> array.put(value.take(MAX_VALUE_CHARS))
            is Boolean, is Number -> array.put(value)
            is Bundle -> {
                val nested = JSONObject()
                for (key in value.keySet().sorted().take(MAX_KEYS)) {
                    putValue(nested, key, value.get(key), depth + 1)
                }
                array.put(nested)
            }
            is Array<*> -> {
                val nested = JSONArray()
                for (item in value.take(MAX_ITEMS)) putValue(nested, item, depth + 1)
                array.put(nested)
            }
            else -> Unit
        }
    }
}
