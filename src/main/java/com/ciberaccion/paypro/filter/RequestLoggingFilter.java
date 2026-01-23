package com.ciberaccion.paypro.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
                                
        log.info("Incoming request: method={}, uri={}, remoteAddr={}",
                 request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
                 //System.out.println("request logging filter executed");

        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - start;
        log.info("Completed request: uri={}, status={}, duration={}ms",
                 request.getRequestURI(), response.getStatus(), duration);

    }

}
