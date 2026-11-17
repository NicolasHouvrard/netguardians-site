package com.logicielpourtous.site.features.api.angular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * PUT /esn-roles ne doit jamais pouvoir donner l'administration : un simple utilisateur ESN ne peut pas
 * nommer un administrateur (les administrateurs se nomment par /intra/users, reserve a ROLE_ADMIN).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContactAccountEsnRolesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private User cible;
    private Contact contact;

    private void preparer(Set<Role> rolesInitiaux) {
        Customer client = new Customer();
        client.setCompanyName("Client test roles " + UUID.randomUUID());
        client = customerRepository.saveAndFlush(client);
        cible = new User();
        cible.setEmail("cible-" + UUID.randomUUID() + "@exemple.fr");
        cible.setPassword("x");
        cible.setFullName("Cible");
        cible.setActive(true);
        cible.setRoles(new HashSet<>(rolesInitiaux));
        cible = userRepository.saveAndFlush(cible);
        contact = new Contact();
        contact.setFirstName("A");
        contact.setLastName("B");
        contact.setEmail(cible.getEmail());
        contact.setCustomer(client);
        contact.setUserId(cible.getId());
        contact = contactRepository.saveAndFlush(contact);
    }

    private void appeler(String corps) throws Exception {
        mockMvc.perform(put("/api/intra/customers/{c}/contacts/{ct}/account/esn-roles",
                        contact.getCustomer().getId(), contact.getId())
                        .with(user("esn@exemple.fr").roles("ESN_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk());
    }

    private Set<String> rolesDe() {
        return userRepository.findById(cible.getId()).orElseThrow().getRoles().stream()
                .map(Role::getName).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void demanderAdmin_neDonneAucunRoleAdmin_etNeCreeRienNomme_ADMIN() throws Exception {
        preparer(Set.of());
        boolean existaitAvant = roleRepository.findByName("ADMIN").isPresent();
        appeler("{\"roleNames\":[\"ADMIN\",\"ROLE_ADMIN\"]}");
        assertThat(rolesDe()).doesNotContain("ADMIN", "ROLE_ADMIN");
        assertThat(roleRepository.findByName("ADMIN").isPresent()).isEqualTo(existaitAvant);
    }

    @Test
    void lesAutresRolesEsnFonctionnentToujours() throws Exception {
        preparer(Set.of());
        appeler("{\"roleNames\":[\"COMMERCIAL\",\"ADMIN\"]}");
        assertThat(rolesDe()).contains("COMMERCIAL").doesNotContain("ADMIN");
    }

    @Test
    void unAdminExistant_n_estPasRetireParCetteRoute() throws Exception {
        Role admin = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_ADMIN", "Administrateur", "ESN")));
        preparer(Set.of(admin));
        appeler("{\"roleNames\":[\"COMMERCIAL\"]}");
        assertThat(rolesDe()).contains("ROLE_ADMIN", "COMMERCIAL");
    }
}
