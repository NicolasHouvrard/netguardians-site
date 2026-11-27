package com.logicielpourtous.site.features.auth;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.logicielpourtous.site.config.AutheliaLogoutSuccessHandler;
import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;

/**
 * Page affichee apres la deconnexion d'un compte unique. Le lien « Fermer aussi la session du compte unique » n'est
 * propose que pour {@code sso=1} ET un portail Authelia configure en https ; son adresse vient UNIQUEMENT de la
 * configuration, jamais de la requete.
 */
@Controller
public class DeconnexionController {

    private final ConfigurationCompteUnique compteUnique;

    public DeconnexionController(ConfigurationCompteUnique compteUnique) {
        this.compteUnique = compteUnique;
    }

    @GetMapping("/deconnecte")
    public String deconnecte(@RequestParam(name = "sso", required = false) String sso, Model model) {
        String lien = "1".equals(sso) ? AutheliaLogoutSuccessHandler.urlDeconnexionPortail(compteUnique.issuerUri()) : null;
        model.addAttribute("lienAuthelia", lien);
        return "feature/auth/deconnecte";
    }
}
