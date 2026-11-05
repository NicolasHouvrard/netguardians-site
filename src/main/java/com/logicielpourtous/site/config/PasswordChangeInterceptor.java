package com.logicielpourtous.site.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();
        if (uri.startsWith("/change-password") || uri.startsWith("/logout") || uri.startsWith("/error")) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("MUST_CHANGE_PASSWORD"))) {
            response.sendRedirect(request.getContextPath() + "/change-password");
            return false;
        }
        return true;
    }
}
