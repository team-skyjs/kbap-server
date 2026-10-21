package com.kbap.api.curation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import java.io.ByteArrayInputStream
import java.lang.reflect.Type

@Component
class CurationBodyLimitAdvice : RequestBodyAdviceAdapter(), BeanPostProcessor {
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean is RequestMappingHandlerAdapter) bean.setRequestBodyAdvice(listOf(this))
        return bean
    }
    override fun supports(parameter: MethodParameter, targetType: Type, converterType: Class<out HttpMessageConverter<*>>): Boolean = parameter.parameterType in setOf(CurationDraftRequest::class.java, CurationVersionRequest::class.java, CurationUploadRequest::class.java, CurationCompleteRequest::class.java)
    override fun beforeBodyRead(inputMessage: HttpInputMessage, parameter: MethodParameter, targetType: Type, converterType: Class<out HttpMessageConverter<*>>): HttpInputMessage {
        val bytes = inputMessage.body.readNBytes(1024 * 1024 + 1)
        if (bytes.size > 1024 * 1024) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val tree = runCatching { jacksonObjectMapper().readTree(bytes) }.getOrNull() ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        verifyNulls(tree, null)
        return object : HttpInputMessage {
            override fun getHeaders() = inputMessage.headers
            override fun getBody() = ByteArrayInputStream(bytes)
        }
    }
    private fun verifyNulls(node: JsonNode, field: String?) {
        if (node.isNull && field !in setOf("cover", "image", "editorialReview", "externalLinkReview", "attributionRequirement", "attributionText", "checkedAssetId", "expectedVersion")) throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (node.isObject) node.properties().forEach { verifyNulls(it.value, it.key) }
        if (node.isArray) node.forEach { verifyNulls(it, null) }
    }
}
