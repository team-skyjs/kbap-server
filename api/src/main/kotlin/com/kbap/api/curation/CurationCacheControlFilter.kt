package com.kbap.api.curation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class CurationCacheControlFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.requestURI.startsWith("/api/curations/seoul") || request.requestURI.startsWith("/api/admin/curations/seoul")) response.setHeader("Cache-Control", "no-store")
        chain.doFilter(request, response)
    }
}
