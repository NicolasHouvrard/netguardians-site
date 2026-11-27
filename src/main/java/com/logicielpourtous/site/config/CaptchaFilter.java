package com.logicielpourtous.site.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class CaptchaFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED = Set.of("/login", "/register", "/reset-password");

    private final CaptchaService captchaService;

    public CaptchaFilter(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PROTECTED.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token    = request.getParameter("cf-turnstile-response");
        String remoteIp = resolveIp(request);

        if (!captchaService.verify(token, remoteIp)) {
            // Formulaire de secours : retour sur sa page (choix binaire, la valeur du champ n'est pas reflechie).
            boolean secours = "/login".equals(request.getServletPath()) && "1".equals(request.getParameter("secours"));
            String redirect = (secours ? "/login/secours" : request.getServletPath()) + "?error=captcha";
            response.sendRedirect(redirect);
            return;
        }
        chain.doFilter(request, response);
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
