package com.logicielpourtous.site.features.licence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Aiguillage cote Maitre : repond a un serveur de licence, pour une identite qu'il vient d'authentifier chez
 * Authelia, la liste des societes du compte hebergees par UN AUTRE serveur. Les adresses viennent
 * exclusivement de {@code licence_serveurs.urlPublique} (ou de la propriete du Maitre), jamais de la requete.
 * Tous les refus (serveur sans identifiant de client, jeton invalide, compte inconnu, inactif, ambigu, sans
 * societe) donnent la MEME liste vide : rien ne permet de sonder l'existence d'un compte.
 */
@Service
@Transactional(readOnly = true)
public class AiguillageService {

    /** Une societe du compte et l'adresse publique du serveur qui l'heberge. */
    public record Destination(Long societeId, String nomSociete, String urlPublique) {
    }

    private final LicenceServeurRepository serveurs;
    private final UserRepository users;
    private final AppartenanceRepository appartenances;
    private final ValidateurJetonAuthelia validateur;
    private final String maitreUrlPublique;

    public AiguillageService(LicenceServeurRepository serveurs, UserRepository users,
                             AppartenanceRepository appartenances, ValidateurJetonAuthelia validateur,
                             @Value("${app.auth.authelia.maitre-url-publique:}") String maitreUrlPublique) {
        this.serveurs = serveurs;
        this.users = users;
        this.appartenances = appartenances;
        this.validateur = validateur;
        this.maitreUrlPublique = normaliserMaitre(maitreUrlPublique);
    }

    /** Meme regle que les adresses des serveurs (https seulement) ; invalide = non configuree, avec avertissement. */
    private static String normaliserMaitre(String valeur) {
        try {
            String n = ServeurAccueilService.normaliser(valeur);
            return n == null ? "" : n;
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger(AiguillageService.class).warn(
                    "app.auth.authelia.maitre-url-publique invalide (https://hote[:port] attendu) : ignoree, "
                            + "les societes hebergees par le Maitre ne seront pas proposees.");
            return "";
        }
    }

    public List<Destination> aiguiller(Long licenceServeurId, String idToken) {
        if (licenceServeurId == null) {
            return List.of();
        }
        Optional<LicenceServeur> appelant = serveurs.findById(licenceServeurId)
                .filter(s -> s.getStatut() == LicenceServeur.LicenceServeurStatut.ACTIF)
                .filter(s -> s.getOidcClientId() != null && !s.getOidcClientId().isBlank());
        if (appelant.isEmpty()) {
            return List.of();
        }
        Optional<String> email = validateur.valider(idToken, appelant.get().getOidcClientId());
        if (email.isEmpty()) {
            return List.of();
        }
        List<User> comptes = users.findAllByEmailIgnoreCase(email.get());
        if (comptes.size() != 1 || !comptes.get(0).isActive()) {
            return List.of();
        }

        Set<Destination> resultat = new LinkedHashSet<>();
        for (Appartenance a : appartenances.findByUserIdOrderById(comptes.get(0).getId())) {
            Long hote = a.getTenant().getServeurLicenceId();
            if (licenceServeurId.equals(hote)) {
                continue; // l'appelant connait deja cette societe
            }
            String url = hote == null ? maitreUrlPublique : urlDuServeurActif(hote);
            if (url != null && !url.isBlank()) {
                resultat.add(new Destination(a.getSocieteId(), a.getTenant().getName(), url));
            }
        }
        return new ArrayList<>(resultat);
    }

    private String urlDuServeurActif(Long id) {
        return serveurs.findById(id)
                .filter(s -> s.getStatut() == LicenceServeur.LicenceServeurStatut.ACTIF)
                .map(LicenceServeur::getUrlPublique)
                .orElse(null);
    }
}
