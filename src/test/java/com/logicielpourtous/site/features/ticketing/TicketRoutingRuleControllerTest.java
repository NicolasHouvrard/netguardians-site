package com.logicielpourtous.site.features.ticketing;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preuve d'intégration que les routes /intra/tickets/routing fonctionnent toujours
 * après l'extraction de TicketRoutingRule/TicketRoutingService/TicketRoutingRuleController/
 * TicketRoutingRuleRepository de features/request vers features/ticketing.
 *
 * SecurityConfig.java place "/intra/**" (chaîne de session web, @Order(3)) derrière
 * hasAnyRole("ADMIN","USER","COMMERCIAL","TECHNICIEN","MARKETING","COMPTABLE"), et
 * TicketRoutingRuleController a pour base "/intra/tickets/routing" — qui ne correspond à
 * aucun des matchers plus spécifiques permitAll() ("/intra/pages/**", "/intra/menu/**"),
 * donc elle hérite de cette exigence de rôle générique.
 *
 * Le header Thymeleaf partagé (templates/header/connected.html) appelle
 * principal.hasEsnRole() via sec:authorize, une méthode définie sur
 * com.logicielpourtous.site.features.auth.CustomUserDetails — pas sur le principal
 * générique que produit @WithMockUser (org.springframework.security.core.userdetails.User).
 * On construit donc explicitement un vrai CustomUserDetails (adossé à une entité User
 * minimale avec un rôle ADMIN de type "ESN") et on l'injecte via
 * SecurityMockMvcRequestPostProcessors.user(UserDetails), pour obtenir le comportement
 * réel de l'application plutôt qu'un principal de test générique incompatible avec son UI.
 *
 * ChatIntraAdvice (@ControllerAdvice global, actif sur toute requête /intra/**) appelle
 * ChatConversationRepository.countByTenantAndStatus(tenant, ...) avec le Tenant du principal —
 * Hibernate exige alors un Tenant réellement persisté (sinon TransientObjectException : un
 * Tenant transitoire ne peut pas servir de paramètre de requête basé sur sa clé étrangère).
 * On persiste donc un Tenant minimal via TenantRepository avant de construire le principal.
 *
 * Ce test est annoté @Transactional : ce @SpringBootTest utilise MockMvc en mode mock
 * (pas de port réel, pas de client HTTP séparé) — la requête simulée s'exécute donc
 * synchroniquement dans le même thread que le test, et les méthodes @Transactional du
 * contrôleur rejoignent la transaction déjà ouverte par le test (propagation REQUIRED par
 * défaut) plutôt que d'en ouvrir une nouvelle. Spring annule donc cette transaction unique
 * à la fin du test, ce qui supprime le Tenant persisté ci-dessous sans laisser de ligne
 * orpheline dans site_db — vérifié empiriquement (comptage SQL avant/après identique),
 * pas seulement supposé correct depuis la documentation Spring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketRoutingRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void routingRulesEndpointStillRespondsAfterPackageMove() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("admin-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test Admin");
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));

        CustomUserDetails principal = new CustomUserDetails(user);

        mockMvc.perform(get("/intra/tickets/routing").with(user(principal)))
            .andExpect(status().isOk());
    }
}
