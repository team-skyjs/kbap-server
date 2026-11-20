package com.kbap.api.core.observability

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiVersions
import io.micrometer.common.KeyValues
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.stereotype.Component

@Component
class ApiVersionObservationConvention : DefaultServerRequestObservationConvention() {
    override fun getLowCardinalityKeyValues(context: ServerRequestObservationContext): KeyValues {
        val header = context.carrier?.getHeader(ApiHeaders.API_VERSION)
        return super.getLowCardinalityKeyValues(context).and("api_version", ApiVersions.metricLabel(header))
    }
}
