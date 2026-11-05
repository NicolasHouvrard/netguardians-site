package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

/**
 * Principal OAuth2 (non-OIDC, ex: Microsoft) qui étend CustomUserDetails.
 * Tous les @AuthenticationPrincipal CustomUserDetails continuent de fonctionner.
 */
public class CustomOAuth2User extends CustomUserDetails implements OAuth2User {

    private static final long serialVersionUID = 1L;
    private final OAuth2User delegate;

    public CustomOAuth2User(OAuth2User delegate, User user, String email) {
        super(user);
        this.delegate = delegate;
    }

    @Override public String getName()                   { return getUsername(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
}
