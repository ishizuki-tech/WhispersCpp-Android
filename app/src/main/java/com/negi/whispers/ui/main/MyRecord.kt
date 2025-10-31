// file: app/src/main/java/com/negi/whispers/ui/main/MyRecord.kt
package com.negi.whispers.ui.main

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents a single recorded item displayed in the UI list.
 *
 * @property logs         Textual logs shown in the list (includes messages, transcription, etc.)
 * @property absolutePath Absolute path to the WAV file on disk (may be empty for transient messages)
 *
 * Serialization:
 *  - Uses custom [MyRecordSerializer] for backward compatibility and robust decoding.
 *  - Avoids crashes when unknown fields or blank values appear in stored JSON.
 */
@Serializable(with = MyRecordSerializer::class)
data class MyRecord(
    var logs: String,
    val absolutePath: String
)

/**
 * Custom serializer for [MyRecord].
 *
 * Why custom:
 *  - Some older records may lack fields or contain null/blank values.
 *  - Provides graceful fallback to defaults rather than throwing exceptions.
 *  - Ensures forward compatibility with evolving schema.
 *
 * Implementation details:
 *  - Encodes exactly two elements: "logs" and "absolutePath".
 *  - During decoding, handles out-of-order fields and missing values safely.
 */
object MyRecordSerializer : KSerializer<MyRecord> {

    // Descriptor defines the schema structure for serialization
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MyRecord") {
        element<String>("logs")
        element<String>("absolutePath")
    }

    /**
     * Serializes [MyRecord] into a JSON object.
     */
    override fun serialize(encoder: Encoder, value: MyRecord) {
        val comp = encoder.beginStructure(descriptor)
        comp.encodeStringElement(descriptor, 0, value.logs)
        comp.encodeStringElement(descriptor, 1, value.absolutePath)
        comp.endStructure(descriptor)
    }

    /**
     * Deserializes JSON into [MyRecord] with defensive decoding.
     * - Unknown fields are skipped.
     * - Blank "logs" defaults to "(empty)".
     * - Blank "absolutePath" defaults to "".
     */
    override fun deserialize(decoder: Decoder): MyRecord {
        val dec = decoder.beginStructure(descriptor)
        var logs = ""
        var path = ""

        loop@ while (true) {
            when (val index = dec.decodeElementIndex(descriptor)) {
                0 -> logs = dec.decodeStringElement(descriptor, 0)
                1 -> path = dec.decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> {
                    // Safely skip unknown field indices
                    runCatching {
                        dec.decodeSerializableElement(descriptor, index, String.serializer())
                    }
                }
            }
        }

        dec.endStructure(descriptor)

        // Post-process defaults
        return MyRecord(
            logs = logs.ifBlank { "(empty)" },
            absolutePath = path.ifBlank { "" }
        )
    }
}
