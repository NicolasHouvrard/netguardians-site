package com.logicielpourtous.site.features.licence;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.sync.RequestSyncApplier;
import com.logicielpourtous.site.features.sync.RequestSyncPayload;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;
import com.logicielpourtous.site.features.sync.SyncConflict;
import com.logicielpourtous.site.features.sync.SyncConflictRepository;

/**
 * Ecran I-4 : gestion administrative des {@code SecondaryServer}/{@code SyncConflict} du moteur de
 * synchronisation ({@code features.sync}, déjà livré et revu), aujourd'hui gérables seulement via du
 * SQL manuel. Reservé au role {@code ROLE_ADMIN} (meme garde que les autres ecrans {@code intra}
 * administratifs du depot), MAIS — contrairement a {@link CompteLicenceAdminController} (reserve au
 * role {@code app.role=MASTER}) — les deux ecrans principaux ({@code /serveurs}, {@code /conflits})
 * sont disponibles quel que soit le role de CETTE instance : un {@code PARTNER} gere lui aussi SES
 * propres {@code SecondaryServer} (ses {@code CLIENT} enroles) et fait autorite de resolution sur SES
 * propres {@code SyncConflict}, exactement comme un {@code MASTER} le fait pour ses {@code
 * PARTNER}/{@code CLIENT}. Seule la generation de jeton d'installation (bouton par {@code Customer})
 * est reservee aux instances {@code PARTNER} (voir {@link #genererJeton}).
 */
@Controller
@RequestMapping("/intra/licence")
@PreAuthorize("hasRole('ADMIN')")
public class GestionServeursController {

    /**
     * Duree de validite d'un code d'enrolement genere MANUELLEMENT par un admin sur cet ecran : plus
     * longue que les 15 min de {@code LicenceEnrolementController#DUREE_CODE_ENROLEMENT} (echange
     * immediat par un flux automatique) car ce code est transmis hors-perimetre — l'admin le
     * communique a qui installe le serveur secondaire — avant d'etre consomme via le {@code POST
     * /api/sync/enroll} deja existant ; meme raisonnement que {@code
     * LicenceEnrolementController#DUREE_VALIDITE_JETON}.
     */
    private static final Duration DUREE_CODE_MANUEL = Duration.ofDays(7);

    private final SecondaryServerRepository secondaryServerRepository;
    private final SyncConflictRepository syncConflictRepository;
    private final RequestRepository requestRepository;
    private final RequestSyncApplier requestSyncApplier;
    private final CustomerRepository customerRepository;
    private final ServerRoleProperties serverRoleProperties;
    private final ObjectMapper objectMapper;
    private final Optional<LicenceClient> licenceClient;

    public GestionServeursController(SecondaryServerRepository secondaryServerRepository,
                                     SyncConflictRepository syncConflictRepository,
                                     RequestRepository requestRepository,
                                     RequestSyncApplier requestSyncApplier,
                                     CustomerRepository customerRepository,
                                     ServerRoleProperties serverRoleProperties,
                                     ObjectMapper objectMapper,
                                     Optional<LicenceClient> licenceClient) {
        this.secondaryServerRepository = secondaryServerRepository;
        this.syncConflictRepository = syncConflictRepository;
        this.requestRepository = requestRepository;
        this.requestSyncApplier = requestSyncApplier;
        this.customerRepository = customerRepository;
        this.serverRoleProperties = serverRoleProperties;
        this.objectMapper = objectMapper;
        this.licenceClient = licenceClient;
    }

    // ── Ecran /intra/licence/serveurs ─────────────────────────────────────────

    @GetMapping("/serveurs")
    public String serveurs(Model model) {
        model.addAttribute("serveurs", secondaryServerRepository.findAll());
        model.addAttribute("customers", customerRepository.findCustomers());
        model.addAttribute("estPartner", serverRoleProperties.getRole() == ServerRole.PARTNER);
        return "feature/licence/serveurs";
    }

