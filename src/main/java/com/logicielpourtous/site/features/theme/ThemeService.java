package com.logicielpourtous.site.features.theme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglages de theme : valeurs par defaut (source) + valeurs modifiees par l'administrateur (base).
 * Le fichier CSS genere ne contient que des valeurs validees ou fixes (voir {@link ThemeValeurs}) et est controle
 * une derniere fois contre une liste blanche stricte avant d'etre rendu.
 */
@Service
public class ThemeService {

    private static final Logger LOG = LoggerFactory.getLogger(ThemeService.class);

    public enum Origine { HERITE, MODIFIE }

    /**
     * Etat d'un reglage pour l'ecran d'administration.
     *
     * @param valeur    valeur effective (une reference par defaut est resolue vers la valeur de sa cible)
     * @param origine   {@code HERITE} (valeur par defaut) ou {@code MODIFIE} (valeur de l'administrateur)
     * @param groupe    identifiant du groupe (voir {@link ThemeValeurs#GROUPES})
     * @param reference cle suivie quand la valeur par defaut est une reference ; sinon {@code null}
     */
    public record Etat(String valeur, Origine origine, String groupe, String reference) {
    }

    /** Fichier CSS et son identifiant de version (empreinte du contenu). */
    public record Rendu(String css, String version) {
    }

    /** Structure stricte du fichier : un bloc :root avec des lignes --socle-<cle>:<valeur>; */
    private static final Pattern STRUCTURE = Pattern
            .compile(":root\\{\\n(--socle-[a-z0-9-]+:[A-Za-z0-9#(),.' \\-]+;\\n)+\\}\\n");
    private static final Pattern CARACTERES = Pattern.compile("[A-Za-z0-9#:;{}()\\-,.' \\n]+");
    private static final String[] INTERDITS = { "url(", "@import", "expression", "javascript:", "</", "/*" };

    private final ThemeParDefautSource source;
    private final ThemeReglageRepository reglages;
    private final ThemeJournalRepository journal;

    public ThemeService(ThemeParDefautSource source, ThemeReglageRepository reglages, ThemeJournalRepository journal) {
        this.source = source;
        this.reglages = reglages;
        this.journal = journal;
    }

    /** Valeurs par defaut, re-validees a chaque lecture (la source peut changer). */
    private Map<String, String> defauts() {
        return ThemeValeurs.validerDefauts(source.valeurs());
    }

    /** Valeurs modifiees ; une ligne invalide (ecrite hors du service) est ignoree, jamais emise. */
    private Map<String, String> modifiees() {
        Map<String, String> m = new HashMap<>();
        for (ThemeReglage r : reglages.findAll()) {
            try {
                m.put(r.getCle(), ThemeValeurs.valider(r.getCle(), r.getValeur()));
            } catch (IllegalArgumentException e) {
                LOG.error("Réglage de thème invalide en base, ignoré : {}", r.getCle().length() > 60 ? "?" : r.getCle());
            }
        }
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Etat> reglages() {
        Map<String, String> defauts = defauts();
        Map<String, String> modifiees = modifiees();
        Map<String, Etat> etat = new LinkedHashMap<>();
        for (String cle : ThemeValeurs.cles()) {
            String groupe = ThemeValeurs.groupe(cle);
            String m = modifiees.get(cle);
            if (m != null) {
                etat.put(cle, new Etat(m, Origine.MODIFIE, groupe, null));
                continue;
            }
            String d = defauts.get(cle);
            if (ThemeValeurs.estReference(d)) {
                String cible = ThemeValeurs.cibleReference(d);
                String effective = modifiees.getOrDefault(cible, defauts.get(cible));
                etat.put(cle, new Etat(effective, Origine.HERITE, groupe, cible));
            } else {
                etat.put(cle, new Etat(d, Origine.HERITE, groupe, null));
            }
        }
        return etat;
    }

    @Transactional
    public void definir(Long adminId, String cle, String valeur) {
        String validee = ThemeValeurs.valider(cle, valeur);
        Optional<ThemeReglage> existant = reglages.findById(cle);
        String ancienne = existant.map(ThemeReglage::getValeur).orElse(null);
        if (validee.equals(ancienne)) {
            return;
        }
        if (existant.isPresent()) {
            ThemeReglage r = existant.get();
            r.remplacer(validee, adminId);
            reglages.saveAndFlush(r);
        } else {
            reglages.saveAndFlush(new ThemeReglage(cle, validee, adminId));
        }
        journal.save(new ThemeJournal(cle, ancienne, validee, adminId));
    }

    @Transactional
    public void reinitialiser(Long adminId, String cle) {
        ThemeValeurs.verifierCle(cle);
        reglages.findById(cle).ifPresent(r -> {
            journal.save(new ThemeJournal(cle, r.getValeur(), null, adminId));
            reglages.delete(r);
            reglages.flush();
        });
    }

    @Transactional
    public void toutReinitialiser(Long adminId) {
        for (ThemeReglage r : reglages.findAll()) {
            journal.save(new ThemeJournal(r.getCle(), r.getValeur(), null, adminId));
            reglages.delete(r);
        }
        reglages.flush();
    }

    /** Identifiant de version du CSS : empreinte du fichier genere (change avec un reglage ou un nouveau defaut). */
    @Transactional(readOnly = true)
    public String versionCss() {
        return rendu().version();
    }

    @Transactional(readOnly = true)
    public String css() {
        return rendu().css();
    }

    /** Le fichier CSS et sa version, calcules ensemble (une seule lecture de la base). */
    @Transactional(readOnly = true)
    public Rendu rendu() {
        Map<String, String> defauts = defauts();
        String css;
        try {
            css = construire(defauts, modifiees());
            if (!sain(css)) {
                throw new IllegalStateException("CSS de thème refusé par la liste blanche.");
            }
        } catch (RuntimeException e) {
            LOG.error("CSS de thème avec réglages refusé ({}), retour aux valeurs par défaut.", e.getClass().getSimpleName());
            css = construire(defauts, Map.of());
            if (!sain(css)) {
                throw new IllegalStateException("CSS de thème par défaut refusé par la liste blanche.");
            }
        }
        return new Rendu(css, empreinte(css));
    }

    private static String construire(Map<String, String> defauts, Map<String, String> modifiees) {
        StringBuilder sb = new StringBuilder(":root{\n");
        for (String cle : ThemeValeurs.cles()) {
            String valeur = modifiees.containsKey(cle) ? modifiees.get(cle) : defauts.get(cle);
            sb.append("--socle-").append(cle).append(':').append(ThemeValeurs.rendreCss(cle, valeur)).append(";\n");
        }
        return sb.append("}\n").toString();
    }

    /** Derniere barriere : liste blanche de caracteres, structure exacte, aucun motif dangereux. */
    static boolean sain(String css) {
        if (css == null || !CARACTERES.matcher(css).matches() || !STRUCTURE.matcher(css).matches()) {
            return false;
        }
        String minuscules = css.toLowerCase(Locale.ROOT);
        for (String interdit : INTERDITS) {
            if (minuscules.contains(interdit)) {
                return false;
            }
        }
        return true;
    }

    private static String empreinte(String texte) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
