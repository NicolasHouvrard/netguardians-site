package com.logicielpourtous.site.features.licence;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.commun.TenantRepository;

/**
 * Ecran admin {@code /intra/licence/federation} (Maitre uniquement, comme
 * {@code CompteLicenceAdminController}) : serveur d'accueil et partenaire de chaque tenant, URL
 * publique de chaque serveur, serveur par defaut des nouveaux tenants. Les regles sont dans
 * {@link ServeurAccueilService} ; une entree refusee y leve une {@link IllegalArgumentException},
 * affichee ici comme message d'erreur sans rien modifier.
 */
@Controller
@RequestMapping("/intra/licence/federation")
@PreAuthorize("hasRole('ADMIN')")
public class FederationAdminController {

    private static final String PAGE = "redirect:/intra/licence/federation";

    private final ServeurAccueilService serveurAccueilService;
    private final TenantRepository tenantRepository;
    private final LicenceServeurRepository licenceServeurRepository;
    private final ServerRoleProperties serverRoleProperties;

    public FederationAdminController(ServeurAccueilService serveurAccueilService,
                                     TenantRepository tenantRepository,
                                     LicenceServeurRepository licenceServeurRepository,
                                     ServerRoleProperties serverRoleProperties) {
        this.serveurAccueilService = serveurAccueilService;
        this.tenantRepository = tenantRepository;
        this.licenceServeurRepository = licenceServeurRepository;
        this.serverRoleProperties = serverRoleProperties;
    }

    @GetMapping
    public String page(Model model) {
        requireMaster();
        model.addAttribute("tenants", tenantRepository.findAll(Sort.by("name")));
        model.addAttribute("serveurs", licenceServeurRepository.findAll(Sort.by("id")));
        model.addAttribute("serveurDefautId", serveurAccueilService.serveurParDefautId().orElse(null));
        // Serveurs revoques : un tenant encore rattache a l'un d'eux doit l'afficher (et non "Maitre").
        model.addAttribute("serveursRevoquesIds", licenceServeurRepository.findByStatut(
                        LicenceServeur.LicenceServeurStatut.REVOQUE).stream()
                .map(LicenceServeur::getId).collect(java.util.stream.Collectors.toSet()));
        return "feature/licence/federation";
    }

    @PostMapping("/tenants/{id}")
    public String rattacherTenant(@PathVariable Long id,
                                  @RequestParam(required = false) Long serveurLicenceId,
                                  @RequestParam(required = false) Long tenantPartenaireId,
                                  RedirectAttributes ra) {
        requireMaster();
        try {
            serveurAccueilService.rattacherTenant(id, serveurLicenceId, tenantPartenaireId);
            ra.addFlashAttribute("successMessage", "Rattachement enregistré.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/serveurs/{id}/url")
    public String definirUrlPublique(@PathVariable Long id,
                                     @RequestParam(required = false) String urlPublique,
                                     RedirectAttributes ra) {
        requireMaster();
        try {
            LicenceServeur serveur = licenceServeurRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Serveur introuvable."));
            serveur.setUrlPublique(serveurAccueilService.normaliserUrlPublique(urlPublique));
            licenceServeurRepository.save(serveur);
            ra.addFlashAttribute("successMessage", "URL publique enregistrée.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/defaut")
    public String definirServeurParDefaut(@RequestParam(required = false) Long serveurId,
                                          RedirectAttributes ra) {
        requireMaster();
        try {
            serveurAccueilService.definirServeurParDefaut(serveurId);
            ra.addFlashAttribute("successMessage", "Serveur par défaut enregistré.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    private void requireMaster() {
        if (serverRoleProperties.getRole() != ServerRole.MASTER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
