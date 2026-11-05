package com.logicielpourtous.site.features.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.logicielpourtous.site.features.user.User;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class CustomUserDetails implements UserDetails, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final User user;

    public CustomUserDetails(User user) {
        this.user = user;
    }

    public Long   getUserId()     { return user.getId(); }
    public String getFullName()  { return user.getFullName(); }
    public Long   getCustomerId(){ return user.getCustomerId(); }
    public com.logicielpourtous.site.features.commun.Tenant getTenant() { return user.getTenant(); }

    public boolean isClient() {
        return getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT"));
    }

    public boolean isGestionnaire() {
        return getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_GESTIONNAIRE"));
    }

    public boolean hasPortalRole() {
        return isClient() || isGestionnaire();
    }

    public boolean hasEsnRole() {
        return user.getRoles().stream()
                .anyMatch(r -> "ESN".equals(r.getType()));
    }

    @Override
    public String getUsername() { return user.getEmail(); }

    @Override
    public String getPassword() { return user.getPassword(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
            .map(role -> {
                String name = role.getName();
                if (name != null && !name.startsWith("ROLE_")) {
                    name = "ROLE_" + name;
                }
                return new SimpleGrantedAuthority(name);
            })
            .collect(Collectors.toList());

        // Autorité synthétique pour tous les rôles de type ESN : permet une règle
        // de sécurité unique indépendante des noms de rôles custom par tenant.
        if (hasEsnRole()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ESN_USER"));
        }

        return authorities;
    }

    @Override 
public boolean isEnabled() { return user.isActive(); }
    
    // Obligatoire pour UserDetails
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    
}