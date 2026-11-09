package com.logicielpourtous.site.features.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import com.logicielpourtous.site.features.auth.RepriseRegistreService.BilanReprise;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;

/**
 * Page {@code /intra/societes/reprise} (Maitre uniquement, comme {@code DoublonsSocietesController}) : le GET
 * affiche la simulation (rien n'est ecrit), le POST (protege par CSRF, voir {@code csrfCible()}) execute la
 * reprise des societes existantes dans le registre et affiche son bilan.
 */
@Controller
@RequestMapping("/intra/societes/reprise")
@PreAuthorize("hasRole('ADMIN')")
public class RepriseRegistreController {

    private final RepriseRegistreService service;
    private final ServerRoleProperties serverRoleProperties;

    public RepriseRegistreController(RepriseRegistreService service, ServerRoleProperties serverRoleProperties) {
        this.service = service;
        this.serverRoleProperties = serverRoleProperties;
    }

    @GetMapping
    public String simulation(Model model) {
        requireMaster();
        afficher(model, service.reprendre(true), true);
        return "feature/licence/reprise-registre";
    }

    @PostMapping
    public String executer(Model model) {
        requireMaster();
        afficher(model, service.reprendre(false), false);
        return "feature/licence/reprise-registre";
    }

    private static void afficher(Model model, BilanReprise bilan, boolean simulation) {
        model.addAttribute("bilan", bilan);
        model.addAttribute("simulation", simulation);
    }

    private void requireMaster() {
        if (serverRoleProperties.getRole() != ServerRole.MASTER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
