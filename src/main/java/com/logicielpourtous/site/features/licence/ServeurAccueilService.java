package com.logicielpourtous.site.features.licence;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;

/**
 * Regles de federation du Maitre (etape 1 de la connexion deleguee) : serveur d'accueil d'un tenant,
 * hierarchie tenant client -> tenant partenaire (un seul niveau), serveur par defaut des nouveaux
 * tenants, forme de l'URL publique d'un serveur. Toute entree refusee leve une
 * {@link IllegalArgumentException} au message lisible, rien n'est alors modifie.
 */
@Service
@Transactional
public class ServeurAccueilService {

    /** {@code https://hote[:port]}, sans chemin, requete, identifiants ni espace. */
    private static final Pattern URL_PUBLIQUE = Pattern.compile(
            "^https://[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*"
                    + "(:([0-9]{1,5}))?/?$");

    private final ParametreFederationRepository parametreFederationRepository;
    private final LicenceServeurRepository licenceServeurRepository;
    private final TenantRepository tenantRepository;

    public ServeurAccueilService(ParametreFederationRepository parametreFederationRepository,
                                 LicenceServeurRepository licenceServeurRepository,
                                 TenantRepository tenantRepository) {
        this.parametreFederationRepository = parametreFederationRepository;
        this.licenceServeurRepository = licenceServeurRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Long> serveurParDefautId() {
        return parametreFederationRepository.findById(ParametreFederation.ID_UNIQUE)
                .map(ParametreFederation::getServeurDefautId);
    }

    /** {@code null} efface le serveur par defaut. */
    public void definirServeurParDefaut(Long serveurId) {
        if (serveurId != null) {
            exigerServeurActif(serveurId);
        }
        ParametreFederation parametre = parametreFederationRepository.findById(ParametreFederation.ID_UNIQUE)
                .orElseGet(ParametreFederation::new);
        parametre.setServeurDefautId(serveurId);
        parametreFederationRepository.save(parametre);
    }

    /** {@code null} pour {@code serveurId} ou {@code partenaireId} retire le rattachement correspondant. */
    public void rattacherTenant(Long tenantId, Long serveurId, Long partenaireId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant introuvable."));
        if (serveurId != null) {
            exigerServeurActif(serveurId);
        }
        if (partenaireId != null) {
            if (partenaireId.equals(tenantId)) {
                throw new IllegalArgumentException("Un tenant ne peut pas être son propre partenaire.");
            }
            Tenant partenaire = tenantRepository.findById(partenaireId)
                    .orElseThrow(() -> new IllegalArgumentException("Tenant partenaire introuvable."));
            if (partenaire.getTenantPartenaireId() != null) {
                throw new IllegalArgumentException("Ce partenaire dépend lui-même d'un autre partenaire : "
                        + "la hiérarchie n'a qu'un seul niveau.");
            }
            if (tenantRepository.existsByTenantPartenaireId(tenantId)) {
                throw new IllegalArgumentException("Ce tenant est déjà le partenaire d'autres tenants : "
                        + "il ne peut pas dépendre lui-même d'un partenaire.");
            }
        }
        tenant.setServeurLicenceId(serveurId);
        tenant.setTenantPartenaireId(partenaireId);
        tenantRepository.save(tenant);
    }

    /** Renvoie l'URL sans {@code /} final ; vide ou {@code null} renvoie {@code null} (effacement). */
    public String normaliserUrlPublique(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String nettoyee = url.trim();
        Matcher m = URL_PUBLIQUE.matcher(nettoyee);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "URL publique invalide : attendu https://hote[:port], sans chemin ni identifiants.");
        }
        String port = m.group(5);
        if (port != null) {
            int numero = Integer.parseInt(port);
            if (numero < 1 || numero > 65535) {
                throw new IllegalArgumentException("Port invalide dans l'URL publique.");
            }
        }
        return nettoyee.endsWith("/") ? nettoyee.substring(0, nettoyee.length() - 1) : nettoyee;
    }

    private void exigerServeurActif(Long serveurId) {
        LicenceServeur serveur = licenceServeurRepository.findById(serveurId)
                .orElseThrow(() -> new IllegalArgumentException("Serveur introuvable."));
        if (serveur.getStatut() != LicenceServeur.LicenceServeurStatut.ACTIF) {
            throw new IllegalArgumentException("Ce serveur est révoqué : il ne peut pas être choisi.");
        }
    }
}
