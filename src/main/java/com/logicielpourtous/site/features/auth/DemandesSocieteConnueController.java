package com.logicielpourtous.site.features.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tableau de bord du responsable d'une societe : demandes de personnes qui ont tente d'inscrire une societe deja
 * connue. Reserve aux administrateurs ; chaque demande est lue et marquee dans le seul perimetre du connecte
 * (404 en dehors, jamais 403 : aucune fuite d'existence).
 */
@Controller
@RequestMapping("/intra/demandes-societe-connue")
@PreAuthorize("hasRole('ADMIN')")
public class DemandesSocieteConnueController {

    private final DemandesSocieteConnueService service;

    public DemandesSocieteConnueController(DemandesSocieteConnueService service) {
        this.service = service;
    }

    @GetMapping
    public String liste(@AuthenticationPrincipal CustomUserDetails compte, Model model) {
        model.addAttribute("demandes", service.nonTraitees(compte == null ? null : compte.getTenant()));
        return "feature/auth/demandes-societe-connue";
    }

    @PostMapping("/{id}/traitee")
    public String marquerTraitee(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails compte) {
        if (!service.marquerTraitee(id, compte == null ? null : compte.getTenant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/intra/demandes-societe-connue";
    }
}
