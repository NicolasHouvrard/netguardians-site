package com.logicielpourtous.site.features.licence;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;

/**
 * Ecran d'administration des comptes de licence (MP) : {@code /intra/licence/comptes}.
 *
 * <p>Reserve au role {@code ROLE_ADMIN} (meme garde que les autres ecrans {@code intra}
 * administratifs du depot — cf. {@code TeamApiController}/{@code SiteConfigApiController}),
 * ET disponible seulement si CETTE instance est {@code MASTER} : une instance {@code PARTNER}/
 * {@code CLIENT}/{@code PUBLIC} ne gere pas de comptes de licence, l'ecran repond alors 404.
 */
@Controller
@RequestMapping("/intra/licence/comptes")
@PreAuthorize("hasRole('ADMIN')")
public class CompteLicenceAdminController {

    private final CompteLicenceRepository compteLicenceRepository;
    private final LicenceServeurRepository licenceServeurRepository;
    private final PasswordEncoder passwordEncoder;
    private final ServerRoleProperties serverRoleProperties;

    public CompteLicenceAdminController(CompteLicenceRepository compteLicenceRepository,
                                        LicenceServeurRepository licenceServeurRepository,
                                        PasswordEncoder passwordEncoder,
                                        ServerRoleProperties serverRoleProperties) {
        this.compteLicenceRepository = compteLicenceRepository;
        this.licenceServeurRepository = licenceServeurRepository;
        this.passwordEncoder = passwordEncoder;
        this.serverRoleProperties = serverRoleProperties;
    }

    @GetMapping
    public String list(Model model) {
        requireMaster();
        model.addAttribute("comptes", compteLicenceRepository.findAll());
        model.addAttribute("typesAbonnement", CompteLicence.TypeAbonnement.values());
        model.addAttribute("frequences", CompteLicence.Frequence.values());
        return "feature/licence/comptes";
    }

    @PostMapping
    public String create(@RequestParam String login,
                         @RequestParam String motDePasse,
                         @RequestParam CompteLicence.TypeAbonnement typeAbonnement,
                         @RequestParam CompteLicence.Frequence frequence,
                         @RequestParam int nombreServeursAutorises,
                         RedirectAttributes ra) {
        requireMaster();
        if (compteLicenceRepository.findByLogin(login).isPresent()) {
            ra.addFlashAttribute("errorMessage", "Le login « " + login + " » existe déjà.");
            return "redirect:/intra/licence/comptes";
        }
        CompteLicence compte = new CompteLicence();
        compte.setLogin(login);
        compte.setMotDePasseHash(passwordEncoder.encode(motDePasse));
        compte.setTypeAbonnement(typeAbonnement);
        compte.setFrequence(frequence);
        compte.setNombreServeursAutorises(nombreServeursAutorises);
        compteLicenceRepository.save(compte);
        ra.addFlashAttribute("successMessage", "Compte « " + login + " » créé.");
        return "redirect:/intra/licence/comptes";
    }

    @PostMapping("/{id}/toggle-actif")
    public String toggleActif(@PathVariable Long id, RedirectAttributes ra) {
        requireMaster();
        compteLicenceRepository.findById(id).ifPresent(compte -> {
            boolean nouveauActif = !compte.isActif();
            compte.setActif(nouveauActif);
            compteLicenceRepository.save(compte);
            if (!nouveauActif) {
                // Revue finale, I2 : desactiver le compte coupe l'acces ACTIF de tous ses serveurs et le
                // propage au partenaire — MEME mecanisme que revokeLicenceServeur (REVOQUE +
                // notifiePartenaire=false, re-sonde par /nouveaux-serveurs). Une reactivation, elle, ne
                // restaure AUCUN serveur revoque (comme une revocation unitaire, jamais auto-inversee).
                licenceServeurRepository.findByCompteId(id).stream()
                        .filter(s -> s.getStatut() == LicenceServeur.LicenceServeurStatut.ACTIF)
                        .forEach(s -> {
                            s.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
                            s.setNotifiePartenaire(false);
                            licenceServeurRepository.save(s);
                        });
            }
        });
        ra.addFlashAttribute("successMessage", "Compte mis à jour.");
        return "redirect:/intra/licence/comptes";
    }

    /**
     * Detail d'un compte : ses {@link LicenceServeur}s, avec adresse editable et revocation par
     * ligne. Reutilise la MEME vue que {@link #list} (attribut {@code detailCompte} present/absent
     * selon la route) plutot qu'un template separe.
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        requireMaster();
        CompteLicence compte = compteLicenceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("comptes", compteLicenceRepository.findAll());
        model.addAttribute("typesAbonnement", CompteLicence.TypeAbonnement.values());
        model.addAttribute("frequences", CompteLicence.Frequence.values());
        model.addAttribute("detailCompte", compte);
        model.addAttribute("serveursDuCompte", licenceServeurRepository.findByCompteId(id));
        return "feature/licence/comptes";
    }

    @PostMapping("/{compteId}/serveurs/{serveurId}/adresse")
    public String updateAdresseDeclaree(@PathVariable Long compteId, @PathVariable Long serveurId,
                                        @RequestParam String adresseDeclaree, RedirectAttributes ra) {
        requireMaster();
        LicenceServeur serveur = serveurDuCompteOuNotFound(compteId, serveurId);
        serveur.setAdresseDeclaree(adresseDeclaree);
        licenceServeurRepository.save(serveur);
        ra.addFlashAttribute("successMessage", "Adresse mise à jour.");
        return "redirect:/intra/licence/comptes/" + compteId;
    }

    @PostMapping("/{compteId}/serveurs/{serveurId}/revoquer")
    public String revokeLicenceServeur(@PathVariable Long compteId, @PathVariable Long serveurId, RedirectAttributes ra) {
        requireMaster();
        LicenceServeur serveur = serveurDuCompteOuNotFound(compteId, serveurId);
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
        // Task 6 : une revocation est un changement d'etat qui doit etre PROPAGE au PARTNER de ce
        // compte (-> SecondaryServer local revoque) au prochain sondage — remet notifiePartenaire a
        // false (meme si ce serveur avait deja ete notifie a sa creation) pour qu'il reapparaisse
        // dans GET /api/licence/nouveaux-serveurs (voir LicenceServeur#isNotifiePartenaire).
        serveur.setNotifiePartenaire(false);
        licenceServeurRepository.save(serveur);
        ra.addFlashAttribute("successMessage", "Serveur révoqué.");
        return "redirect:/intra/licence/comptes/" + compteId;
    }

    /**
     * Charge le {@link LicenceServeur} {@code serveurId} ET verifie qu'il appartient bien au compte
     * {@code compteId} du chemin — sans ca, une requete (malformee ou perimee) pourrait modifier/
     * revoquer un serveur d'un compte totalement different via son seul {@code serveurId}.
     */
    private LicenceServeur serveurDuCompteOuNotFound(Long compteId, Long serveurId) {
        return licenceServeurRepository.findById(serveurId)
                .filter(s -> compteId.equals(s.getCompteId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Choix simple retenu pour la garde "disponible seulement si app.role=MASTER" : 404 explicite. */
    private void requireMaster() {
        if (serverRoleProperties.getRole() != ServerRole.MASTER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
