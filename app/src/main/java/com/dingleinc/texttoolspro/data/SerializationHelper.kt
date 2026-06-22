package com.dingleinc.texttoolspro.data

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.InputStream

object SerializationHelper {

    val yamlMapper = ObjectMapper(YAMLFactory()).apply {
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        registerKotlinModule()
    }

    val jsonMapper = ObjectMapper().apply {
        propertyNamingStrategy = PropertyNamingStrategies.LOWER_CAMEL_CASE
        registerKotlinModule()
    }

    fun parseDictWrapperFromJson(stream: InputStream): DictWrapper {
        return jsonMapper.readValue(stream, DictWrapper::class.java)
    }

    fun parseDictWrapperFromYaml(stream: InputStream): DictWrapper {
        return yamlMapper.readValue(stream, DictWrapper::class.java)
    }

    fun parseDictWrapperFromYaml(text: String): DictWrapper {
        return yamlMapper.readValue(text, DictWrapper::class.java)
    }

    fun toJson(obj: Any): String {
        return jsonMapper.writeValueAsString(obj)
    }

    fun toYaml(obj: Any): String {
        return yamlMapper.writeValueAsString(obj)
    }
}
