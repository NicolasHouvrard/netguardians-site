package com.logicielpourtous.site.features.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.User;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class CustomUserDetails implements UserDetails, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final User user;

    /** Appartenances de la personne (liste serialisable) ; vide pour un compte sans appartenance. */
    private final java.util.ArrayList<Appartenance> appartenances;
    /** Appartenance choisie (societe active), nulle tant que rien n'est choisi. */
    private final Long appartenanceActiveId;

    /** Compte sans appartenance : comportement historique, fonde sur la fiche utilisateur. */
    public CustomUserDetails(User user) {
        this(user, List.of(), null);
    }

    public CustomUserDetails(User user, List<Appartenance> appartenances, Long appartenanceActiveId) {
        this.user = user;
        this.appartenances = new java.util.ArrayList<>(appartenances == null ? List.of() : appartenances);
        this.appartenanceActiveId = appartenanceActiveId;
    }

    public List<Appartenance> appartenances() { return java.util.Collections.unmodifiableList(appartenances); }

    public Long appartenanceActiveId() { return appartenanceActiveId; }

    /** Vrai si la personne appartient a au moins deux societes. */
    public boolean plusieursSocietes() { return appartenances.size() >= 2; }

    /** Vrai si plusieurs societes et aucune appartenance active valide : la personne doit choisir. */
    public boolean selectionRequise() { return plusieursSocietes() && active() == null; }

    /** Nouveau compte avec l'appartenance active donnee ; elle doit etre l'une des appartenances de cette personne. */
    public CustomUserDetails avecAppartenanceActive(Long id) {
        boolean sienne = id != null && appartenances.stream().anyMatch(a -> id.equals(a.getId()));
        if (!sienne) {
            throw new IllegalArgumentException("Appartenance inconnue pour ce compte : " + id);
        }
        return new CustomUserDetails(user, appartenances, id);
    }

    /** Appartenance active valide (uniquement quand il y a plusieurs societes), sinon nul. */
    private Appartenance active() {
        if (!plusieursSocietes() || appartenanceActiveId == null) {
            return null;
        }
        return appartenances.stream()
                .filter(a -> appartenanceActiveId.equals(a.getId()))
                .findFirst().orElse(null);
    }

    private java.util.Set<com.logicielpourtous.site.features.role.Role> rolesEffectifs() {
        Appartenance a = active();
        return a != null ? a.getRoles() : user.getRoles();
    }

    public Long   getUserId()     { return user.getId(); }
    public String getFullName()  { return user.getFullName(); }

    public Long getCustomerId() {
        if (selectionRequise()) {
            return null;
        }
        Appartenance a = active();
        return a != null ? a.getCustomerId() : user.getCustomerId();
    }

    public com.logicielpourtous.site.features.commun.Tenant getTenant() {
        if (selectionRequise()) {
            return null;
        }
        Appartenance a = active();
        return a != null ? a.getTenant() : user.getTenant();
    }

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
        if (selectionRequise()) {
            return false;
        }
        return rolesEffectifs().stream()
                .anyMatch(r -> "ESN".equals(r.getType()));
    }

    @Override
    public String getUsername() { return user.getEmail(); }

    @Override
    public String getPassword() { return user.getPassword(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (selectionRequise()) {
            return List.of(new SimpleGrantedAuthority("ROLE_SELECTION_SOCIETE"));
        }
        List<SimpleGrantedAuthority> authorities = rolesEffectifs().stream()
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