    /**
     * Cree un {@link SecondaryServer} (aucun autre mecanisme ne le fait aujourd'hui pour cette
     * entite — contrairement a {@link LicenceServeur}, elle n'a pas d'auto-enrolement) ET lui genere
     * immediatement un code d'enrolement, en un seul envoi de formulaire : c'est exactement ce que le
     * SQL manuel faisait (INSERT + code), remplace ici par cet ecran.
     */
    @PostMapping("/serveurs")
    public String creerServeur(@RequestParam String nom,
                               @RequestParam ServerRole role,
                               @RequestParam(required = false) Long customerId,
                               RedirectAttributes ra) {
        if (role != ServerRole.CLIENT && role != ServerRole.PARTNER) {
            ra.addFlashAttribute("errorMessage", "Rôle invalide : un serveur secondaire est CLIENT ou PARTNER.");
            return "redirect:/intra/licence/serveurs";
        }
        if (customerId != null && customerRepository.findById(customerId).isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Client introuvable : aucun serveur créé.");
            return "redirect:/intra/licence/serveurs";
        }
        SecondaryServer serveur = new SecondaryServer();
        serveur.setNom(nom);
        serveur.setRole(role);
        serveur.setCustomerId(customerId);
        String code = genererCodeManuel(serveur);
        secondaryServerRepository.save(serveur);
        ra.addFlashAttribute("successMessage",
                "Serveur créé. Code d'enrôlement à transmettre (POST /api/sync/enroll) : " + code);
        return "redirect:/intra/licence/serveurs";
    }

    @PostMapping("/serveurs/{id}/revoquer")
    public String revoquerServeur(@PathVariable Long id, RedirectAttributes ra) {
        SecondaryServer serveur = secondaryServerRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        serveur.setStatut(SecondaryServer.SecondaryServerStatut.REVOQUE);
        secondaryServerRepository.save(serveur);
        ra.addFlashAttribute("successMessage", "Serveur révoqué.");
        return "redirect:/intra/licence/serveurs";
    }

    /**
     * Code a usage unique, haute entropie : {@link UUID#randomUUID()} directement — pas de classe
     * partagee dediee pour ce seul besoin (ni {@code SyncApiKeys} ni {@code LicenceCles}, qui portent
     * chacun une semantique de module distincte ; en creer une 3e pour une simple chaine aleatoire
     * serait une indirection sans valeur ici).
     */
    private String genererCodeManuel(SecondaryServer serveur) {
        String code = UUID.randomUUID().toString();
        serveur.setEnrollmentCode(code);
        serveur.setEnrollmentCodeExpiresAt(Instant.now().plus(DUREE_CODE_MANUEL));
        return code;
    }

    // ── Generation de jeton d'installation CLIENT (instances PARTNER uniquement) ──

