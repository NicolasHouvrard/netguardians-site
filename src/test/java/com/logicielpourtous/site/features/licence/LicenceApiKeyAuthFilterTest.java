package com.logicielpourtous.site.features.licence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;
import com.logicielpourtous.site.features.sync.SyncApiKeyAuthFilter;
import com.logicielpourtous.site.features.sync.SyncApiKeys;

/**
 * {@link LicenceApiKeyAuthFilter} : authentification des routes {@code /api/licence/**} par
 * l'en-tete {@code X-Licence-Api-Key}, et cloisonnement reciproque avec {@code features.sync}'s
 * {@code X-Sync-Api-Key} (Review Focus du plan : les deux mecanismes de cle sont deliberement
 * separes, voir {@link LicenceCles}).
 *
 * <p>Aucune route protegee par {@code ROLE_LICENCE_SECONDARY} n'existe encore a ce jour (le
 * client-token d'une tache ulterieure du plan) : {@code licenceApiFilterChain} termine donc sur
 * {@code anyRequest().denyAll()} (repli par defaut, pas {@code hasRole(...)} — fix round 1, finding
 * 5), et ces tests utilisent un chemin arbitraire sous {@code /api/licence/**} pour exercer la
 * chaine de securite elle-meme. Comme {@code denyAll()} refuse l'acces INDEPENDAMMENT du principal,
 * une authentification reussie (cle active) se distingue d'un echec d'authentification par le code
 * HTTP : 403 (AccessDeniedException sur un principal reel, authentifie mais refuse) contre 401
 * (AuthenticationException sur un principal anonyme, {@code HttpStatusEntryPoint}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LicenceApiKeyAuthFilterTest {

    private static final String ROUTE_PROTEGEE_ARBITRAIRE = "/api/licence/quelconque";

    @Autowired private MockMvc mockMvc;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private SecondaryServerRepository secondaryServerRepository;

    @Test
    void protectedRouteWithoutKeyIsRejected() throws Exception {
        mockMvc.perform(get(ROUTE_PROTEGEE_ARBITRAIRE)).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteWithUnknownKeyIsRejected() throws Exception {
        mockMvc.perform(get(ROUTE_PROTEGEE_ARBITRAIRE).header(LicenceApiKeyAuthFilter.HEADER, LicenceCles.generate()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteWithRevokedKeyIsRejected() throws Exception {
        String cle = LicenceCles.generate();
        enrolledServeur(cle, LicenceServeur.LicenceServeurStatut.REVOQUE);

        mockMvc.perform(get(ROUTE_PROTEGEE_ARBITRAIRE).header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteWithValidActiveKeyPassesAuthenticationButIsDeniedByDefaultDenyAll() throws Exception {
        String cle = LicenceCles.generate();
        enrolledServeur(cle, LicenceServeur.LicenceServeurStatut.ACTIF);

        // 403 (pas 401) : preuve que le filtre A authentifie (principal reel) — c'est bien
        // anyRequest().denyAll() qui refuse ensuite, pas une authentification manquante.
        mockMvc.perform(get(ROUTE_PROTEGEE_ARBITRAIRE).header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isForbidden());
    }

    // ── Cross-filtre (Review Focus) ───────────────────────────────────────────

    @Test
    void syncApiKeyIsRejectedOnLicenceRoutes() throws Exception {
        String cleSync = SyncApiKeys.generate();
        SecondaryServer serveur = new SecondaryServer();
        serveur.setNom("Partenaire sync (cross-filtre)");
        serveur.setRole(ServerRole.CLIENT);
        serveur.setApiKeyHash(SyncApiKeys.hash(cleSync));
        serveur.setStatut(SecondaryServer.SecondaryServerStatut.ACTIF);
        secondaryServerRepository.save(serveur);

        mockMvc.perform(get(ROUTE_PROTEGEE_ARBITRAIRE).header(LicenceApiKeyAuthFilter.HEADER, cleSync))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void licenceApiKeyIsRejectedOnSyncRoutes() throws Exception {
        String cleLicence = LicenceCles.generate();
        enrolledServeur(cleLicence, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(get("/api/sync/events").header(SyncApiKeyAuthFilter.HEADER, cleLicence))
                .andExpect(status().isUnauthorized());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private LicenceServeur enrolledServeur(String cleApi, LicenceServeur.LicenceServeurStatut statut) {
        LicenceServeur s = new LicenceServeur();
        s.setCompteId(1L);
        s.setRole(ServerRole.PARTNER);
        s.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        s.setCleApiHash(LicenceCles.hash(cleApi));
        s.setStatut(statut);
        return licenceServeurRepository.save(s);
    }
}
