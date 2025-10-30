// file: app/src/main/java/com/negi/whispers/ui/main/MyRecord.kt
package com.negi.whispers.ui.main

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = MyRecordSerializer::class)
data class MyRecord(
    var logs: String,
    val absolutePath: String
)

object MyRecordSerializer : KSerializer<MyRecord> {
    override val descriptor = buildClassSerialDescriptor("MyRecord") {
        element<String>("logs")
        element<String>("absolutePath")
    }

    override fun serialize(encoder: Encoder, value: MyRecord) {
        val comp = encoder.beginStructure(descriptor)
        comp.encodeStringElement(descriptor, 0, value.logs)
        comp.encodeStringElement(descriptor, 1, value.absolutePath)
        comp.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): MyRecord {
        val dec = decoder.beginStructure(descriptor)
        var logs = ""
        var path = ""
        loop@ while (true) {
            when (val index = dec.decodeElementIndex(descriptor)) {
                0 -> logs = dec.decodeStringElement(descriptor, 0)
                1 -> path = dec.decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> runCatching { dec.decodeSerializableElement(descriptor, index, String.serializer()) }
            }
        }
        dec.endStructure(descriptor)
        return MyRecord(
            logs = logs.ifBlank { "(empty)" },
            absolutePath = path.ifBlank { "" }
        )
    }
}
