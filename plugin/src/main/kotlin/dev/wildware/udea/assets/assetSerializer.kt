package dev.wildware.udea.assets

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

//class AssetReferenceSerializer : StdSerializer<AssetRefence<*>>(AssetRefence::class.java) {
//    override fun serialize(reference: AssetRefence<*>, jg: JsonGenerator, ctxt: SerializerProvider) {
//        jg.writeString(reference.value.path)
//    }
//}
//
//class AssetReferenceDeserializer(
//    val project: Project,
//) : StdDeserializer<AssetRefence<*>>(AssetRefence::class.java) {
//    override fun deserialize(j: JsonParser, ctxt: DeserializationContext): AssetRefence<*> {
//        val path = j.valueAsString
//        return AssetRefence(project.service<AssetManager>().getAsset(path))
//    }
//}
