package com.logicielpourtous.site.features.auth;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.logicielpourtous.site.config.SecurityConfig;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.user.Appartenance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Selecteur de societe pour une personne qui appartient a plusieurs societes : affiche ses societes et
 * retient celle qu'elle choisit (l'authentification de la session est remplacee par le compte dont la
 * societe est active, le tenant de session suit).
 */
@Controller
@RequestMapping(SelectionSocieteFilter.SELECTEUR)
public class ChoixSocieteController {

    /** Une ligne du selecteur : l'appartenance, le nom de la societe et celui de la fiche client eventuelle. */
    public record SocieteProposee(Long appartenanceId, String societe, String fiche, boolean active) {}

    private final CustomerRepository customerRepository;
    private final CustomUserDetailsService userDetailsService;
    private final CustomTenantSuccessHandler successHandler;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holderStrategy = SecurityContextHolder.getContextHolderStrategy();

    public ChoixSocieteController(CustomerRepository customerRepository, CustomUserDetailsService userDetailsService,
                                  CustomTenantSuccessHandler successHandler) {
        this.customerRepository = customerRepository;
        this.userDetailsService = userDetailsService;
        this.successHandler = successHandler;
    }

    @GetMapping
    public String afficher(Authentication authentication, Model model) {
        CustomUserDetails compte = compteMultiSocietes(authentication);
        if (compte == null) {
            // Ruling : une personne d'une seule societe n'a rien a choisir.
            return "redirect:/";
        }
        List<SocieteProposee> societes = compte.appartenances().stream()
                .map(a -> new SocieteProposee(a.getId(), a.getTenant().getName(), nomDeFiche(a),
                        a.getId().equals(compte.appartenanceActiveId()) && !compte.selectionRequise()))
                .toList();
        model.addAttribute("societes", societes);
        model.addAttribute("selectionRequise", compte.selectionRequise());
        return "feature/auth/choisir-societe";
    }

    @PostMapping
    public String choisir(@RequestParam Long appartenanceId, Authentication authentication,
                          HttpServletRequest request, HttpServletResponse response) {
        CustomUserDetails compte = compteMultiSocietes(authentication);
        if (compte == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        try {
            // Garde de propriete : l'identifiant doit etre l'une des appartenances du compte connecte.
            compte.avecAppartenanceActive(appartenanceId);
        } catch (IllegalArgumentException e) {
            // Pas l'une des appartenances du compte connecte : refus, rien n'est modifie.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        // Compte recharge depuis la base : une appartenance retiree, des roles modifies ou un compte
        // desactive depuis la connexion sont pris en compte (l'objet de session peut etre perime).
        CustomUserDetails choisi;
        try {
            choisi = (CustomUserDetails) userDetailsService.charger(compte.getUsername(), appartenanceId);
        } catch (UsernameNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (choisi.selectionRequise() || choisi.getTenant() == null || !choisi.isEnabled()
                || !appartenanceId.equals(choisi.appartenanceActiveId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        // Les donnees de session propres a l'ancienne societe (client consulte, facture en cours d'import,
        // dernier tableau de bord, conversation...) ne doivent pas suivre la personne dans la nouvelle.
        HttpSession session = request.getSession(false);
        // Suite de la connexion (lus avant le vidage de la session) : origine Angular retenue par le
        // gestionnaire de connexion, changement de mot de passe obligatoire.
        boolean depuisAngular = false;
        boolean doitChangerMotDePasse = false;
        if (session != null) {
            depuisAngular = Boolean.TRUE.equals(
                    session.getAttribute(CustomTenantSuccessHandler.ORIGINE_ANGULAR_EN_ATTENTE));
            doitChangerMotDePasse = Boolean.TRUE.equals(session.getAttribute("MUST_CHANGE_PASSWORD"));
            // Retire aussi l'indicateur d'origine Angular (absent de la liste blanche) : usage unique.
            viderDonneesDeSociete(session);
            // Changement de droits : nouvel identifiant de session (protection contre la fixation de session).
            request.changeSessionId();
        }
        SecurityContext contexte = holderStrategy.createEmptyContext();
        contexte.setAuthentication(authentificationPour(authentication, choisi));
        holderStrategy.setContext(contexte);
        securityContextRepository.saveContext(contexte, request, response);

        String schema = choisi.getTenant().getSchemaName();
        request.getSession().setAttribute("CURRENT_TENANT_ID", schema);
        TenantContext.setCurrentTenant(schema);
        // Meme suite qu'une connexion d'un compte qui a une societe (Angular, mot de passe, accueil par droits,
        // cookie _intra_jwt pour admin/ESN), avec des jetons portant la societe choisie.
        return "redirect:" + successHandler.destinationApresConnexion(response, choisi,
                doitChangerMotDePasse, depuisAngular);
    }

    /**
     * Attributs de session conserves lors d'un changement de societe ; tous les autres sont retires (liste
     * blanche : un futur attribut propre a une societe est retire par defaut). Le contexte de securite est
     * reecrit juste apres, {@code CURRENT_TENANT_ID} aussi.
     */
    private static final Set<String> CONSERVES_AU_CHANGEMENT = Set.of(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            "MUST_CHANGE_PASSWORD",
            "shopCart");

    private static void viderDonneesDeSociete(HttpSession session) {
        for (String nom : Collections.list(session.getAttributeNames())) {
            if (!CONSERVES_AU_CHANGEMENT.contains(nom)) {
                session.removeAttribute(nom);
            }
        }
    }

    /** Compte connecte qui appartient a plusieurs societes, sinon nul. */
    private static CustomUserDetails compteMultiSocietes(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails compte
                && compte.plusieursSocietes()) {
            return compte;
        }
        return null;
    }

    /**
     * Nouvelle authentification portant {@code compte}, de meme nature que l'ancienne : une connexion par
     * remember-me le reste (elle ne devient pas une authentification complete), toute autre devient une
     * authentification par identifiant.
     */
    static Authentication authentificationPour(Authentication ancienne, CustomUserDetails compte) {
        Authentication nouvelle;
        if (ancienne instanceof RememberMeAuthenticationToken) {
            nouvelle = new RememberMeAuthenticationToken(SecurityConfig.CLE_REMEMBER_ME, compte, compte.getAuthorities());
        } else {
            nouvelle = UsernamePasswordAuthenticationToken.authenticated(compte, null, compte.getAuthorities());
        }
        if (ancienne != null && nouvelle instanceof org.springframework.security.authentication.AbstractAuthenticationToken jeton) {
            jeton.setDetails(ancienne.getDetails());
        }
        return nouvelle;
    }

    private String nomDeFiche(Appartenance a) {
        if (a.getCustomerId() == null) {
            return null;
        }
        return customerRepository.findById(a.getCustomerId()).map(c -> c.getCompanyName()).orElse(null);
    }
}
