package com.logicielpourtous.site.features.auth;

import java.sql.Timestamp;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;

/**
 * Nettoyage quotidien des inscriptions autonomes jamais verifiees : libere les societes qu'elles bloquent au
 * registre. Les candidates sont preselectionnees ici (origine « inscription client », Maitre, fiche client, plus
 * anciennes que le delai) ; TOUTES les conditions sont ensuite reverifiees sous verrou par
 * {@link InscriptionSocieteService#liberer}, chaque liberation dans sa propre transaction (un echec n'arrete pas
 * le lot).
 *
 * <p>Horaire : {@code app.inscription.nettoyage-cron} (defaut 3 h 30 chaque nuit ; {@code -} le desactive).
 */
@Component
public class NettoyageInscriptionsNonVerifiees {

    private static final Logger log = LoggerFactory.getLogger(NettoyageInscriptionsNonVerifiees.class);

    private final InscriptionSocieteService inscription;
    private final SocieteIdentiteRepository identites;
    private final JdbcTemplate jdbc;

    public NettoyageInscriptionsNonVerifiees(InscriptionSocieteService inscription,
                                             SocieteIdentiteRepository identites, JdbcTemplate jdbc) {
        this.inscription = inscription;
        this.identites = identites;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${app.inscription.nettoyage-cron:0 30 3 * * *}")
    public void nettoyagePlanifie() {
        int liberees = nettoyer();
        if (liberees > 0) {
            log.info("Nettoyage des inscriptions non verifiees : {} societe(s) liberee(s)", liberees);
        }
    }

    /** @return le nombre de societes liberees */
    public int nettoyer() {
        List<Long> candidates = jdbc.queryForList(
                "SELECT id FROM societes_identites WHERE origine = ? AND proprietaire_tenant_id IS NULL "
                        + "AND fiche_type = ? AND cree_a < ? ORDER BY id",
                Long.class, InscriptionSocieteService.ORIGINE_INSCRIPTION_CLIENT,
                InscriptionSocieteService.FICHE_CUSTOMERS, Timestamp.from(inscription.limiteVerification()));
        int liberees = 0;
        for (Long id : candidates) {
            try {
                // Hors transaction : chaque liberation a la sienne ; un echec annule cette seule candidate.
                if (identites.findById(id).map(inscription::liberer).orElse(false)) {
                    liberees++;
                }
            } catch (RuntimeException e) {
                log.error("Nettoyage des inscriptions non verifiees : echec pour la societe du registre #{}", id, e);
            }
        }
        return liberees;
    }
}