    /**
     * Appelle le maitre ({@link LicenceClient#genererJeton}) pour obtenir un jeton d'installation
     * pour {@code customerId}. Reservee aux instances {@code PARTNER} (404 sinon, meme choix que
     * {@link CompteLicenceAdminController#requireMaster}).
     *
     * <p><b>Validation du {@code customerId}</b> : {@code Customer} n'a aucune notion de
     * partenaire/tenant proprietaire (confirme Task 4) — n'importe quel client actif peut donc
     * legitimement apparaitre ici, PAS de controle de perimetre additionnel a faire. On verifie
     * neanmoins que le {@code Customer} existe et est actif AVANT d'appeler le maitre : un id
     * invalide/perime ne doit pas generer un jeton inutilisable (le maitre, lui, ne valide pas
     * l'existence du client — Task 4 — mais ce n'est pas une raison pour que CET ecran propage un id
     * de toute facon invalide).
     */
    @PostMapping("/serveurs/jetons")
    public String genererJeton(@RequestParam Long customerId, RedirectAttributes ra) {
        if (serverRoleProperties.getRole() != ServerRole.PARTNER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (licenceClient.isEmpty()) {
            ra.addFlashAttribute("errorMessage",
                    "Génération de jeton indisponible : app.licence.master-url non configurée.");
            return "redirect:/intra/licence/serveurs";
        }
        Customer customer = customerRepository.findById(customerId).filter(Customer::isActive).orElse(null);
        if (customer == null) {
            ra.addFlashAttribute("errorMessage", "Client introuvable ou inactif.");
            return "redirect:/intra/licence/serveurs";
        }
        try {
            LicenceClient.JetonGenere jeton = licenceClient.get().genererJeton(customerId);
            ra.addFlashAttribute("successMessage", "Jeton d'installation pour " + customer.getCompanyName()
                    + " : " + jeton.jeton() + " (expire le " + jeton.expireA() + ")");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("errorMessage", "Échec de la génération du jeton : " + e.getMessage());
        }
        return "redirect:/intra/licence/serveurs";
    }

    // ── Ecran /intra/licence/conflits ──────────────────────────────────────────

    @GetMapping("/conflits")
    public String conflits(Model model) {
        model.addAttribute("conflits", syncConflictRepository.findByResolu(false));
        return "feature/licence/conflits";
    }

    @PostMapping("/conflits/{id}/appliquer")
    @Transactional
    public String appliquerConflit(@PathVariable Long id, RedirectAttributes ra) {
        SyncConflict conflit = conflitNonResoluOuNull(id);
        if (conflit == null) {
            ra.addFlashAttribute("errorMessage", "Conflit introuvable ou déjà tranché.");
            return "redirect:/intra/licence/conflits";
        }
        Request cible = requestRepository.findBySyncId(conflit.getEntitySyncId()).orElse(null);
        if (cible == null) {
            ra.addFlashAttribute("errorMessage", "Demande associée introuvable (supprimée ?).");
            return "redirect:/intra/licence/conflits";
        }
        RequestSyncPayload payload = lirePayload(conflit.getPayloadRecu());
        requestSyncApplier.appliquer(cible, payload);
        conflit.setResolu(true);
        syncConflictRepository.save(conflit);
        ra.addFlashAttribute("successMessage", "Conflit résolu : payload reçu appliqué.");
        return "redirect:/intra/licence/conflits";
    }

    @PostMapping("/conflits/{id}/garder")
    @Transactional
    public String garderEtatActuel(@PathVariable Long id, RedirectAttributes ra) {
        SyncConflict conflit = conflitNonResoluOuNull(id);
        if (conflit == null) {
            ra.addFlashAttribute("errorMessage", "Conflit introuvable ou déjà tranché.");
            return "redirect:/intra/licence/conflits";
        }
        conflit.setResolu(true);
        syncConflictRepository.save(conflit);
        ra.addFlashAttribute("successMessage", "Conflit résolu : état actuel conservé.");
        return "redirect:/intra/licence/conflits";
    }

    /**
     * Verrou pessimiste (voir {@link SyncConflictRepository#findByIdForUpdate}) : deux resolutions
     * concurrentes du MEME conflit (double-clic, ou "appliquer" et "garder" cliques presque en meme
     * temps sur 2 onglets) ne peuvent pas toutes les deux s'executer — la 2e, apres avoir attendu,
     * relit {@code resolu=true} et renvoie {@code null} sans rien refaire. A appeler dans une
     * transaction (methodes {@code @Transactional} ci-dessus).
     */
    private SyncConflict conflitNonResoluOuNull(Long id) {
        return syncConflictRepository.findByIdForUpdate(id)
                .filter(c -> !c.isResolu())
                .orElse(null);
    }

    private RequestSyncPayload lirePayload(String payloadRecu) {
        try {
            return objectMapper.readValue(payloadRecu, RequestSyncPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Payload de conflit illisible", e);
        }
    }
}
