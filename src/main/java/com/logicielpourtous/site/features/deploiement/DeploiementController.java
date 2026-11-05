package com.logicielpourtous.site.features.deploiement;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.logicielpourtous.site.features.licence.LicenceApiKeyAuthFilter;
import com.logicielpourtous.site.features.licence.LicenceServeur;
import com.logicielpourtous.site.features.licence.LicenceServeurRepository;

/**
 * Distribution de l'ordre de deploiement bleu-vert en cours a un {@link LicenceServeur} enrole
 * (Task 5+ : l'Agent de deploiement de CE serveur, qui sonde periodiquement cette route).
 *
 * <p>Montee sur la chaine de securite existante {@code SecurityConfig.licenceApiFilterChain} (pas
 * une nouvelle chaine) : meme authentification par cle API ({@value
 * LicenceApiKeyAuthFilter#HEADER}) que les autres routes {@code /api/licence/**} — le principal
 * pose par {@link LicenceApiKeyAuthFilter} est l'id du {@link LicenceServeur} appelant, meme patron
 * que {@code LicenceEnrolementController}.
 */
@RestController
@RequestMapping("/api/licence")
public class DeploiementController {

    private static final Logger log = LoggerFactory.getLogger(DeploiementController.class);

    private final OrdreDeploiementRepository ordreDeploiementRepository;
    private final LicenceServeurRepository licenceServeurRepository;

    public DeploiementController(OrdreDeploiementRepository ordreDeploiementRepository,
                                 LicenceServeurRepository licenceServeurRepository) {
        this.ordreDeploiementRepository = ordreDeploiementRepository;
        this.licenceServeurRepository = licenceServeurRepository;
    }

    public record OrdreDeploiementReponse(String version) {}

    /**
     * Renvoie la version du dernier {@link OrdreDeploiement} pas encore consomme par le {@link
     * LicenceServeur} authentifie, ou {@code null} s'il n'y en a aucun (jamais emis, ou deja
     * servi a CE serveur) — voir {@link LicenceServeur#getDernierOrdreDeploiementConsommeId()}.
     * Marque l'ordre consomme pour ce serveur (persiste) avant de repondre, pour qu'un sondage
     * suivant du meme Agent ne le reçoive pas une seconde fois.
     *
     * <p>Les 2 controles ci-dessous (principal/serveur) sont des gardes defensives : {@code
     * SecurityConfig.licenceApiFilterChain} (hasRole) garantit deja qu'on ne peut atteindre cette
     * methode qu'authentifie par {@link LicenceApiKeyAuthFilter}, qui pose TOUJOURS un principal
     * {@code Long} resolu depuis un {@link LicenceServeur} existant — en pratique jamais declenchees,
     * meme discipline defensive que {@code LicenceEnrolementController#partenaireActifAuthentifieOuNull}
     * (403, pas une reponse "ok" ambigue).
     */
    @GetMapping("/ordre-deploiement")
    @Transactional
    public ResponseEntity<OrdreDeploiementReponse> ordreDeploiement() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Long serveurId)) {
            log.warn("Appel ordre-deploiement refuse : principal inattendu {}", principal);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        LicenceServeur serveur = licenceServeurRepository.findById(serveurId).orElse(null);
        if (serveur == null) {
            log.warn("Appel ordre-deploiement refuse : serveur licence {} introuvable", serveurId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<OrdreDeploiement> dernier = ordreDeploiementRepository.findTopByOrderByCreeADescIdDesc();
        if (dernier.isEmpty() || dernier.get().getId().equals(serveur.getDernierOrdreDeploiementConsommeId())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OrdreDeploiementReponse(null));
        }

        OrdreDeploiement ordre = dernier.get();
        serveur.setDernierOrdreDeploiementConsommeId(ordre.getId());
        licenceServeurRepository.save(serveur);
        log.info("Ordre de deploiement {} (version {}) servi au serveur licence {}",
                ordre.getId(), ordre.getVersion(), serveur.getId());

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new OrdreDeploiementReponse(ordre.getVersion()));
    }
}
