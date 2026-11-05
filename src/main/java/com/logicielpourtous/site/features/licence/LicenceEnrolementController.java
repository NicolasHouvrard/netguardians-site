package com.logicielpourtous.site.features.licence;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.logicielpourtous.site.features.commun.ServerRole;

/**
 * Enrolement automatique d'un serveur secondaire ({@code PARTNER} ou {@code CLIENT}) auprès de ce
 * maitre, en echange des identifiants (login/mot de passe) d'un {@link CompteLicence} — pendant,
 * pour le module {@code licence}, de {@code features.sync.EnrollmentController}.
 *
 * <p>Deux etapes :
 * <ol>
 *   <li>{@code POST /api/licence/enregistrer} : prouve l'identite via login/mot de passe
 *       ({@code PasswordEncoder#matches}), cree (ou retrouve, si c'est un redemarrage/reinstall de
 *       la meme machine — idempotence sur {@code (compteId, identifiantInstallationStable)}) le
 *       {@link LicenceServeur}, et delivre un {@code codeEnrolement} a usage unique en clair (ce
 *       n'est PAS une cle API : juste un code intermediaire, comme {@code SecondaryServer}'s
 *       {@code enrollmentCode}) ;</li>
 *   <li>{@code POST /api/licence/cle} : echange ce code contre la cle API dediee a ce module
 *       ({@link LicenceCles}), delivree en clair UNE SEULE FOIS — seule son empreinte est
 *       conservee ({@code cleApiHash}), aucune route ne permet de la relire ensuite.</li>
 * </ol>
 *
 * <p>Routes publiques (la preuve est dans le corps de la requete, comme {@code /api/sync/enroll}) —
 * voir {@code SecurityConfig.licenceApiFilterChain}.
 *
 * <p><b>Verrouillage</b> : la ligne du {@link CompteLicence} ({@code
 * CompteLicenceRepository#findByIdForUpdate}) est verrouillee pendant toute la verification
 * quota/unicite-PARTNER + sauvegarde du {@link LicenceServeur}, pour serialiser deux enrolements
 * concurrents sous le meme compte (sinon deux requetes pres de la limite du quota pourraient toutes
 * les deux passer la verification avant que l'une d'elles ne committe).
 *
 * <p><b>Timing</b> : la comparaison BCrypt du mot de passe est TOUJOURS executee (contre une
 * empreinte bidon si le login est inconnu), pour qu'un login inconnu ne soit pas distinguable d'un
 * mauvais mot de passe par le temps de reponse. Aucune limitation de debit/verrouillage de compte
 * n'est ajoutee (hors perimetre — aucun patron existant ailleurs dans ce depot).
 *
 * <p>Depuis cette tache, {@code /enregistrer} accepte aussi un enrolement de CLIENT par jeton
 * ({@code body.jeton} non vide, plutot que {@code login}/{@code motDePasse}) : le jeton (genere par
 * un {@code PARTNER} via {@code POST /api/licence/jetons}, a usage unique) porte lui-meme le {@code
 * compteId} et le {@code customerId} du {@link LicenceServeur} {@code CLIENT} cree — le corps de la
 * requete cliente n'a donc JAMAIS a porter son propre {@code customerId}. Meme discipline de verrou
 * pessimiste que le chemin login/mot de passe (voir {@link JetonClientRepository#findByCode}) pour
 * fermer la fenetre de rejeu/double-consommation concurrente du MEME jeton.
 *
 * <p>Codes de reponse de {@code /enregistrer} :
 * <ul>
 *   <li>400 : champ obligatoire absent, ou {@code roleVoulu} autre que {@code PARTNER}/{@code CLIENT}
 *       (chemin login/mot de passe uniquement) ;</li>
 *   <li>401 : login/mot de passe invalides, ou compte {@code actif=false} (indiscernables) ; ou,
 *       pour une reutilisation idempotente, serveur {@code REVOQUE} ; ou (chemin jeton) jeton
 *       inconnu/deja utilise, ou {@code CompteLicence} associe au jeton {@code actif=false} ;</li>
 *   <li>409 : quota de serveurs atteint (verifie sur le {@code compteId} du jeton pour le chemin
 *       jeton), ou un {@code PARTNER} actif existe deja pour ce compte (chemin login/mot de passe) ;</li>
 *   <li>410 : (chemin jeton) jeton expire.</li>
 * </ul>
 * Codes de reponse de {@code /cle} :
 * <ul>
 *   <li>400 : code absent ;</li>
 *   <li>401 : code inconnu, deja utilise, ou serveur {@code REVOQUE} ;</li>
 *   <li>410 : code expire.</li>
 * </ul>
 * Codes de reponse de {@code /jetons} :
 * <ul>
 *   <li>400 : {@code customerId} absent ;</li>
 *   <li>401 : cle API absente/invalide/inactive (filtre de securite, voir {@link
 *       LicenceApiKeyAuthFilter}) ;</li>
 *   <li>403 : l'appelant authentifie n'est pas un {@code LicenceServeur} de role {@code PARTNER}
 *       actif.</li>
 * </ul>
 * Codes de reponse de {@code /nouveaux-serveurs} (Task 6, {@code GET}, sonde par {@code
 * LicenceDecouverteScheduler}) :
 * <ul>
 *   <li>401 : cle API absente/invalide/inactive ;</li>
 *   <li>403 : l'appelant authentifie n'est pas un {@code PARTNER} actif.</li>
 * </ul>
 * Codes de reponse de {@code /code-partenaire} (Task 6) :
 * <ul>
 *   <li>400 : {@code identifiantInstallationStable} ou {@code codeLocal} absent ;</li>
 *   <li>401 : cle API absente/invalide/inactive ;</li>
 *   <li>403 : l'appelant authentifie n'est pas un {@code PARTNER} actif ;</li>
 *   <li>404 : aucun {@code LicenceServeur} {@code CLIENT} {@code identifiantInstallationStable} pour
 *       le compte de l'appelant.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/licence")
public class LicenceEnrolementController {

    private static final Logger log = LoggerFactory.getLogger(LicenceEnrolementController.class);

    /** Duree de validite du code d'enrolement : court car echange immediatement par l'appelant
     * (meme flux, deux appels successifs) — reduit la fenetre d'exposition si le code fuit (logs...). */
    private static final Duration DUREE_CODE_ENROLEMENT = Duration.ofMinutes(15);

    /**
     * Empreinte BCrypt bidon, calculee une seule fois au chargement de la classe (pas de lien avec
     * un compte reel), comparee par {@code PasswordEncoder#matches} quand le login presente est
     * INCONNU — uniquement pour que ce cas coute le MEME temps BCrypt (~50-100 ms) qu'un login
     * connu avec un mauvais mot de passe. Sans ca, {@code compteOpt.isEmpty()} court-circuite avant
     * tout BCrypt et le temps de reponse devient un oracle d'enumeration de logins. Pas de
     * limitation de debit/verrouillage ajoutee ici (aucun patron existant ailleurs dans ce depot
     * pour ca) : hors perimetre de cette tache, voir le rapport de tache.
     */
    private static final String EMPREINTE_BIDON_ANTI_TIMING =
            new BCryptPasswordEncoder().encode("licence-timing-guard-" + java.util.UUID.randomUUID());

    /** {@code jeton} : chemin d'enrolement d'un CLIENT (voir la javadoc de la classe), alternatif a
     * {@code login}/{@code motDePasse}/{@code roleVoulu} (chemin PARTNER/CLIENT historique). */
    public record EnregistrerRequest(String login, String motDePasse, String jeton, ServerRole roleVoulu,
                                      String identifiantInstallationStable, String adresseDeclaree) {}

    public record CleRequest(String codeEnrolement) {}

    public record JetonRequest(Long customerId) {}

    /** Entree de la reponse de {@link #nouveauxServeurs} (Task 6) : un {@link LicenceServeur}
     * {@code CLIENT} pas encore notifie au {@code PARTNER} appelant. */
    public record NouveauServeurDto(String identifiantInstallationStable, Long customerId,
                                     LicenceServeur.LicenceServeurStatut statut) {}

    /** {@code codeLocal} : code d'enrolement du {@code SecondaryServer} local (module {@code
     * features.sync}) que le PARTENAIRE appelant a materialise pour ce client (Task 6). */
    public record CodePartenaireRequest(String identifiantInstallationStable, String codeLocal) {}

    /** Duree de validite d'un jeton d'installation CLIENT : plus longue que {@code
     * DUREE_CODE_ENROLEMENT} car, contrairement au code d'enrolement (echange immediatement par le
     * MEME flux automatique), le jeton est transmis au client par un canal hors-perimetre (le
     * partenaire le communique a son client) avant d'etre consomme — fenetre realiste pour que
     * l'installation cliente ait lieu. */
    private static final Duration DUREE_VALIDITE_JETON = Duration.ofDays(7);

    private final CompteLicenceRepository compteLicenceRepository;
    private final LicenceServeurRepository licenceServeurRepository;
    private final JetonClientRepository jetonClientRepository;
    private final PasswordEncoder passwordEncoder;

    public LicenceEnrolementController(CompteLicenceRepository compteLicenceRepository,
                                       LicenceServeurRepository licenceServeurRepository,
                                       JetonClientRepository jetonClientRepository,
                                       PasswordEncoder passwordEncoder) {
        this.compteLicenceRepository = compteLicenceRepository;
        this.licenceServeurRepository = licenceServeurRepository;
        this.jetonClientRepository = jetonClientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/enregistrer")
    @Transactional
    public ResponseEntity<Map<String, Object>> enregistrer(@RequestBody(required = false) EnregistrerRequest body) {
        if (body == null || isBlank(body.identifiantInstallationStable())) {
            return ResponseEntity.badRequest().build();
        }
        if (!isBlank(body.jeton())) {
            return enregistrerViaJeton(body);
        }

        if (isBlank(body.login()) || isBlank(body.motDePasse()) || body.roleVoulu() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (body.roleVoulu() != ServerRole.PARTNER && body.roleVoulu() != ServerRole.CLIENT) {
            log.warn("Enrolement licence refuse : roleVoulu {} invalide", body.roleVoulu());
            return ResponseEntity.badRequest().build();
        }

        Optional<CompteLicence> compteOpt = compteLicenceRepository.findByLogin(body.login());
        // Timing : on compare TOUJOURS via BCrypt (empreinte reelle si le compte existe, empreinte
        // bidon sinon) avant tout court-circuit, pour qu'un login inconnu coute le meme temps qu'un
        // mauvais mot de passe sur un login connu (voir EMPREINTE_BIDON_ANTI_TIMING).
        String empreinteAComparer = compteOpt.map(CompteLicence::getMotDePasseHash).orElse(EMPREINTE_BIDON_ANTI_TIMING);
        boolean motDePasseValide = passwordEncoder.matches(body.motDePasse(), empreinteAComparer);
        if (compteOpt.isEmpty() || !motDePasseValide || !compteOpt.get().isActif()) {
            log.warn("Enrolement licence refuse : identifiants invalides ou compte inactif");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verrou pessimiste sur la ligne du compte : serialise les enrolements concurrents sous le
        // MEME compte (quota, unicite du PARTNER actif) — sans ca, deux requetes concurrentes pres
        // de la limite du quota (ou deux 1ers enrolements PARTNER concurrents) pourraient toutes les
        // deux passer la validation avant que l'une d'elles ne committe.
        CompteLicence compte = compteLicenceRepository.findByIdForUpdate(compteOpt.get().getId()).orElseThrow();

        Optional<LicenceServeur> existant = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), body.identifiantInstallationStable());

        LicenceServeur serveur;
        if (existant.isPresent()) {
            // Idempotence : un redemarrage/reinstall de la MEME machine retrouve son LicenceServeur,
            // ne consomme pas un 2e siege du quota.
            serveur = existant.get();
            if (serveur.getStatut() != LicenceServeur.LicenceServeurStatut.ACTIF) {
                log.warn("Enrolement licence refuse : serveur {} revoque", serveur.getId());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            // L'adresse auto-declaree ne remplace JAMAIS une valeur deja presente : une correction
            // manuelle faite par l'admin (ecran /intra/licence/comptes/{id}) ne doit pas etre ecrasee
            // par le prochain redemarrage de la MEME installation.
            if (isBlank(serveur.getAdresseDeclaree()) && !isBlank(body.adresseDeclaree())) {
                serveur.setAdresseDeclaree(body.adresseDeclaree());
            }
        } else {
            long actifs = licenceServeurRepository.countByCompteIdAndStatut(
                    compte.getId(), LicenceServeur.LicenceServeurStatut.ACTIF);
            if (actifs >= compte.getNombreServeursAutorises()) {
                log.warn("Enrolement licence refuse : quota atteint pour le compte {}", compte.getId());
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            if (body.roleVoulu() == ServerRole.PARTNER
                    && licenceServeurRepository.existsByCompteIdAndRoleAndStatut(
                            compte.getId(), ServerRole.PARTNER, LicenceServeur.LicenceServeurStatut.ACTIF)) {
                log.warn("Enrolement licence refuse : un PARTNER actif existe deja pour le compte {}", compte.getId());
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            serveur = new LicenceServeur();
            serveur.setCompteId(compte.getId());
            serveur.setRole(body.roleVoulu());
            serveur.setIdentifiantInstallationStable(body.identifiantInstallationStable());
            serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
            if (!isBlank(body.adresseDeclaree())) {
                serveur.setAdresseDeclaree(body.adresseDeclaree());
            }
        }

        String code = LicenceCles.generate();
        Instant expireA = Instant.now().plus(DUREE_CODE_ENROLEMENT);
        serveur.setCodeEnrolement(code);
        serveur.setCodeEnrolementExpireA(expireA);
        serveur.setCodeEnrolementUtilise(false);
        licenceServeurRepository.save(serveur);
        log.info("Serveur licence {} enregistre (compte {}), code d'enrolement delivre", serveur.getId(), compte.getId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reponseEnregistrement(serveur, code, expireA));
    }

    /**
     * Chemin CLIENT de {@code /enregistrer} : la preuve d'identite est le jeton lui-meme (a usage
     * unique, genere par un {@code PARTNER} via {@link #jetons}), pas un login/mot de passe — le
     * {@code compteId} et le {@code customerId} du {@link LicenceServeur} cree viennent TOUS LES
     * DEUX du jeton, jamais du corps de la requete cliente.
     *
     * <p>{@link JetonClientRepository#findByCode} verrouille la ligne du jeton (meme patron que
     * {@link LicenceServeurRepository#findByCodeEnrolement}) : deux consommations concurrentes du
     * MEME jeton ne peuvent pas toutes les deux reussir. Le verrou sur la ligne du {@link
     * CompteLicence} ({@link CompteLicenceRepository#findByIdForUpdate}) applique ensuite EXACTEMENT
     * le meme controle de quota que le chemin login/mot de passe, sur le {@code compteId} DU JETON
     * (jamais un autre compte).
     *
     * <p><b>Idempotence (fix round 1)</b> : exactement comme le chemin login/mot de passe,
     * {@code identifiantInstallationStable} est consulte EN PREMIER (avant tout controle
     * utilise/expire du jeton) — un redemarrage/reinstall de la MEME machine retrouve son {@link
     * LicenceServeur} existant et obtient simplement un nouveau {@code codeEnrolement}, sans
     * consulter l'etat du jeton. Sans ca, un client dont le 1er {@code /enregistrer} a reussi cote
     * serveur mais dont la reponse s'est perdue (panne reseau) n'aurait plus AUCUN moyen de
     * recuperer : le jeton, a usage unique, serait deja marque {@code utilise}, et il faudrait qu'un
     * partenaire lui en regenere un nouveau — alors que le chemin login/mot de passe, lui, permet de
     * rejouer indefiniment la MEME installation. Le controle utilise/expire du jeton ne s'applique
     * donc qu'a la creation d'un NOUVEAU {@link LicenceServeur} (installation jamais vue pour ce
     * {@code identifiantInstallationStable}), jamais a sa reutilisation idempotente.
     *
     * <p><b>Fix round 2 — perimetre de la reutilisation idempotente</b> : {@code
     * identifiantInstallationStable} est un champ du corps de la requete, donc controle par
     * l'appelant. {@code findByCompteIdAndIdentifiantInstallationStable} seul (sans composant role/
     * customerId dans sa cle) ne suffit PAS a autoriser la reutilisation : un porteur d'un jeton
     * valide (mono-customer) qui connaitrait (fuite, log, devine) l'{@code
     * identifiantInstallationStable} d'un AUTRE {@link LicenceServeur} deja enrole sous le MEME
     * compte (le {@code PARTNER} du compte, ou le {@code CLIENT} d'un AUTRE client) pourrait sinon
     * declencher la branche de reutilisation sur CETTE ligne et obtenir un nouveau {@code
     * codeEnrolement} puis, via {@code /cle}, une rotation de sa cle API — une prise de controle
     * cross-tenant. {@link #estReutilisationLegitimePourCeJeton} verrouille donc la reutilisation a
     * EXACTEMENT ce que CE jeton autorise (meme role {@code CLIENT}, meme {@code customerId}, ET pas
     * deja pleinement enrole — {@code cleApiHash == null} — pour ne jamais re-emettre un {@code
     * codeEnrolement}/re-roter une cle sur un serveur qui en a deja une) ; toute correspondance qui
     * echoue a ce controle est refusee explicitement (409), SANS toucher a la ligne existante NI
     * tenter de creer un doublon (qui violerait de toute facon la contrainte d'unicite {@code
     * (compteId, identifiantInstallationStable)}).
     */
    private static boolean estReutilisationLegitimePourCeJeton(LicenceServeur existant, JetonClient jeton) {
        return existant.getRole() == ServerRole.CLIENT
                && java.util.Objects.equals(existant.getCustomerId(), jeton.getCustomerId())
                && existant.getCleApiHash() == null;
    }

    private ResponseEntity<Map<String, Object>> enregistrerViaJeton(EnregistrerRequest body) {
        Optional<JetonClient> jetonOpt = jetonClientRepository.findByCode(body.jeton());
        if (jetonOpt.isEmpty()) {
            log.warn("Enrolement licence refuse : jeton inconnu");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JetonClient jeton = jetonOpt.get();

        // Verrou pessimiste sur la ligne du compte (meme rationale et meme discipline que le chemin
        // login/mot de passe) : serialise les enrolements concurrents sous le MEME compte, AVANT de
        // decider si c'est une reutilisation idempotente ou une nouvelle consommation du jeton.
        CompteLicence compte = compteLicenceRepository.findByIdForUpdate(jeton.getCompteId()).orElseThrow();
        if (!compte.isActif()) {
            log.warn("Enrolement licence refuse : compte {} (jeton {}) inactif", compte.getId(), jeton.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<LicenceServeur> existant = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), body.identifiantInstallationStable());

        LicenceServeur serveur;
        if (existant.isPresent() && !estReutilisationLegitimePourCeJeton(existant.get(), jeton)) {
            // identifiantInstallationStable correspond a un serveur existant, mais qui n'appartient
            // PAS a ce jeton (role different — ex. le PARTNER du compte —, customerId different, ou
            // deja pleinement enrole) : refuse explicitement, SANS toucher a la ligne existante (pas
            // de nouveau codeEnrolement, pas de rotation de cle) et SANS tenter de creer un doublon.
            log.warn("Enrolement licence refuse (jeton) : identifiantInstallationStable deja associe au "
                    + "serveur {} (role {}, customer {}), incompatible avec le jeton {} (customer {})",
                    existant.get().getId(), existant.get().getRole(), existant.get().getCustomerId(),
                    jeton.getId(), jeton.getCustomerId());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } else if (existant.isPresent()) {
            // Idempotence : MEME installation (identifiantInstallationStable deja connu pour ce
            // compte, ET appartenant legitimement a ce jeton — voir estReutilisationLegitimePourCeJeton)
            // — reutilise le LicenceServeur existant, SANS consulter l'etat utilise/expire du jeton
            // (voir javadoc ci-dessus) ni consommer un 2e siege du quota.
            serveur = existant.get();
            if (serveur.getStatut() != LicenceServeur.LicenceServeurStatut.ACTIF) {
                log.warn("Enrolement licence refuse : serveur {} revoque", serveur.getId());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (isBlank(serveur.getAdresseDeclaree()) && !isBlank(body.adresseDeclaree())) {
                serveur.setAdresseDeclaree(body.adresseDeclaree());
            }
        } else {
            // Nouvelle installation : LA le jeton doit etre non-utilise et non-expire.
            if (jeton.isUtilise()) {
                log.warn("Enrolement licence refuse : jeton {} deja utilise", jeton.getId());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (jeton.getExpireA() == null || !jeton.getExpireA().isAfter(Instant.now())) {
                log.warn("Enrolement licence refuse : jeton {} expire", jeton.getId());
                return ResponseEntity.status(HttpStatus.GONE).build();
            }

            long actifs = licenceServeurRepository.countByCompteIdAndStatut(
                    compte.getId(), LicenceServeur.LicenceServeurStatut.ACTIF);
            if (actifs >= compte.getNombreServeursAutorises()) {
                log.warn("Enrolement licence refuse : quota atteint pour le compte {} (jeton {})", compte.getId(), jeton.getId());
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }

            serveur = new LicenceServeur();
            serveur.setCompteId(compte.getId());
            serveur.setRole(ServerRole.CLIENT);
            serveur.setCustomerId(jeton.getCustomerId());
            serveur.setIdentifiantInstallationStable(body.identifiantInstallationStable());
            serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
            if (!isBlank(body.adresseDeclaree())) {
                serveur.setAdresseDeclaree(body.adresseDeclaree());
            }

            jeton.setUtilise(true);
            jetonClientRepository.save(jeton);
        }

        String code = LicenceCles.generate();
        Instant expireA = Instant.now().plus(DUREE_CODE_ENROLEMENT);
        serveur.setCodeEnrolement(code);
        serveur.setCodeEnrolementExpireA(expireA);
        serveur.setCodeEnrolementUtilise(false);
        licenceServeurRepository.save(serveur);
        log.info("Serveur licence CLIENT {} enregistre via jeton (compte {}, customer {}), code d'enrolement delivre",
                serveur.getId(), compte.getId(), jeton.getCustomerId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reponseEnregistrement(serveur, code, expireA));
    }

    /**
     * Reponse de {@code /enregistrer} : identique a avant (seulement {@code codeEnrolement}/
     * {@code codeEnrolementExpireA}) pour un {@code PARTNER}. Pour un {@code CLIENT} (Task 6),
     * ajoute {@code adressePartenaireResolue} (adresse auto-declaree du {@code PARTNER} ACTIF de ce
     * compte, {@code null} si inconnue) et {@code codeLocal} ({@link LicenceServeur#getCodeLocalPartenaire()},
     * {@code null} tant que le partenaire ne l'a pas encore pousse via {@link #codePartenaire}) —
     * {@link LicenceBootstrap} (cote CLIENT) retente cet appel periodiquement jusqu'a ce que les
     * deux soient non-nuls (voir sa javadoc).
     */
    private Map<String, Object> reponseEnregistrement(LicenceServeur serveur, String code, Instant expireA) {
        if (serveur.getRole() != ServerRole.CLIENT) {
            return Map.of("codeEnrolement", code, "codeEnrolementExpireA", expireA.toString());
        }
        Map<String, Object> reponse = new LinkedHashMap<>();
        reponse.put("codeEnrolement", code);
        reponse.put("codeEnrolementExpireA", expireA.toString());
        reponse.put("adressePartenaireResolue", adressePartenaireActifDuCompte(serveur.getCompteId()));
        reponse.put("codeLocal", serveur.getCodeLocalPartenaire());
        return reponse;
    }

    /** Adresse auto-declaree du {@code PARTNER} {@code ACTIF} de ce compte (il n'en existe au plus
     * qu'un, voir la contrainte d'unicite verifiee a l'enrolement) ; {@code null} si inconnue ou
     * absente. Reutilise {@link LicenceServeurRepository#findByCompteId}, aucune nouvelle requete. */
    private String adressePartenaireActifDuCompte(Long compteId) {
        return licenceServeurRepository.findByCompteId(compteId).stream()
                .filter(s -> s.getRole() == ServerRole.PARTNER && s.getStatut() == LicenceServeur.LicenceServeurStatut.ACTIF)
                .map(LicenceServeur::getAdresseDeclaree)
                .findFirst()
                .orElse(null);
    }

    /**
     * Genere un jeton d'installation CLIENT a usage unique pour un des clients du {@code PARTNER}
     * appelant. L'appelant est resolu via le principal pose par {@link LicenceApiKeyAuthFilter}
     * (l'id du {@link LicenceServeur} authentifie par sa cle API) — jamais depuis le corps de la
     * requete, qui ne porte QUE le {@code customerId} cible.
     *
     * <p>Protegee par {@code SecurityConfig.licenceApiFilterChain} ({@code
     * hasRole(LicenceApiKeyAuthFilter.ROLE)}) : seule une cle API active peut authentifier l'appel.
     * Le controle additionnel ici (role {@code PARTNER}, statut {@code ACTIF}) est donc une
     * verification metier, pas une 2e authentification — un {@code CLIENT} authentifie par sa
     * propre cle active ne peut pas generer de jeton pour un AUTRE client.
     */
    @PostMapping("/jetons")
    @Transactional
    public ResponseEntity<Map<String, Object>> jetons(@RequestBody(required = false) JetonRequest body) {
        if (body == null || body.customerId() == null) {
            return ResponseEntity.badRequest().build();
        }

        LicenceServeur partenaire = partenaireActifAuthentifieOuNull();
        if (partenaire == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String code = LicenceCles.generate();
        Instant expireA = Instant.now().plus(DUREE_VALIDITE_JETON);
        JetonClient jeton = new JetonClient();
        jeton.setCompteId(partenaire.getCompteId());
        jeton.setCustomerId(body.customerId());
        jeton.setCode(code);
        jeton.setExpireA(expireA);
        jeton.setUtilise(false);
        jetonClientRepository.save(jeton);
        log.info("Jeton client genere par le partenaire {} (compte {}) pour le client {}",
                partenaire.getId(), partenaire.getCompteId(), body.customerId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("jeton", code, "expireA", expireA.toString()));
    }

    /**
     * Resout l'appelant authentifie (principal pose par {@link LicenceApiKeyAuthFilter}) et verifie
     * qu'il s'agit d'un {@link LicenceServeur} {@code PARTNER} {@code ACTIF} — factorise le controle
     * deja fait par {@link #jetons}, reutilise par {@link #nouveauxServeurs} et {@link
     * #codePartenaire} (Task 6). {@code null} si le principal est absent/d'un autre type, ou si le
     * {@link LicenceServeur} resolu n'est pas un {@code PARTNER} {@code ACTIF}.
     */
    private LicenceServeur partenaireActifAuthentifieOuNull() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Long partenaireId)) {
            log.warn("Appel licence refuse : principal inattendu {}", principal);
            return null;
        }
        LicenceServeur partenaire = licenceServeurRepository.findById(partenaireId).orElse(null);
        if (partenaire == null || partenaire.getRole() != ServerRole.PARTNER
                || partenaire.getStatut() != LicenceServeur.LicenceServeurStatut.ACTIF) {
            log.warn("Appel licence refuse : appelant {} n'est pas un PARTNER actif", partenaireId);
            return null;
        }
        return partenaire;
    }

    /**
     * Decouverte cote PARTNER (Task 6, {@code LicenceDecouverteScheduler}) : les {@link
     * LicenceServeur} {@code CLIENT} de SON PROPRE compte (jamais un autre, {@code compteId} resolu
     * depuis le principal authentifie, jamais depuis la requete) pas encore notifies — nouvelles
     * installations jamais vues, ou revocations pas encore propagees (voir {@link
     * LicenceServeur#isNotifiePartenaire()}).
     */
    @GetMapping("/nouveaux-serveurs")
    public ResponseEntity<List<NouveauServeurDto>> nouveauxServeurs() {
        LicenceServeur partenaire = partenaireActifAuthentifieOuNull();
        if (partenaire == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<NouveauServeurDto> resultat = licenceServeurRepository
                .findByCompteIdAndRoleAndNotifiePartenaireFalse(partenaire.getCompteId(), ServerRole.CLIENT)
                .stream()
                .map(s -> new NouveauServeurDto(s.getIdentifiantInstallationStable(), s.getCustomerId(), s.getStatut()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resultat);
    }

    /**
     * Le PARTENAIRE appelant signale avoir materialise localement (ou revoque) le {@link
     * LicenceServeur} {@code CLIENT} {@code identifiantInstallationStable} et pousse le {@code
     * codeLocal} (code d'enrolement de SON {@code SecondaryServer} local) que ce client recevra au
     * prochain appel de {@code /enregistrer} (voir {@link #reponseEnregistrement}). Marque {@code
     * notifiePartenaire=true} — ce {@link LicenceServeur} ne reapparaitra plus dans {@link
     * #nouveauxServeurs} tant que son {@code statut} ne change pas a nouveau.
     *
     * <p><b>Perimetre</b> : {@code identifiantInstallationStable} est cherche sous le SEUL {@code
     * compteId} de l'appelant authentifie (jamais un autre compte), exactement comme {@link #jetons}
     * resout {@code compteId} depuis le principal — un partenaire ne peut donc jamais pousser (ni
     * decouvrir) un code pour un client d'un AUTRE compte.
     */
    @PostMapping("/code-partenaire")
    @Transactional
    public ResponseEntity<Void> codePartenaire(@RequestBody(required = false) CodePartenaireRequest body) {
        // codeLocal n'est PAS verifie ici (revue finale, C2) : il ne l'est qu'une fois la cible connue,
        // voir plus bas — un accuse de revocation n'en porte legitimement aucun.
        if (body == null || isBlank(body.identifiantInstallationStable())) {
            return ResponseEntity.badRequest().build();
        }
        LicenceServeur partenaire = partenaireActifAuthentifieOuNull();
        if (partenaire == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        LicenceServeur client = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(partenaire.getCompteId(), body.identifiantInstallationStable())
                .filter(s -> s.getRole() == ServerRole.CLIENT)
                .orElse(null);
        if (client == null) {
            log.warn("Code-partenaire refuse : {} introuvable (ou pas CLIENT) pour le compte {}",
                    body.identifiantInstallationStable(), partenaire.getCompteId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        // Revue finale, C2 : pour un client REVOQUE, cet appel est un pur accuse de revocation (rien a
        // enroler) — le SecondaryServer local d'un client deja inscrit sync n'a plus de code
        // (EnrollmentController l'efface apres succes), un codeLocal vide est donc legitime ici. Le
        // refuser laissait notifiePartenaire=false a vie (re-sondage infini). Pour tout autre statut,
        // codeLocal reste obligatoire (400), exactement comme avant.
        boolean accuseDeRevocation = client.getStatut() == LicenceServeur.LicenceServeurStatut.REVOQUE;
        if (!accuseDeRevocation && isBlank(body.codeLocal())) {
            return ResponseEntity.badRequest().build();
        }
        if (!accuseDeRevocation) {
            client.setCodeLocalPartenaire(body.codeLocal());
        }
        client.setNotifiePartenaire(true);
        licenceServeurRepository.save(client);
        log.info("{} du partenaire {} enregistre pour le serveur licence {} (compte {})",
                accuseDeRevocation ? "Accuse de revocation" : "Code local",
                partenaire.getId(), client.getId(), partenaire.getCompteId());

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/cle")
    @Transactional
    public ResponseEntity<Map<String, String>> cle(@RequestBody(required = false) CleRequest body) {
        if (body == null || isBlank(body.codeEnrolement())) {
            return ResponseEntity.badRequest().build();
        }

        Optional<LicenceServeur> found = licenceServeurRepository.findByCodeEnrolement(body.codeEnrolement());
        if (found.isEmpty()) {
            log.warn("Echange de cle licence refuse : code inconnu");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        LicenceServeur serveur = found.get();

        if (serveur.getStatut() != LicenceServeur.LicenceServeurStatut.ACTIF) {
            log.warn("Echange de cle licence refuse : serveur {} revoque", serveur.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (serveur.isCodeEnrolementUtilise()) {
            log.warn("Echange de cle licence refuse : code deja utilise pour le serveur {}", serveur.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (serveur.getCodeEnrolementExpireA() == null || !serveur.getCodeEnrolementExpireA().isAfter(Instant.now())) {
            log.warn("Echange de cle licence refuse : code expire pour le serveur {}", serveur.getId());
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        String cleApi = LicenceCles.generate();
        serveur.setCleApiHash(LicenceCles.hash(cleApi));
        serveur.setCodeEnrolementUtilise(true);
        licenceServeurRepository.save(serveur);
        log.info("Serveur licence {} enrole (cle API delivree)", serveur.getId());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("cleApi", cleApi));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
