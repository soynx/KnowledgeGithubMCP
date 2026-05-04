package org.soynx.mcp.knowledgebase.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        String method  = request.getMethod();
        String uri     = request.getRequestURI();
        String query   = request.getQueryString();
        String remote  = request.getRemoteAddr();
        String fullUri = query != null ? uri + "?" + query : uri;

        boolean isHealthCheck = "/actuator/health".equals(uri);

        if (isHealthCheck) {
            log.debug("[REQUEST]  {} {} (from {})", method, fullUri, remote);
        } else {
            log.info("[REQUEST]  {} {} (from {})", method, fullUri, remote);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            if (isHealthCheck) {
                log.debug("[RESPONSE] {} {} → {} ({}ms)", method, fullUri, response.getStatus(), duration);
            } else {
                log.info("[RESPONSE] {} {} → {} ({}ms)", method, fullUri, response.getStatus(), duration);
            }
        }
    }
}
