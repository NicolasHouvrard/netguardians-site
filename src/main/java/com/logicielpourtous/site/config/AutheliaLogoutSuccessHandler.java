package com.logicielpourtous.site.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;

import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fin de deconnexion. Pour un utilisateur connecte par le fournisseur « authelia » (et si l'adresse du portail est
 * configuree en https), le site ferme sa session puis affiche sa page {@value #PAGE} qui propose de fermer aussi la
 * session du compte unique (Authelia 4.39 n'a pas de deconnexion OpenID Connect ; elle arrive en 4.40). Pour tous les
 * autres cas (compte local, Google, Microsoft, portail non configure ou pas en https) : comportement habituel
 * (retour a /login?logout).
 *
 * <p>Si de plus un « retour » est configure ({@code app.auth.authelia.retour-deconnexion}) et valide, le site envoie le
 * navigateur, dans le meme onglet, vers la page de deconnexion du portail avec {@code rd=<retour>} : le portail ferme sa
 * session puis ramene l'utilisateur sur le site. Le retour vient uniquement de la configuration, jamais de la requete.
 *
 * <p>Aucun appel reseau ; une requete HTMX recoit HX-Redirect (200) au lieu d'un 302.
 */
public class AutheliaLogoutSuccessHandler implements LogoutSuccessHandler {

    /** Page du site affichee apres la deconnexion d'un compte unique. */
    public static final String PAGE = "/deconnecte?sso=1";
    private static final Logger LOG = LoggerFactory.getLogger(AutheliaLogoutSuccessHandler.class);
    private static final String FOURNISSEUR = "authelia";

    /**
     * Etat derive des reglages : l'emetteur et le retour dont il provient (pour detecter un changement), si le portail
     * est utilisable, et l'adresse complete portail + rd (null si le retour n'est pas configure ou pas valide).
     */
    private record Etat(String issuerUri, String retour, boolean portailUtilisable, String urlPortailAvecRetour) {
        static Etat de(String issuerUri, String retourDeconnexion) {
            String portail = urlDeconnexionPortail(issuerUri);
            return new Etat(issuerUri, retourDeconnexion, portail != null,
                    portail == null ? null : avecRetour(portail, issuerUri, retourDeconnexion));
        }

        boolean correspond(String issuer, String retour) {
            return java.util.Objects.equals(issuerUri, issuer) && java.util.Objects.equals(this.retour, retour);
        }
    }

    private final SimpleUrlLogoutSuccessHandler parDefaut = new SimpleUrlLogoutSuccessHandler();
    /** Reglages lus a chaque deconnexion ; null = valeurs fixes (etat initial seul). */
    private final ConfigurationCompteUnique reglages;
    private volatile Etat etat;

    public AutheliaLogoutSuccessHandler(String issuerUri) {
        this(issuerUri, null);
    }

    public AutheliaLogoutSuccessHandler(String issuerUri, String retourDeconnexion) {
        parDefaut.setDefaultTargetUrl("/login?logout");
        this.reglages = null;
        this.etat = Etat.de(issuerUri, retourDeconnexion);
    }

    /**
     * Suit les reglages du compte unique : l'etat est recalcule (sans reseau) a la premiere deconnexion qui suit un
     * changement de l'emetteur ou du retour.
     */
    public AutheliaLogoutSuccessHandler(ConfigurationCompteUnique reglages) {
        parDefaut.setDefaultTargetUrl("/login?logout");
        this.reglages = reglages;
        this.etat = null;
    }

    private Etat etatCourant() {
        if (reglages == null) {
            return etat;
        }
        String issuer = reglages.issuerUri();
        String retour = reglages.retourDeconnexion();
        Etat e = etat;
        if (e == null || !e.correspond(issuer, retour)) {
            e = Etat.de(issuer, retour);
            etat = e;
        }
        return e;
    }

    private static String avecRetour(String urlPortail, String issuerUri, String retour) {
        if (retour == null || retour.isBlank()) {
            return null;
        }
        String valeur = retour.trim();
        String hotePortail = hote(issuerUri.trim());
        String hoteRetour = null;
        try {
            URI uri = new URI(valeur);
            if (uri.isAbsolute() && "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && uri.getRawFragment() == null && uri.getHost() != null) {
                hoteRetour = uri.getHost().toLowerCase(Locale.ROOT);
            }
        } catch (java.net.URISyntaxException e) {
            hoteRetour = null;
        }
        String parent = hotePortail == null ? null : parent(hotePortail);
        if (hoteRetour != null && parent != null
                && (hoteRetour.equals(parent) || hoteRetour.endsWith("." + parent))) {
            return urlPortail + "?rd=" + URLEncoder.encode(valeur, StandardCharsets.UTF_8);
        }
        LOG.warn("Retour de deconnexion ignore : il doit etre une adresse https, sans identifiants ni fragment, "
                + "dans le domaine parent du portail Authelia.");
        return null;
    }

    private static String hote(String adresse) {
        try {
            String h = new URI(adresse).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Domaine parent : l'hote prive de son premier label ; null s'il ne reste qu'un label. */
    private static String parent(String hote) {
        int i = hote.indexOf('.');
        if (i < 0) {
            return null;
        }
        String parent = hote.substring(i + 1);
        return parent.indexOf('.') < 0 || parent.isEmpty() ? null : parent;
    }

    /**
     * Adresse de la page de deconnexion du portail ({@code <issuer-uri sans slash final>/logout}), construite a partir de
     * la seule configuration ; null si elle est absente ou n'est pas en https.
     */
    public static String urlDeconnexionPortail(String issuerUri) {
        String base = issuerUri == null ? "" : issuerUri.trim();
        if (base.isEmpty()) {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.regionMatches(true, 0, "https://", 0, 8) || base.length() == 8) {
            LOG.warn("Deconnexion unique ignoree : l'adresse du portail Authelia doit etre en https.");
            return null;
        }
        return base + "/logout";
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException, ServletException {
        boolean jetonAuthelia = authentication instanceof OAuth2AuthenticationToken jeton
                && FOURNISSEUR.equals(jeton.getAuthorizedClientRegistrationId());
        // Marqueur de session lu par CompteUniqueLogoutHandler : couvre le compte multi-societes dont
        // l'authentification a ete remplacee apres le choix de societe.
        boolean marqueur = Boolean.TRUE.equals(request.getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE));
        Etat courant = etatCourant();
        if (courant.portailUtilisable() && (jetonAuthelia || marqueur)) {
            String cible = courant.urlPortailAvecRetour() != null ? courant.urlPortailAvecRetour()
                    : request.getContextPath() + PAGE;
            if ("true".equals(request.getHeader("HX-Request"))) {
                response.setHeader("HX-Redirect", cible);
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                response.sendRedirect(cible);
            }
            return;
        }
        parDefaut.onLogoutSuccess(request, response, authentication);
    }
}
