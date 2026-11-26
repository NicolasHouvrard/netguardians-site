package com.logicielpourtous.site.features.auth;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.logicielpourtous.site.features.commun.AiguillageMaitre;
import com.logicielpourtous.site.features.commun.AiguillageMaitre.Destination;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Aiguillage cote serveur secondaire : apres une authentification Authelia sans compte local, demande au Maitre
 * ou habite l'utilisateur et le renvoie vers le serveur concerne (qui ouvrira sa session sans mot de passe, la
 * session Authelia etant deja ouverte).
 *
 * <p>Securite : l'adresse de redirection vient UNIQUEMENT de la liste renvoyee par le Maitre (jamais d'un parametre) ;
 * le choix se fait par index valide contre la liste gardee en session ; le jeton d'identite est retire de la session
 * a sa premiere lecture et n'est jamais journalise ; tous les refus renvoient a la meme page de connexion.
 */
@Controller("connexionAiguillageController")
public class AiguillageController {

    static final String CHEMIN = "/connexion/aiguillage";
    private static final String REFUS = "/login?error=aiguillage";
    private static final String SUFFIXE_CONNEXION = "/oauth2/authorization/authelia";
    private static final Logger log = LoggerFactory.getLogger(AiguillageController.class);

    private final Optional<AiguillageMaitre> maitre;

    public AiguillageController(Optional<AiguillageMaitre> maitre) {
        this.maitre = maitre;
    }

    @GetMapping(CHEMIN)
    public String aiguiller(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, Model model)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        HttpSession session = request.getSession(false);
        if (session == null) {
            return refus();
        }
        // Lecture UNIQUE : le jeton est retire de la session quoi qu'il arrive ensuite.
        Object jeton = session.getAttribute(AiguillageRequis.ATTRIBUT_JETON);
        Object instant = session.getAttribute(AiguillageRequis.ATTRIBUT_JETON_INSTANT);
        AiguillageRequis.retirer(session);
        // Jeton refuse s'il est sans horodatage ou plus vieux que sa duree de vie maximale.
        boolean frais = instant instanceof Long t && System.currentTimeMillis() - t <= AiguillageRequis.DUREE_MAX_MS;
        if (!frais || !(jeton instanceof String idToken) || idToken.isBlank() || maitre.isEmpty()) {
            return refus();
        }

        List<Destination> destinations;
        try {
            destinations = valides(maitre.get().aiguiller(idToken));
        } catch (RuntimeException e) {
            // Seul le type est journalise : le message pourrait contenir des elements de la requete.
            log.warn("Aiguillage impossible : Maitre injoignable ou en erreur ({})", e.getClass().getSimpleName());
            return refus();
        }

        if (destinations.isEmpty()) {
            return refus();
        }
        if (destinations.size() == 1) {
            response.sendRedirect(adresseConnexion(destinations.get(0)));
            return null;
        }
        session.setAttribute(AiguillageRequis.ATTRIBUT_LISTE, new ArrayList<>(destinations));
        model.addAttribute("serveurs", destinations.stream().map(Destination::nomSociete).toList());
        return "feature/auth/choisir-serveur";
    }

    @PostMapping(CHEMIN)
    public String choisir(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                          @RequestParam(name = "index", required = false) String index) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        HttpSession session = request.getSession(false);
        Object liste = session == null ? null : session.getAttribute(AiguillageRequis.ATTRIBUT_LISTE);
        if (session != null) {
            // Usage unique de la liste.
            session.removeAttribute(AiguillageRequis.ATTRIBUT_LISTE);
        }
        if (!(liste instanceof List<?> destinations) || index == null) {
            return refus();
        }
        int position;
        try {
            position = Integer.parseInt(index.trim());
        } catch (NumberFormatException e) {
            return refus();
        }
        if (position < 0 || position >= destinations.size() || !(destinations.get(position) instanceof Destination choisie)) {
            return refus();
        }
        response.sendRedirect(adresseConnexion(choisie));
        return null;
    }

    private static String refus() {
        return "redirect:" + REFUS;
    }

    private static String adresseConnexion(Destination destination) {
        String base = destination.urlPublique().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + SUFFIXE_CONNEXION;
    }

    /** Ne garde que les destinations dont l'adresse est une URL https absolue sans identifiants integres. */
    private static List<Destination> valides(List<Destination> brutes) {
        List<Destination> resultat = new ArrayList<>();
        if (brutes == null) {
            return resultat;
        }
        for (Destination d : brutes) {
            if (d != null && adresseAcceptable(d.urlPublique())) {
                resultat.add(d);
            }
        }
        return resultat;
    }

    private static boolean adresseAcceptable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String schema = uri.getScheme();
            return "https".equalsIgnoreCase(schema)
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
