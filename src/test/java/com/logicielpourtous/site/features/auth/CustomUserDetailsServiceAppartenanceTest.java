package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

@SpringBootTest
@Transactional
class CustomUserDetailsServiceAppartenanceTest {

    @Autowired private CustomUserDetailsService service;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AppartenanceRepository appartenanceRepository;

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setName("T");
        t.setSchemaName("t_" + UUID.randomUUID());
        return tenantRepository.save(t);
    }

    private User personne(Tenant t) {
        User u = new User();
        u.setEmail("svc-" + UUID.randomUUID() + "@exemple.fr");
        u.setPassword("x");
        u.setFullName("P");
        u.setActive(true);
        u.setTenant(t);
        return userRepository.save(u);
    }

    private Appartenance appartenance(User u, Tenant t) {
        Role r = roleRepository.save(new Role("R" + UUID.randomUUID(), "R", "CLIENT"));
        Appartenance a = new Appartenance();
        a.setUserId(u.getId());
        a.setTenant(t);
        a.setRoles(Set.of(r));
        return appartenanceRepository.saveAndFlush(a);
    }

    @Test
    void charger_laissePersonneEnAttenteSiLIdentifiantNEstPasLeSien() {
        Tenant t1 = tenant();
        Tenant t2 = tenant();
        User moi = personne(t1);
        appartenance(moi, t1);
        Appartenance miennes2 = appartenance(moi, t2);
        Appartenance dUnAutre = appartenance(personne(t2), t2);

        CustomUserDetails refuse = (CustomUserDetails) service.charger(moi.getEmail(), dUnAutre.getId());
        assertThat(refuse.selectionRequise()).isTrue();
        assertThat(refuse.appartenanceActiveId()).isNull();

        CustomUserDetails ok = (CustomUserDetails) service.charger(moi.getEmail(), miennes2.getId());
        assertThat(ok.selectionRequise()).isFalse();
        assertThat(ok.appartenanceActiveId()).isEqualTo(miennes2.getId());

        CustomUserDetails parDefaut = (CustomUserDetails) service.loadUserByUsername(moi.getEmail());
        assertThat(parDefaut.selectionRequise()).isTrue();
    }
}
