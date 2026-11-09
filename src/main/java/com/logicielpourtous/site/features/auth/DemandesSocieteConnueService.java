package com.logicielpourtous.site.features.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;

/**
 * Demandes « societe connue » vues par le responsable de la societe : un partenaire ne voit que les demandes
 * des societes dont il est proprietaire au registre ; le Maitre (serveur MASTER, tenant cree en premier) voit
 * celles des societes sans proprietaire. Tout ce qui sort du perimetre est traite comme inexistant.
 */
@Service
public class DemandesSocieteConnueService {

    /** Une ligne du tableau de bord (valeurs saisies par le demandeur : a afficher echappees). */
    public record DemandeAffichee(Long id, String email, String nom, Instant creeA, String siren, String tva) {}

    private final DemandeSocieteConnueRepository demandes;
    private final SocieteIdentiteRepository identites;
    private final TenantRepository tenantRepository;
    private final ServerRoleProperties serverRoleProperties;

    public DemandesSocieteConnueService(DemandeSocieteConnueRepository demandes,
                                        SocieteIdentiteRepository identites,
                                        TenantRepository tenantRepository,
                                        ServerRoleProperties serverRoleProperties) {
        this.demandes = demandes;
        this.identites = identites;
        this.tenantRepository = tenantRepository;
        this.serverRoleProperties = serverRoleProperties;
    }

    /** Demandes non traitees dans le perimetre du tenant (vide si le tenant est inconnu). */
    @Transactional(readOnly = true)
    public List<DemandeAffichee> nonTraitees(Tenant tenant) {
        List<DemandeSocieteConnue> lignes = dansLePerimetre(tenant);
        Map<Long, SocieteIdentite> societes = identites
                .findAllById(lignes.stream().map(DemandeSocieteConnue::getSocieteIdentiteId).distinct().toList())
                .stream().collect(Collectors.toMap(SocieteIdentite::getId, Function.identity()));
        return lignes.stream().map(d -> {
            SocieteIdentite s = societes.get(d.getSocieteIdentiteId());
            return new DemandeAffichee(d.getId(), d.getEmailDemandeur(), d.getNomDemandeur(), d.getCreeA(),
                    s == null ? null : s.getSiren(), s == null ? null : s.getTva());
        }).toList();
    }

    @Transactional(readOnly = true)
    public int compter(Tenant tenant) {
        return dansLePerimetre(tenant).size();
    }

    /**
     * Marque la demande traitee si elle est non traitee ET dans le perimetre du tenant ; sinon renvoie
     * {@code false} sans rien modifier (l'appelant repond 404 : aucune difference entre « hors perimetre » et
     * « inexistante »).
     */
    @Transactional
    public boolean marquerTraitee(Long id, Tenant tenant) {
        Optional<DemandeSocieteConnue> trouvee = dansLePerimetre(tenant).stream()
                .filter(d -> d.getId().equals(id)).findFirst();
        trouvee.ifPresent(d -> d.setTraitee(true));
        return trouvee.isPresent();
    }

    private List<DemandeSocieteConnue> dansLePerimetre(Tenant tenant) {
        if (tenant == null || tenant.getId() == null) {
            return List.of();
        }
        if (estMaitre(tenant)) {
            return demandes.nonTraiteesDuMaitre();
        }
        return demandes.nonTraiteesDuPartenaire(tenant.getId());
    }

    private boolean estMaitre(Tenant tenant) {
        return serverRoleProperties.getRole() == ServerRole.MASTER
                && tenantRepository.findFirstByOrderByIdAsc()
                        .map(premier -> premier.getId().equals(tenant.getId())).orElse(false);
    }
}
