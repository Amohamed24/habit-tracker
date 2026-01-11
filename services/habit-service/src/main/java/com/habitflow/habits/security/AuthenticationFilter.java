package com.habitflow.habits.security;

import com.habitflow.habits.client.AuthServiceClient;
import com.habitflow.habits.dto.UserValidationResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class AuthenticationFilter extends OncePerRequestFilter {
    
    private final AuthServiceClient authServiceClient;
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        // Skip authentication for health endpoint
        if (request.getRequestURI().equals("/api/habits/health")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Skip OPTIONS requests (CORS preflight)
        if (request.getMethod().equals("OPTIONS")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\": \"Missing or invalid Authorization header\"}");
            return;
        }
        
        String token = authHeader.substring(7);
        UserValidationResponse validation = authServiceClient.validateToken(token);
        
        if (!validation.isValid()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\": \"Invalid token\"}");
            return;
        }
        
        // Store user info in request attributes for later use
        request.setAttribute("userId", validation.getUserId());
        request.setAttribute("userEmail", validation.getEmail());
        
        filterChain.doFilter(request, response);
    }
}