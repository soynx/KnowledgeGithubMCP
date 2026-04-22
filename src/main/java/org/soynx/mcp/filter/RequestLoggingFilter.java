package org.soynx.mcp.filter;

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

        log.info("[REQUEST]  {} {} (from {})", method, fullUri, remote);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("[RESPONSE] {} {} → {} ({}ms)", method, fullUri, response.getStatus(), duration);
        }
    }
}
