package com.kbap.common.infra.push

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.http.HttpClient
import java.time.Duration

internal fun expoRestClientBuilder(): RestClient.Builder {
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(10)) }
    return RestClient.builder().requestFactory(requestFactory)
}

internal fun expoRestClient(baseUrl: String, accessToken: String, builder: RestClient.Builder): RestClient {
    val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    builder
        .baseUrl(baseUrl)
        .configureMessageConverters { it.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(mapper)) }
    if (accessToken.isNotBlank()) {
        builder.defaultHeaders { it.setBearerAuth(accessToken) }
    }
    return builder.build()
}
