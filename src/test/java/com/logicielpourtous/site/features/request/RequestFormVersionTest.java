package com.logicielpourtous.site.features.request;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Non-regression du formulaire d'edition {@code feature/requests/form.html} /
 * {@code RequestController.save()} ({@code @ModelAttribute Request}, liee directement depuis les
 * champs du formulaire) face au verrouillage optimiste ({@code @Version} sur {@code Syncable}).
 *
 * <p>Volontairement SANS {@code @Transactional} englobante : chaque requete MockMvc tourne comme en
 * production (contexte de persistance propre a l'appel, {@code save()} = vrai {@code merge} d'une
 * instance detachee, vrai UPDATE ... WHERE version = ? au commit). Les lignes creees sont nettoyees
 * en {@code @AfterEach}.
 *
 * <p>Le round-trip est REEL : on fait le GET du formulaire d'edition, on recupere les champs caches
 * rendus par Thymeleaf (id, version...) dans le HTML, et on les re-soumet comme le ferait le
 * navigateur. Si le template ne rend pas le champ {@code version}, le test le voit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestFormVersionTest {

    private static final Pattern HIDDEN_INPUT = Pattern.compile("<input[^>]*type=\"hidden\"[^>]*>");
    private static final Pattern NAME_ATTR = Pattern.compile("\\bname=\"([^\"]*)\"");
    private static final Pattern VALUE_ATTR = Pattern.compile("\\bvalue=\"([^\"]*)\"");

    @Autowired private MockMvc mockMvc;
    @Autowired private RequestRepository requestRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Tenant tenant;
    private User persistedUser;
    private Customer customer;
    private CustomUserDetails principal;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setName("Version Test Tenant");
        tenant.setSchemaName("version_test_" + UUID.randomUUID().toString().replace("-", ""));
        tenant = tenantRepository.save(tenant);

        String email = "version-test-" + UUID.randomUUID() + "@logicielpourtous.com";
        User u = new User();
        u.setEmail(email);
        u.setPassword("test");
        u.setFullName("Version Test");
        u.setActive(true);
        u.setTenant(tenant);
        persistedUser = userRepository.save(u);

        User principalUser = new User();
        principalUser.setEmail(email);
        principalUser.setPassword("test");
        principalUser.setFullName("Version Test");
        principalUser.setActive(true);
        principalUser.setTenant(tenant);
        principalUser.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        principal = new CustomUserDetails(principalUser);

        Customer c = new Customer();
        c.setCompanyName("Client version test " + UUID.randomUUID());
        customer = customerRepository.save(c);
    }

    @AfterEach
    void tearDown() {
        // Outbox (alimentee par registerChange()), puis requests, puis le reste.
        jdbcTemplate.update("DELETE FROM sync_outbox_entries WHERE entitysyncid IN "
                + "(SELECT sync_id FROM requests WHERE customer_id = ?)", customer.getId());
        jdbcTemplate.update("DELETE FROM requests WHERE customer_id = ?", customer.getId());
        customerRepository.deleteById(customer.getId());
        userRepository.deleteById(persistedUser.getId());
        tenantRepository.deleteById(tenant.getId());
    }

    /**
     * Step 2/4/6 du brief : edition d'une demande EXISTANTE via le formulaire, tel que le navigateur
     * le soumet (champs caches rendus par le GET + champs saisis). Doit reussir.
     */
    @Test
    void editingExistingRequestThroughFormRoundTripSucceeds() throws Exception {
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("Avant");
        r = requestRepository.save(r);

        Map<String, String> hidden = loadEditFormHiddenFields(r.getId());
        assertThat(hidden).containsEntry("id", r.getId().toString());

        mockMvc.perform(submit(hidden, "Apres"))
                .andExpect(status().is3xxRedirection());

        assertThat(requestRepository.findById(r.getId()).orElseThrow().getTitle()).isEqualTo("Apres");
    }

    /** Le formulaire rend bien la version COURANTE (pas 0 en dur) : 2 editions successives passent. */
    @Test
    void successiveEditsThroughFormRoundTripVersion() throws Exception {
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("v0");
        r = requestRepository.save(r);
        assertThat(r.getVersion()).isZero();

        Map<String, String> first = loadEditFormHiddenFields(r.getId());
        assertThat(first).containsEntry("version", "0");
        mockMvc.perform(submit(first, "v1")).andExpect(status().is3xxRedirection());

        Map<String, String> second = loadEditFormHiddenFields(r.getId());
        assertThat(second).containsEntry("version", "1");
        mockMvc.perform(submit(second, "v2")).andExpect(status().is3xxRedirection());

        Request reloaded = requestRepository.findById(r.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("v2");
        assertThat(reloaded.getVersion()).isEqualTo(2L);
    }

    /**
     * Step 7 (via le vrai formulaire) : 2 utilisateurs ouvrent le meme formulaire (meme version),
     * le 1er enregistre, le 2e doit etre REJETE (verrouillage optimiste) au lieu d'ecraser en silence.
     *
     * <p>Task 4 : le rejet n'est plus une page d'erreur 500 brute mais une redirection vers le
     * formulaire avec un message clair ({@code RequestController#conflitEdition}).
     */
    @Test
    void concurrentFormEditsSecondSubmitIsRejectedWithOptimisticLock() throws Exception {
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("Origine");
        r = requestRepository.save(r);

        Map<String, String> formA = loadEditFormHiddenFields(r.getId());
        Map<String, String> formB = loadEditFormHiddenFields(r.getId());
        assertThat(formA.get("version")).isEqualTo(formB.get("version"));

        mockMvc.perform(submit(formA, "Edition A")).andExpect(status().is3xxRedirection());

        mockMvc.perform(submit(formB, "Edition B"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/intra/customers/" + customer.getId() + "/requests/edit/" + r.getId()))
                .andExpect(flash().attribute("errorMessage", RequestController.MESSAGE_CONFLIT_EDITION));

        Request reloaded = requestRepository.findById(r.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Edition A");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    /**
     * Cas qui devient frequent avec la synchronisation (Task 4) : la version augmente HORS de
     * l'interface (changement recu d'un autre serveur, simule ici par une ecriture directe) entre
     * l'ouverture du formulaire et son envoi. Redirection vers le formulaire, message affiche.
     */
    @Test
    void versionBumpedOutsideTheFormRedirectsBackWithReadableMessage() throws Exception {
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("Origine");
        r = requestRepository.save(r);
        Map<String, String> form = loadEditFormHiddenFields(r.getId());

        Request horsInterface = requestRepository.findById(r.getId()).orElseThrow();
        horsInterface.setTitle("Modifie par la synchro");
        requestRepository.save(horsInterface);

        mockMvc.perform(submit(form, "Edition perimee"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", RequestController.MESSAGE_CONFLIT_EDITION));
        assertThat(requestRepository.findById(r.getId()).orElseThrow().getTitle()).isEqualTo("Modifie par la synchro");

        // Le message est bien affiche sur le formulaire recharge (version a jour).
        String html = mockMvc.perform(get("/intra/customers/" + customer.getId() + "/requests/edit/" + r.getId())
                        .with(user(principal))
                        .flashAttr("errorMessage", RequestController.MESSAGE_CONFLIT_EDITION))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("merci de recharger la page avant de r");
    }

    /**
     * Step 7 (mecanisme JPA brut, hors controleur, sans transaction englobante = cas des
     * controleurs) : 2 chargements de la meme Request, 2 sauvegardes, la 2e echoue.
     */
    @Test
    void twoLoadsTwoSavesSecondSaveFailsWithOptimisticLock() {
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("Origine");
        Long id = requestRepository.save(r).getId();

        Request copy1 = requestRepository.findById(id).orElseThrow();
        Request copy2 = requestRepository.findById(id).orElseThrow();
        assertThat(copy1.getVersion()).isEqualTo(copy2.getVersion()).isZero();

        copy1.setTitle("Copie 1");
        requestRepository.save(copy1);

        copy2.setTitle("Copie 2");
        assertThatThrownBy(() -> requestRepository.save(copy2))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Request reloaded = requestRepository.findById(id).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Copie 1");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    /** Formulaire de CREATION (champ version vide) : toujours une creation, version initialisee a 0. */
    @Test
    void creatingRequestThroughFormStillWorks() throws Exception {
        mockMvc.perform(post("/intra/customers/" + customer.getId() + "/requests/save")
                        .with(user(principal))
                        .param("id", "")
                        .param("version", "")
                        .param("title", "Creation"))
                .andExpect(status().is3xxRedirection());

        assertThat(requestRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId()))
                .singleElement()
                .satisfies(created -> {
                    assertThat(created.getTitle()).isEqualTo("Creation");
                    assertThat(created.getVersion()).isZero();
                });
    }

    // ── utilitaires ─────────────────────────────────────────────────────────

    /** GET du formulaire d'edition puis extraction des champs caches tels que rendus par Thymeleaf. */
    Map<String, String> loadEditFormHiddenFields(Long requestId) throws Exception {
        String html = mockMvc.perform(get("/intra/customers/" + customer.getId() + "/requests/edit/" + requestId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, String> hidden = new LinkedHashMap<>();
        Matcher m = HIDDEN_INPUT.matcher(html);
        while (m.find()) {
            String tag = m.group();
            Matcher n = NAME_ATTR.matcher(tag);
            Matcher v = VALUE_ATTR.matcher(tag);
            if (n.find()) {
                hidden.putIfAbsent(n.group(1), v.find() ? v.group(1) : "");
            }
        }
        return hidden;
    }

    MockHttpServletRequestBuilder submit(Map<String, String> hidden, String title) {
        MockHttpServletRequestBuilder b = post("/intra/customers/" + customer.getId() + "/requests/save")
                .with(user(principal));
        hidden.forEach(b::param);
        return b.param("title", title)
                .param("status", RequestStatus.IN_PROGRESS.name())
                .param("priority", RequestPriority.MEDIUM.name());
    }
}
