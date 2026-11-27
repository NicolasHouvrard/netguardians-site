package com.logicielpourtous.site.features.sso;

import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl;
import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesEnBase;
import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesModifies;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Page « Authentification SSO » : etat de Google/Microsoft (lecture seule) et section « Compte unique (Authelia) » :
 * reglages en base, test de connexion, generation du secret client. Le secret n'est JAMAIS affiche ni renvoye ; le
 * seul affichage ponctuel est l'EMPREINTE a coller dans Authelia, dans la reponse du POST de generation.
 * Acces : {@code /admin/**} exige ROLE_ADMIN (SecurityConfig), CSRF actif sur tous les POST.
 */
@Controller
@RequestMapping("/admin/sso-config")
public class SsoStatusController {

    private static final Logger LOG = LoggerFactory.getLogger(SsoStatusController.class);
    private static final String VUE = "admin/sso-config";

    private final MailAccountRepository          mailAccountRepository;
    private final ClientRegistrationRepository   clientRegistrationRepository;
    private final ConfigurationCompteUniqueImpl  config;
    private final ServerRoleProperties           serverRole;
    private final TestConnexionAuthelia          testeur;

    public SsoStatusController(MailAccountRepository mailAccountRepository,
                                ClientRegistrationRepository clientRegistrationRepository,
                                ConfigurationCompteUniqueImpl config,
                                ServerRoleProperties serverRole,
                                TestConnexionAuthelia testeur) {
        this.mailAccountRepository        = mailAccountRepository;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.config                       = config;
        this.serverRole                   = serverRole;
        this.testeur                      = testeur;
    }

    @GetMapping
    public String index(Model model) {
        return afficher(model, null, Map.of());
    }

    @PostMapping("/reglages")
    public String enregistrer(@RequestParam(required = false) String issuerUri,
                              @RequestParam(required = false) String clientId,
                              @RequestParam(required = false) String urlPublique,
                              @RequestParam(required = false) String retourDeconnexion,
                              @RequestParam(required = false) String maitreUrlPublique,
                              @RequestParam(required = false) String nouveauSecret,
                              Authentication auth, Model model, RedirectAttributes flash) {
        boolean maitre = serverRole.getRole() == ServerRole.MASTER;
        ReglagesModifies saisie = new ReglagesModifies(rogne(issuerUri), rogne(clientId), nouveauSecret,
                rogne(urlPublique), maitre ? rogne(maitreUrlPublique) : null, rogne(retourDeconnexion));
        ReglagesEnBase avant = config.lireReglagesEnBase();

        List<String> champs = new ArrayList<>();
        ajouterSiChange(champs, "issuerUri", saisie.issuerUri(), avant.issuerUri());
        ajouterSiChange(champs, "clientId", saisie.clientId(), avant.clientId());
        ajouterSiChange(champs, "urlPublique", saisie.urlPublique(), avant.urlPublique());
        ajouterSiChange(champs, "retourDeconnexion", saisie.retourDeconnexion(), avant.retourDeconnexion());
        ajouterSiChange(champs, "maitreUrlPublique", saisie.maitreUrlPublique(), avant.maitreUrlPublique());
        if (nouveauSecret != null && !nouveauSecret.isBlank()) {
            champs.add("secret");
        }

        if (champs.isEmpty()) {
            flash.addFlashAttribute("message", "Aucune modification.");
            return "redirect:/admin/sso-config";
        }
        try {
            config.enregistrer(saisie, auteur(auth));
        } catch (IllegalArgumentException e) {
            // Messages fixes de la validation (jamais une valeur saisie) ; le secret saisi n'est pas reaffiche.
            Map<String, String> erreurs = new LinkedHashMap<>();
            erreurs.put(champDe(e.getMessage()), e.getMessage());
            LOG.info("Compte unique : reglages refuses pour {} (champ : {})", auteur(auth), erreurs.keySet().iterator().next());
            Map<String, String> valeurs = new LinkedHashMap<>();
            valeurs.put("issuerUri", nn(saisie.issuerUri()));
            valeurs.put("clientId", nn(saisie.clientId()));
            valeurs.put("urlPublique", nn(saisie.urlPublique()));
            valeurs.put("retourDeconnexion", nn(saisie.retourDeconnexion()));
            valeurs.put("maitreUrlPublique", maitre ? nn(saisie.maitreUrlPublique()) : avant.maitreUrlPublique());
            return afficher(model, valeurs, erreurs);
        }
        LOG.info("Compte unique : reglages modifies par {} (champs : {})", auteur(auth), String.join(", ", champs));
        flash.addFlashAttribute("message", "Reglages enregistres : ils sont appliques sans redemarrage.");
        return "redirect:/admin/sso-config";
    }

    @PostMapping("/effacer-secret")
    public String effacerSecret(Authentication auth, RedirectAttributes flash) {
        config.effacerSecret(auteur(auth));
        LOG.info("Compte unique : secret client efface par {}", auteur(auth));
        flash.addFlashAttribute("message", "Secret du client efface.");
        return "redirect:/admin/sso-config";
    }

    @PostMapping("/tester")
    public String tester(Authentication auth, RedirectAttributes flash) {
        TestConnexionAuthelia.Resultat r = testeur.tester(config.issuerUri());
        String message = switch (r) {
            case JOIGNABLE -> "Authelia est joignable.";
            case INJOIGNABLE -> "Authelia est injoignable.";
            case REPONSE_INATTENDUE -> "Reponse inattendue : l'adresse repond mais ce n'est pas la configuration d'Authelia.";
            case ADRESSE_REFUSEE -> "Adresse refusee : il faut une adresse https avec un nom de domaine public.";
        };
        flash.addFlashAttribute(r == TestConnexionAuthelia.Resultat.JOIGNABLE ? "message" : "erreurGlobale", message);
        return "redirect:/admin/sso-config";
    }

    @PostMapping("/generer-secret")
    public String genererSecret(Authentication auth, Model model, HttpServletResponse reponse) {
        String clientId = config.clientId();
        String url = config.urlPublique();
        if (clientId.isBlank() || url.isBlank()) {
            return afficher(model, null, Map.of("general",
                    "Renseignez et enregistrez d'abord l'identifiant du client et l'adresse publique de ce serveur."));
        }
        EmpreinteAuthelia.ClientGenere genere = EmpreinteAuthelia.generer();
        config.remplacerSecret(genere.secretClair(), auteur(auth));
        LOG.info("Compte unique : secret client genere par {}", auteur(auth));

        // Jamais stocke ailleurs que chiffre en base ; jamais en cache ; affiche une seule fois dans CETTE reponse.
        reponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        reponse.setHeader("Pragma", "no-cache");
        model.addAttribute("blocAuthelia", blocAuthelia(clientId, genere.empreinte(), url));
        return afficher(model, null, Map.of());
    }

    /** Bloc a coller dans la liste {@code clients} de la configuration d'Authelia (l'empreinte, pas le secret). */
    static String blocAuthelia(String clientId, String empreinte, String urlPublique) {
        return String.join("\n",
                "- client_id: " + yaml(clientId),
                "  client_name: " + yaml(clientId),
                "  # Verification de l'empreinte (syntaxe a confirmer avec votre version d'Authelia) :",
                "  #   authelia crypto hash validate --password '<mot de passe du client>' -- '<empreinte ci-dessous>'",
                "  client_secret: " + yaml(empreinte),
                "  public: false",
                "  authorization_policy: 'site'",
                "  claims_policy: 'site'",
                "  consent_mode: 'implicit'",
                "  require_pkce: true",
                "  pkce_challenge_method: 'S256'",
                "  redirect_uris: [" + yaml(urlPublique + "/login/oauth2/code/authelia") + "]",
                "  scopes: ['openid', 'email', 'profile']",
                "  response_types: ['code']",
                "  grant_types: ['authorization_code']",
                "  token_endpoint_auth_method: 'client_secret_basic'",
                "");
    }

    private static String yaml(String valeur) {
        return "'" + valeur.replace("'", "''").replace("\r", "").replace("\n", " ") + "'";
    }

    private String afficher(Model model, Map<String, String> valeurs, Map<String, String> erreurs) {
        List<MailAccount> googleAccounts = mailAccountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2)
                .filter(a -> a.getClientId() != null && !a.getClientId().isBlank())
                .toList();

        List<MailAccount> microsoftAccounts = mailAccountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> a.getClientId() != null && !a.getClientId().isBlank())
                .toList();

        boolean googleActive    = clientRegistrationRepository.findByRegistrationId("google")    != null;
        boolean microsoftActive = clientRegistrationRepository.findByRegistrationId("microsoft") != null;

        model.addAttribute("googleAccounts",    googleAccounts);
        model.addAttribute("microsoftAccounts", microsoftAccounts);
        model.addAttribute("googleActive",      googleActive);
        model.addAttribute("microsoftActive",   microsoftActive);

        model.addAttribute("googleCallback", callback("google"));
        model.addAttribute("microsoftCallback", callback("microsoft"));

        ReglagesEnBase base = config.lireReglagesEnBase();
        Map<String, String> v = valeurs;
        if (v == null) {
            v = new LinkedHashMap<>();
            v.put("issuerUri", base.issuerUri());
            v.put("clientId", base.clientId());
            v.put("urlPublique", base.urlPublique());
            v.put("retourDeconnexion", base.retourDeconnexion());
            v.put("maitreUrlPublique", base.maitreUrlPublique());
        }
        model.addAttribute("valeurs", v);
        model.addAttribute("erreurs", erreurs);
        model.addAttribute("secretDefini", base.secretDefini());
        model.addAttribute("compteUniqueConfigure", config.configure());
        model.addAttribute("estMaitre", serverRole.getRole() == ServerRole.MASTER);
        model.addAttribute("urlRetourAuthelia",
                config.urlPublique().isBlank() ? "" : config.urlPublique() + "/login/oauth2/code/authelia");
        return VUE;
    }

    /** Adresse de retour a declarer chez Google/Microsoft (l'ancien gabarit lisait #request, indisponible). */
    private static String callback(String fournisseur) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/login/oauth2/code/" + fournisseur).build().toUriString();
    }

    private static String champDe(String message) {
        if (message == null) {
            return "general";
        }
        if (message.startsWith("L'adresse d'Authelia")) {
            return "issuerUri";
        }
        if (message.startsWith("L'adresse publique du Maitre")) {
            return "maitreUrlPublique";
        }
        if (message.startsWith("L'adresse publique")) {
            return "urlPublique";
        }
        if (message.startsWith("L'adresse de retour")) {
            return "retourDeconnexion";
        }
        if (message.startsWith("L'identifiant")) {
            return "clientId";
        }
        if (message.startsWith("Le secret")) {
            return "nouveauSecret";
        }
        return "general";
    }

    private static void ajouterSiChange(List<String> champs, String nom, String saisi, String actuel) {
        if (saisi != null && !saisi.equals(actuel)) {
            champs.add(nom);
        }
    }

    private static String rogne(String v) {
        return v == null ? null : v.trim();
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }

    /** Identifiant de l'administrateur, sans saut de ligne (pas d'injection dans le journal). */
    private static String auteur(Authentication auth) {
        String nom = auth == null ? "inconnu" : auth.getName();
        return nom.replaceAll("[\\r\\n\\t]", "_");
    }
}
