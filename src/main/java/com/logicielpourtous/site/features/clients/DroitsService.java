package com.logicielpourtous.site.features.clients;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.plateforme.ControleDroits;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.AppartenanceRepository;

/**
 * Droits a trois couches sur une societe cliente : le plafond (fixe par le partenaire referent), les reglages
 * du manager (droits accordes a un role, dans la limite du plafond) et l'utilisateur (ses roles dans la societe).
 * Le refus est la valeur par defaut : un droit n'est accorde que si les trois couches l'autorisent.
 */
@Service
public class DroitsService implements ControleDroits {

    private final PlafondClientRepository plafonds;
    private final ReglageManagerRepository reglages;
    private final AppartenanceRepository appartenances;

    public DroitsService(PlafondClientRepository plafonds, ReglageManagerRepository reglages,
            AppartenanceRepository appartenances) {
        this.plafonds = plafonds;
        this.reglages = reglages;
        this.appartenances = appartenances;
    }

    /** Remplace le plafond de la societe cliente ; un ensemble vide retire tout (tout est alors refuse). */
    @Transactional
    public void fixerPlafond(Long clientSocieteId, Set<String> droits) {
        if (clientSocieteId == null) {
            throw new IllegalArgumentException("La societe cliente doit etre renseignee.");
        }
        Set<String> valides = valider(droits);
        plafonds.deleteByClientSocieteId(clientSocieteId);
        plafonds.flush();
        plafonds.saveAll(valides.stream().map(d -> new PlafondClient(clientSocieteId, d)).toList());
    }

    /**
     * Remplace les droits accordes a un role dans la societe. Leve {@link DepassePlafondException} (sans rien
     * ecrire) si un droit depasse le plafond courant.
     */
    @Transactional
    public void accorderAuRole(Long clientSocieteId, String roleNom, Set<String> droits) {
        if (clientSocieteId == null) {
            throw new IllegalArgumentException("La societe cliente doit etre renseignee.");
        }
        if (roleNom == null || roleNom.isBlank()) {
            throw new IllegalArgumentException("Le role doit etre renseigne.");
        }
        Set<String> valides = valider(droits);
        Set<String> plafond = plafonds.findByClientSocieteId(clientSocieteId).stream()
                .map(PlafondClient::getDroit).collect(Collectors.toSet());
        Set<String> horsPlafond = valides.stream().filter(d -> !plafond.contains(d))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!horsPlafond.isEmpty()) {
            throw new DepassePlafondException("Droits hors du plafond autorise par le partenaire : "
                    + String.join(", ", horsPlafond) + ".");
        }
        reglages.deleteByClientSocieteIdAndRoleNom(clientSocieteId, roleNom);
        reglages.flush();
        reglages.saveAll(valides.stream().map(d -> new ReglageManager(clientSocieteId, roleNom, d)).toList());
    }

    /**
     * Vrai seulement si le droit est dans le plafond de la societe ET accorde a l'un des roles que
     * l'utilisateur detient dans une appartenance a CETTE societe. Faux pour toute entree absente ou inconnue.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean autorise(Long userId, Long societeId, String droit) {
        if (userId == null || societeId == null || droit == null || droit.isBlank()) {
            return false;
        }
        if (!plafonds.existsByClientSocieteIdAndDroit(societeId, droit)) {
            return false;
        }
        Set<String> roles = appartenances.findByUserIdAndSocieteId(userId, societeId).stream()
                .flatMap(a -> a.getRoles().stream())
                .map(Role::getName)
                .collect(Collectors.toSet());
        if (roles.isEmpty()) {
            return false;
        }
        return reglages.existsByClientSocieteIdAndDroitAndRoleNomIn(societeId, droit, roles);
    }

    /**
     * Droits du role manager de la societe cliente dans la limite du plafond (intersection). Vide si pas de plafond
     * (echec ferme) ou si le manager n'a rien accorde.
     */
    @Transactional(readOnly = true)
    public Set<String> droitsEffectifsDuManager(Long clientSocieteId) {
        if (clientSocieteId == null) {
            return Set.of();
        }
        Set<String> plafond = plafonds.findByClientSocieteId(clientSocieteId).stream()
                .map(PlafondClient::getDroit).collect(Collectors.toSet());
        return reglages.findByClientSocieteIdAndRoleNom(clientSocieteId, ManagersService.ROLE_MANAGER).stream()
                .map(ReglageManager::getDroit).filter(plafond::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> valider(Set<String> droits) {
        if (droits == null) {
            throw new IllegalArgumentException("Les droits doivent etre renseignes.");
        }
        for (String d : droits) {
            if (d == null || d.isBlank()) {
                throw new IllegalArgumentException("Un droit ne peut pas etre vide.");
            }
        }
        return Set.copyOf(droits);
    }
}
