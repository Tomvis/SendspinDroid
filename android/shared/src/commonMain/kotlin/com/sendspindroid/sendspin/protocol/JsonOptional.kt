package com.sendspindroid.sendspin.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Three-state container for JSON fields whose absent / present-null / present-value
 * cases each carry semantic weight in the Sendspin protocol.
 *
 * The server emits diff-style `server/state` updates: only the fields that changed
 * appear in the payload. To merge such updates correctly we need to tell three
 * cases apart:
 *
 *  - [Absent]                    — field omitted from the JSON object;
 *                                   inherit from the previous accumulated value.
 *  - [Present] with `value=null` — field present but JSON-null;
 *                                   reset to the type default (clear).
 *  - [Present] with `value=T`    — field present with a concrete value.
 *
 * Plain `T?` collapses Absent and Present(null) together; that is why the
 * previous parser ad-hoc-checked `key in jsonObject` plus the literal string
 * "null" sentinel.
 *
 * Pair with [JsonOptionalAdapterFactory] when configuring Moshi:
 * ```
 * Moshi.Builder()
 *     .add(JsonOptionalAdapterFactory())
 *     .addLast(KotlinJsonAdapterFactory())
 *     .build()
 * ```
 *
 * Use [orElse] to pick a fallback when the field is absent but present-null is a
 * legitimate "clear" signal you want to act on.
 */
sealed class JsonOptional<out T> {
    object Absent : JsonOptional<Nothing>()
    data class Present<out T>(val value: T?) : JsonOptional<T>()
}

/**
 * Returns the present value (which may be null) when this is [JsonOptional.Present],
 * or [fallback] when this is [JsonOptional.Absent].
 *
 * Common pattern for non-nullable fields where present-null means "clear to empty":
 * ```
 * val title: String = jsonOptional.orElse(previousTitle) ?: ""
 * ```
 */
fun <T> JsonOptional<T>.orElse(fallback: T?): T? = when (this) {
    is JsonOptional.Absent -> fallback
    is JsonOptional.Present -> value
}

/**
 * Moshi adapter factory for [JsonOptional].
 *
 * Moshi calls into this factory only when the corresponding JSON key is present
 * in the object; when the key is absent Moshi uses the Kotlin default value
 * [JsonOptional.Absent], which the parser will see as "field not sent."
 */
class JsonOptionalAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (type !is ParameterizedType) return null
        if (type.rawType != JsonOptional::class.java) return null
        val innerType = type.actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        val inner = moshi.adapter<Any?>(innerType) as JsonAdapter<Any?>
        return object : JsonAdapter<JsonOptional<Any?>>() {
            override fun fromJson(reader: JsonReader): JsonOptional<Any?> =
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<Any?>()
                    JsonOptional.Present(null)
                } else {
                    JsonOptional.Present(inner.fromJson(reader))
                }

            override fun toJson(writer: JsonWriter, value: JsonOptional<Any?>?) {
                when (value) {
                    null, JsonOptional.Absent -> writer.nullValue()
                    is JsonOptional.Present -> inner.toJson(writer, value.value)
                }
            }
        }
    }
}
