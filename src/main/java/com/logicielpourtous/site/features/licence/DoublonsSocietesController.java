package com.logicielpourtous.site.features.licence;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.identite.RapportDoublonsService;

/**
 * Page d'administration {@code /intra/societes/doublons} (Maitre uniquement, comme les autres ecrans de
 * licence) : rapport des societes qui partagent un SIREN ou une TVA dans les tables existantes. Lecture
 * seule, aucune fusion automatique. Place dans le module {@code licence} (qui porte les ecrans reserves
 * au Maitre) pour que le module {@code identite} reste independant.
 */
@Controller
@RequestMapping("/intra/societes/doublons")
@PreAuthorize("hasRole('ADMIN')")
public class DoublonsSocietesController {

    private final RapportDoublonsService rapportDoublonsService;
    private final ServerRoleProperties serverRoleProperties;

    public DoublonsSocietesController(RapportDoublonsService rapportDoublonsService,
                                      ServerRoleProperties serverRoleProperties) {
        this.rapportDoublonsService = rapportDoublonsService;
        this.serverRoleProperties = serverRoleProperties;
    }

    @GetMapping
    public String page(Model model) {
        requireMaster();
        model.addAttribute("groupes", rapportDoublonsService.rapport());
        return "feature/licence/doublons";
    }

    private void requireMaster() {
        if (serverRoleProperties.getRole() != ServerRole.MASTER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
