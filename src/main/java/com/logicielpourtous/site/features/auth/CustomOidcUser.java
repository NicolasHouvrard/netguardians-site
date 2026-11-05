package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Map;

/**
 * Principal OIDC (Google) qui étend CustomUserDetails.
 * Tous les @AuthenticationPrincipal CustomUserDetails continuent de fonctionner.
 */
public class CustomOidcUser extends CustomUserDetails implements OidcUser {

    private static final long serialVersionUID = 1L;
    private final OidcUser delegate;

    public CustomOidcUser(OidcUser delegate, User user, String email) {
        super(user);
        this.delegate = delegate;
    }

    @Override public String getName()                    { return getUsername(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public Map<String, Object> getClaims()     { return delegate.getClaims(); }
    @Override public OidcUserInfo getUserInfo()           { return delegate.getUserInfo(); }
    @Override public OidcIdToken getIdToken()             { return delegate.getIdToken(); }
}
