package com.sendspindroid.sendspin.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Process-wide Moshi instance for protocol JSON parsing.
 *
 * Adapter resolution order:
 *  1. KSP-generated adapters for `@JsonClass(generateAdapter = true)` types
 *     (registered automatically via the generated `MoshiAdapter` companion).
 *  2. [JsonOptionalAdapterFactory] — three-state absent/null/value support.
 *  3. [KotlinJsonAdapterFactory] — reflection fallback for non-annotated types
 *     (e.g. `Map<String, Any?>` for envelope decoding).
 *
 * Held as a singleton: building a Moshi instance is moderately expensive
 * (~ms) and the per-message adapter cache is keyed by Moshi identity.
 */
internal object MoshiInstance {
    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(JsonOptionalAdapterFactory())
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * Generic `Map<String, Any?>` adapter used to decode the outer Sendspin
     * message envelope (`{type, payload}`). The decoded value is then handed
     * to a typed adapter via [JsonAdapter.fromJsonValue] for the specific
     * payload shape — no string round-trip.
     */
    val envelopeAdapter: JsonAdapter<Map<String, Any?>> by lazy {
        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java,
        )
        moshi.adapter(type)
    }
}
