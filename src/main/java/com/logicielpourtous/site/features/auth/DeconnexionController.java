package com.logicielpourtous.site.features.auth;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.logicielpourtous.site.config.AutheliaLogoutSuccessHandler;

/**
 * Page affichee apres la deconnexion d'un compte unique. Le lien « Fermer aussi la session du compte unique » n'est
 * propose que pour {@code sso=1} ET un portail Authelia configure en https ; son adresse vient UNIQUEMENT de la
 * configuration, jamais de la requete.
 */
@Controller
public class DeconnexionController {

    private static final String CLE_ISSUER = "spring.security.oauth2.client.provider.authelia.issuer-uri";

    private final Environment env;

    public DeconnexionController(Environment env) {
        this.env = env;
    }

    @GetMapping("/deconnecte")
    public String deconnecte(@RequestParam(name = "sso", required = false) String sso, Model model) {
        String lien = "1".equals(sso) ? AutheliaLogoutSuccessHandler.urlDeconnexionPortail(env.getProperty(CLE_ISSUER)) : null;
        model.addAttribute("lienAuthelia", lien);
        return "feature/auth/deconnecte";
    }
}
