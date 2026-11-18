package com.logicielpourtous.site.features.clients;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Regles des managers d'une societe cliente : une societe a toujours au moins un manager ; le premier utilisateur
 * ajoute en est un d'office ; le statut de manager ne peut etre retire (ni le manager supprime) que s'il en reste un
 * autre, donc seulement si la societe compte au moins deux utilisateurs. L'autorisation de l'appelant est a sa charge.
 * <p>
 * Le comptage se fait sur les appartenances de la societe ({@code societe_id}). Chaque operation verrouille d'abord
 * la ligne de la societe ({@code FOR UPDATE}) : deux operations concurrentes sur une meme societe sont serialisees,
 * ce qui evite qu'elles retirent chacune « l'autre » manager.
 */
@Service
public class ManagersService {

    /** Role manager d'une societe cliente (decision du proprietaire) : la seule source du nom. */
    public static final String ROLE_MANAGER = "ROLE_GESTIONNAIRE";

    private final AppartenanceRepository appartenances;
    private final UserRepository users;
    private final RoleRepository roles;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;

    public ManagersService(AppartenanceRepository appartenances, UserRepository users, RoleRepository roles,
            TenantRepository tenants, JdbcTemplate jdbc) {
        this.appartenances = appartenances;
        this.users = users;
        this.roles = roles;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /**
     * Ajoute un utilisateur a une societe. S'il est le premier, il recoit d'office le role manager. Le tenant de
     * l'appartenance est celui du proprietaire de la societe ({@code societes_identites.proprietaire_tenant_id})
     * quand il est connu, sinon celui de l'utilisateur (comme a l'inscription d'un client).
     */
    @Transactional
    public Appartenance ajouterUtilisateur(Long userId, Long societeId, Set<String> rolesDemandes) {
        exiger(userId, "L'utilisateur doit etre renseigne.");
        exiger(societeId, "La societe doit etre renseignee.");
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur inconnu : " + userId));
        Set<Role> rolesAAttribuer = new HashSet<>();
        for (String nom : rolesDemandes == null ? Set.<String>of() : rolesDemandes) {
            if (nom == null || nom.isBlank()) {
                throw new IllegalArgumentException("Un nom de role est vide.");
            }
            rolesAAttribuer.add(roles.findByName(nom)
                    .orElseThrow(() -> new IllegalArgumentException("Role inconnu : " + nom)));
        }
        Long proprietaireTenantId = verrouillerSociete(societeId);
        Tenant tenant = determinerTenant(user, proprietaireTenantId, societeId);

        List<Appartenance> membres = appartenances.findBySocieteIdOrderById(societeId);
        if (membres.stream().anyMatch(a -> userId.equals(a.getUserId()))) {
            throw new IllegalStateException("L'utilisateur " + userId + " appartient deja a la societe " + societeId + ".");
        }
        if (membres.isEmpty()) {
            rolesAAttribuer.add(roleManager());
        }
        Appartenance a = new Appartenance();
        a.setUserId(userId);
        a.setTenant(tenant);
        a.setSocieteId(societeId);
        a.setRoles(rolesAAttribuer);
        return appartenances.save(a);
    }

    /** Retire le statut de manager d'un utilisateur ; refuse s'il laisserait la societe sans manager. */
    @Transactional
    public void retirerRoleManager(Long userId, Long societeId) {
        exiger(userId, "L'utilisateur doit etre renseigne.");
        exiger(societeId, "La societe doit etre renseignee.");
        verrouillerSociete(societeId);
        List<Appartenance> membres = appartenances.findBySocieteIdOrderById(societeId);
        List<Appartenance> siennes = membres.stream().filter(a -> userId.equals(a.getUserId())).toList();
        if (siennes.isEmpty()) {
            throw new IllegalArgumentException("L'utilisateur " + userId + " n'appartient pas a la societe " + societeId + ".");
        }
        if (siennes.stream().noneMatch(ManagersService::estManager)) {
            throw new IllegalStateException("L'utilisateur " + userId + " n'est pas manager de la societe " + societeId + ".");
        }
        if (nombreUtilisateurs(membres) < 2) {
            throw new DernierManagerException("La societe n'a qu'un seul utilisateur : son statut de manager ne peut pas etre retire.");
        }
        if (nombreManagers(membres) < 2) {
            throw new DernierManagerException("Impossible de retirer le dernier manager : une societe doit toujours avoir un manager.");
        }
        for (Appartenance a : siennes) {
            a.getRoles().removeIf(r -> ROLE_MANAGER.equals(r.getName()));
            appartenances.save(a);
        }
    }

    /** Retire un utilisateur de la societe ; refuse si c'est son dernier manager. */
    @Transactional
    public void retirerUtilisateur(Long userId, Long societeId) {
        exiger(userId, "L'utilisateur doit etre renseigne.");
        exiger(societeId, "La societe doit etre renseignee.");
        verrouillerSociete(societeId);
        List<Appartenance> membres = appartenances.findBySocieteIdOrderById(societeId);
        List<Appartenance> siennes = membres.stream().filter(a -> userId.equals(a.getUserId())).toList();
        if (siennes.isEmpty()) {
            throw new IllegalArgumentException("L'utilisateur " + userId + " n'appartient pas a la societe " + societeId + ".");
        }
        if (siennes.stream().anyMatch(ManagersService::estManager) && nombreManagers(membres) < 2) {
            throw new DernierManagerException("Impossible de retirer le dernier manager de la societe : "
                    + "nommez d'abord un autre manager.");
        }
        appartenances.deleteAll(siennes);
    }

    private static void exiger(Object valeur, String message) {
        if (valeur == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean estManager(Appartenance a) {
        return a.getRoles().stream().anyMatch(r -> ROLE_MANAGER.equals(r.getName()));
    }

    private static long nombreUtilisateurs(List<Appartenance> membres) {
        return membres.stream().map(Appartenance::getUserId).distinct().count();
    }

    private static long nombreManagers(List<Appartenance> membres) {
        return membres.stream().filter(ManagersService::estManager).map(Appartenance::getUserId)
                .collect(Collectors.toSet()).size();
    }

    private Role roleManager() {
        return roles.findByName(ROLE_MANAGER).orElseThrow(
                () -> new IllegalStateException("Le role " + ROLE_MANAGER + " n'existe pas : migration V15 non appliquee."));
    }

    /** Verrouille la ligne de la societe (existence verifiee) et rend son tenant proprietaire (peut etre nul). */
    private Long verrouillerSociete(Long societeId) {
        List<Long> lignes = jdbc.query("SELECT proprietaire_tenant_id FROM societes_identites WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getObject(1, Long.class), societeId);
        if (lignes.isEmpty()) {
            throw new IllegalArgumentException("Societe inconnue : " + societeId);
        }
        return lignes.get(0);
    }

    private Tenant determinerTenant(User user, Long proprietaireTenantId, Long societeId) {
        if (proprietaireTenantId != null) {
            return tenants.findById(proprietaireTenantId).orElseThrow(() -> new IllegalStateException(
                    "Le tenant proprietaire de la societe " + societeId + " est introuvable."));
        }
        if (user.getTenant() == null) {
            throw new IllegalStateException("Ni la societe " + societeId + " ni l'utilisateur " + user.getId()
                    + " n'ont de tenant : appartenance impossible.");
        }
        return user.getTenant();
    }
}
