package com.logicielpourtous.site.features.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.TypeSociete;

/**
 * Formulaire {@code POST /register} (client) : une societe deja inscrite et une societe suivie par un
 * partenaire donnent EXACTEMENT la meme reponse HTTP ; un numero refuse revient au formulaire avec le champ
 * fautif. Sans {@code @Transactional} (le service ouvre ses propres transactions) : nettoyage explicite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InscriptionClientControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RegistreSocietes registre;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> tvas = new ArrayList<>();

    private MockHttpServletRequestBuilder inscription(String siren, String tva) {
        return post("/register")
                .param("accountType", "CLIENT")
                .param("companyName", "Societe Test")
                .param("fullName", "Alice Martin")
                .param("email", "inscription-" + UUID.randomUUID() + "@example.com")
                .param("password", "MotDePasse!1")
                .param("siren", siren)
                .param("tva", tva);
    }

    /** SIREN aleatoire valide, enregistre au registre avec le proprietaire donne. */
    private String dejaAuRegistre(Long proprietaire) {
        while (true) {
            String base = String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
            for (int cle = 0; cle <= 9; cle++) {
                String siren = base + cle;
                if (NumerosSociete.sirenValide(siren) && !siren.startsWith("356000000")) {
                    String tva = NumerosSociete.tvaFrancaise(siren);
                    tvas.add(tva);
                    registre.enregistrer(TypeSociete.CLIENT, IdentiteSociete.depuis(siren, null, tva), proprietaire);
                    return siren;
                }
            }
        }
    }

    @Test
    void societeDuMaitreEtSocieteDUnPartenaire_memeRedirectionNeutre() throws Exception {
        String duMaitre = dejaAuRegistre(null);
        String dUnPartenaire = dejaAuRegistre(7L);

        mockMvc.perform(inscription(duMaitre.substring(0, 3) + " " + duMaitre.substring(3),
                        NumerosSociete.tvaFrancaise(duMaitre).toLowerCase()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/register/societe-connue"));
        mockMvc.perform(inscription(dUnPartenaire, NumerosSociete.tvaFrancaise(dUnPartenaire)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/register/societe-connue"));
    }

    @Test
    void tvaEtrangere_retourAuFormulaireAvecLeChampFautif() throws Exception {
        mockMvc.perform(inscription("", "DE123456789"))
                .andExpect(status().isOk())
                .andExpect(view().name("feature/auth/register"))
                .andExpect(model().attribute("champErreur", "tva"))
                .andExpect(model().attributeExists("error"));
    }

    @AfterEach
    void nettoyer() {
        for (String tva : tvas) {
            jdbc.update("DELETE FROM demandes_societe_connue WHERE societe_identite_id IN "
                    + "(SELECT id FROM societes_identites WHERE tva = ?)", tva);
            jdbc.update("DELETE FROM societes_identites WHERE tva = ?", tva);
        }
    }
}
