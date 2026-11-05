package com.logicielpourtous.site.features.audit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AuthFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final AuditLogService auditLogService;

    public AuthFailureHandler(AuditLogService auditLogService) {
        super("/login?error");
        this.auditLogService = auditLogService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                         HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        String email     = request.getParameter("username");
        String ip        = resolveIp(request);
        String userAgent = request.getHeader("User-Agent");
        auditLogService.logLogin(null, email, ip, userAgent, false);
        super.onAuthenticationFailure(request, response, exception);
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
