package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.User;

/** Le compte connecte porte la societe active (sans Spring : objets en memoire). */
class CustomUserDetailsAppartenanceTest {

    private static Tenant tenant(long id) {
        Tenant t = new Tenant();
        ReflectionTestUtils.setField(t, "id", id);
        t.setName("T" + id);
        return t;
    }

    private static User personne(Tenant t) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", 1L);
        u.setEmail("p@exemple.fr");
        u.setPassword("x");
        u.setActive(true);
        u.setTenant(t);
        u.setCustomerId(7L);
        u.setRoles(Set.of(new Role("USER", "Utilisateur", "CLIENT")));
        return u;
    }

    private static Appartenance appartenance(long id, Tenant t, Long customerId, Role... roles) {
        Appartenance a = new Appartenance();
        a.setId(id);
        a.setUserId(1L);
        a.setTenant(t);
        a.setCustomerId(customerId);
        a.setRoles(Set.of(roles));
        return a;
    }

    private static Set<String> autorites(CustomUserDetails d) {
        return d.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void sansAppartenance_ouAvecUneSeule_lecomportementEstCelDeLaFiche() {
        Tenant fiche = tenant(1);
        User u = personne(fiche);

        CustomUserDetails sans = new CustomUserDetails(u);
        assertThat(sans.getTenant()).isSameAs(fiche);
        assertThat(sans.getCustomerId()).isEqualTo(7L);
        assertThat(autorites(sans)).containsExactly("ROLE_USER");
        assertThat(sans.plusieursSocietes()).isFalse();
        assertThat(sans.selectionRequise()).isFalse();

        Appartenance autre = appartenance(10, tenant(2), 99L, new Role("ADMIN", "Admin", "ESN"));
        CustomUserDetails une = new CustomUserDetails(u, List.of(autre), 10L);
        assertThat(une.getTenant()).isSameAs(fiche);
        assertThat(une.getCustomerId()).isEqualTo(7L);
        assertThat(autorites(une)).containsExactly("ROLE_USER");
        assertThat(une.hasEsnRole()).isFalse();
        assertThat(une.selectionRequise()).isFalse();
    }

    @Test
    void plusieursSocietes_sansChoix_selectionRequise_etAucunDroit() {
        User u = personne(tenant(1));
        List<Appartenance> deux = List.of(
                appartenance(10, tenant(2), 1L, new Role("ADMIN", "Admin", "ESN")),
                appartenance(11, tenant(3), 2L, new Role("USER", "Utilisateur", "CLIENT")));

        CustomUserDetails d = new CustomUserDetails(u, deux, null);
        assertThat(d.plusieursSocietes()).isTrue();
        assertThat(d.selectionRequise()).isTrue();
        assertThat(autorites(d)).containsExactly("ROLE_SELECTION_SOCIETE");
        assertThat(autorites(d)).doesNotContain("ROLE_ADMIN");
        assertThat(d.getTenant()).isNull();
        assertThat(d.getCustomerId()).isNull();

        // Un identifiant actif inconnu equivaut a aucun choix.
        assertThat(new CustomUserDetails(u, deux, 999L).selectionRequise()).isTrue();
    }

    @Test
    void plusieursSocietes_lesDroitsSontCeuxDeLaSocieteActive() {
        User u = personne(tenant(1));
        List<Appartenance> deux = List.of(
                appartenance(10, tenant(2), 1L, new Role("ADMIN", "Admin", "ESN")),
                appartenance(11, tenant(3), 2L, new Role("USER", "Utilisateur", "CLIENT")));

        CustomUserDetails a = new CustomUserDetails(u, deux, 10L);
        assertThat(a.selectionRequise()).isFalse();
        assertThat(autorites(a)).contains("ROLE_ADMIN", "ROLE_ESN_USER");
        assertThat(a.hasEsnRole()).isTrue();

        CustomUserDetails b = new CustomUserDetails(u, deux, 11L);
        assertThat(autorites(b)).contains("ROLE_USER").doesNotContain("ROLE_ADMIN", "ROLE_ESN_USER");
        assertThat(b.hasEsnRole()).isFalse();
    }

    @Test
    void avecAppartenanceActive_refuseUneAppartenanceQuiNEstPasLaSienne() {
        User u = personne(tenant(1));
        List<Appartenance> deux = List.of(
                appartenance(10, tenant(2), 1L, new Role("ADMIN", "Admin", "ESN")),
                appartenance(11, tenant(3), 2L, new Role("USER", "Utilisateur", "CLIENT")));
        CustomUserDetails d = new CustomUserDetails(u, deux, null);

        CustomUserDetails choisi = d.avecAppartenanceActive(11L);
        assertThat(choisi.appartenanceActiveId()).isEqualTo(11L);
        assertThat(d.appartenanceActiveId()).isNull();
        assertThat(choisi.appartenances()).hasSize(2);

        assertThatThrownBy(() -> d.avecAppartenanceActive(12345L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.avecAppartenanceActive(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plusieursSocietes_getTenantEtGetCustomerIdSuiventLaSocieteActive() {
        User u = personne(tenant(1));
        Tenant t2 = tenant(2);
        Tenant t3 = tenant(3);
        List<Appartenance> deux = List.of(
                appartenance(10, t2, 1L, new Role("ADMIN", "Admin", "ESN")),
                appartenance(11, t3, null, new Role("USER", "Utilisateur", "CLIENT")));

        CustomUserDetails a = new CustomUserDetails(u, deux, 10L);
        assertThat(a.getTenant()).isSameAs(t2);
        assertThat(a.getCustomerId()).isEqualTo(1L);

        CustomUserDetails b = a.avecAppartenanceActive(11L);
        assertThat(b.getTenant()).isSameAs(t3);
        assertThat(b.getCustomerId()).isNull();
    }
}
