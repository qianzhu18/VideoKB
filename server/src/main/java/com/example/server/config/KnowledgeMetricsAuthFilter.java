package com.example.server.config;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Actuator uses its own handler mapping, so MVC business interceptors do not protect it. */
@Component
public class KnowledgeMetricsAuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;
    public KnowledgeMetricsAuthFilter(AuthService auth, ObjectMapper json) { this.auth = auth; this.json = json; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/actuator") || path.equals("/actuator/health");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long userId;
        try { userId = auth.resolveUser(request.getHeader("Authorization")); }
        catch (SecurityException e) { error(response, 401, ErrorCode.UNAUTHORIZED, "请先登录"); return; }
        try { auth.requireAdmin(userId); }
        catch (SecurityException e) { error(response, 403, ErrorCode.FORBIDDEN, "仅管理员可查看系统监测数据"); return; }
        chain.doFilter(request, response);
    }
    private void error(HttpServletResponse response, int status, ErrorCode code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), Result.error(code, message));
    }
}
