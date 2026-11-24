package com.logicielpourtous.site.features.theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regles de validation du theme : liste FERMEE de cles, une regle stricte par cle. Ce sont les seules valeurs qui
 * peuvent finir dans {@code /css/theme.css} : aucune valeur saisie n'est jamais recopiee telle quelle, tout est
 * re-genere a partir de donnees validees (couleur normalisee, entier borne, element d'une liste fermee).
 * Les messages d'erreur sont fixes : ils ne contiennent ni la valeur recue ni le detail des regles.
 */
public final class ThemeValeurs {

    /** Longueur maximale d'une valeur (verifiee avant tout autre traitement ; = taille de la colonne). */
    public static final int LONGUEUR_MAX = 40;

    static final String PREFIXE_REF = "ref:";

    private static final String MSG_CLE = "Réglage de thème inconnu.";
    private static final String MSG_VALEUR = "Valeur de réglage de thème invalide.";

    /** Groupes d'ecran, dans l'ordre d'affichage : identifiant -> libelle. */
    public static final Map<String, String> GROUPES = groupes();

    private enum Type { COULEUR, ENTIER, ENUM, POLICE }

    private record Regle(Type type, int min, int max, Set<String> choix, String groupe) {
        static Regle couleur(String g) { return new Regle(Type.COULEUR, 0, 0, Set.of(), g); }
        static Regle entier(String g, int min, int max) { return new Regle(Type.ENTIER, min, max, Set.of(), g); }
        static Regle choix(String g, String... c) {
            return new Regle(Type.ENUM, 0, 0, Collections.unmodifiableSet(new java.util.LinkedHashSet<>(List.of(c))), g);
        }
        static Regle police(String g) { return new Regle(Type.POLICE, 0, 0, Set.of(), g); }
    }

    /** Piles de polices : texte FIXE dans le code, jamais issu d'une saisie. */
    private static final Map<String, String> POLICES = polices();

    private static final Map<String, Regle> REGLES = regles();

    private ThemeValeurs() {
    }

    private static Map<String, String> groupes() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("general", "Général");
        g.put("barre", "Barre supérieure");
        g.put("tuiles", "Tuiles");
        g.put("liste", "Liste");
        g.put("fiche", "Fiche");
        g.put("boutons", "Boutons");
        return Collections.unmodifiableMap(g);
    }

    private static Map<String, String> polices() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("systeme", "system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif");
        p.put("inter", "'Inter',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif");
        p.put("roboto", "'Roboto',system-ui,-apple-system,'Segoe UI',Arial,sans-serif");
        p.put("source-sans-3", "'Source Sans 3',system-ui,-apple-system,'Segoe UI',Arial,sans-serif");
        return Collections.unmodifiableMap(p);
    }

    private static Map<String, Regle> regles() {
        Map<String, Regle> r = new LinkedHashMap<>();
        // general
        r.put("couleur-primaire", Regle.couleur("general"));
        r.put("couleur-secondaire", Regle.couleur("general"));
        r.put("couleur-fond", Regle.couleur("general"));
        r.put("couleur-texte", Regle.couleur("general"));
        r.put("couleur-bordure", Regle.couleur("general"));
        r.put("police", Regle.police("general"));
        r.put("taille-texte", Regle.entier("general", 12, 18));
        r.put("epaisseur-trait", Regle.entier("general", 0, 4));
        r.put("arrondi", Regle.entier("general", 0, 24));
        // barre superieure
        r.put("topbar-fond", Regle.couleur("barre"));
        r.put("topbar-texte", Regle.couleur("barre"));
        r.put("topbar-hauteur", Regle.entier("barre", 36, 64));
        // tuiles (les 8 carres d'application suivent ManifestePlugin.COULEURS_TUILE : test d'egalite)
        r.put("tuile-fond", Regle.couleur("tuiles"));
        r.put("tuile-arrondi", Regle.entier("tuiles", 0, 32));
        r.put("tuile-taille-icone", Regle.entier("tuiles", 24, 64));
        for (String c : List.of("primaire", "violet", "sarcelle", "orange", "ambre", "rose", "ardoise", "vert")) {
            r.put("app-" + c, Regle.couleur("tuiles"));
        }
        // liste
        r.put("liste-densite", Regle.choix("liste", "compact", "normal", "aere"));
        r.put("liste-lignes-alternees", Regle.choix("liste", "oui", "non"));
        r.put("liste-entete-fond", Regle.couleur("liste"));
        // fiche
        r.put("fiche-statut-style", Regle.choix("fiche", "chevron", "pilule"));
        r.put("fiche-statut-actif-fond", Regle.couleur("fiche"));
        // boutons
        r.put("bouton-arrondi", Regle.entier("boutons", 0, 24));
        r.put("bouton-primaire-fond", Regle.couleur("boutons"));
        return Collections.unmodifiableMap(r);
    }

    /** Cles autorisees, dans l'ordre fixe d'affichage et de generation du CSS. */
    public static List<String> cles() {
        return List.copyOf(REGLES.keySet());
    }

    /** Identifiants de polices autorisees. */
    public static Set<String> policesAutorisees() {
        return POLICES.keySet();
    }

    /**
     * Genre de controle d'un reglage pour l'ecran d'administration : {@code couleur}, {@code entier} (bornes
     * min/max), {@code choix} (valeurs dans l'ordre) ou {@code police} (identifiants autorises).
     */
    public record Controle(String genre, int min, int max, List<String> choix) {
    }

    /** Description du controle d'une cle (cle inconnue : {@link IllegalArgumentException}). */
    public static Controle controle(String cle) {
        Regle r = regle(cle);
        switch (r.type()) {
            case COULEUR:
                return new Controle("couleur", 0, 0, List.of());
            case ENTIER:
                return new Controle("entier", r.min(), r.max(), List.of());
            case ENUM:
                return new Controle("choix", 0, 0, List.copyOf(r.choix()));
            default:
                return new Controle("police", 0, 0, List.copyOf(POLICES.keySet()));
        }
    }

    /** Identifiant du groupe d'une cle (voir {@link #GROUPES}). */
    public static String groupe(String cle) {
        return regle(cle).groupe();
    }

    private static Regle regle(String cle) {
        Regle r = cle == null ? null : REGLES.get(cle);
        if (r == null) {
            throw new IllegalArgumentException(MSG_CLE);
        }
        return r;
    }

    /** Verifie qu'une cle est connue (sans rien echoer). */
    static void verifierCle(String cle) {
        regle(cle);
    }

    /** Longueur et espaces : on ne retire que l'espace ordinaire, tout le reste est juge par la regle stricte. */
    private static String nettoyer(String valeur) {
        if (valeur == null || valeur.length() > LONGUEUR_MAX) {
            throw new IllegalArgumentException(MSG_VALEUR);
        }
        int debut = 0;
        int fin = valeur.length();
        while (debut < fin && valeur.charAt(debut) == ' ') {
            debut++;
        }
        while (fin > debut && valeur.charAt(fin - 1) == ' ') {
            fin--;
        }
        if (debut == fin) {
            throw new IllegalArgumentException(MSG_VALEUR);
        }
        return valeur.substring(debut, fin);
    }

    /**
     * Valide une valeur saisie par un utilisateur et la renvoie normalisee (couleurs en minuscules, entiers sans zeros
     * en tete). Une reference {@code ref:} n'est PAS une valeur utilisateur valide.
     */
    public static String valider(String cle, String valeur) {
        Regle r = regle(cle);
        String v = nettoyer(valeur);
        switch (r.type()) {
            case COULEUR:
                return couleur(v);
            case ENTIER:
                return entier(v, r.min(), r.max());
            case ENUM:
                if (!r.choix().contains(v)) {
                    throw new IllegalArgumentException(MSG_VALEUR);
                }
                return v;
            case POLICE:
                if (!POLICES.containsKey(v)) {
                    throw new IllegalArgumentException(MSG_VALEUR);
                }
                return v;
            default:
                throw new IllegalArgumentException(MSG_VALEUR);
        }
    }

    private static String couleur(String v) {
        if (v.length() != 7 || v.charAt(0) != '#') {
            throw new IllegalArgumentException(MSG_VALEUR);
        }
        StringBuilder sb = new StringBuilder("#");
        for (int i = 1; i < 7; i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'F') {
                sb.append((char) (c + ('a' - 'A')));
            } else {
                throw new IllegalArgumentException(MSG_VALEUR);
            }
        }
        return sb.toString();
    }

    private static String entier(String v, int min, int max) {
        if (v.length() > 3) {
            throw new IllegalArgumentException(MSG_VALEUR);
        }
        int n = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(MSG_VALEUR);
            }
            n = n * 10 + (c - '0');
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(MSG_VALEUR);
        }
        return Integer.toString(n);
    }

    /**
     * Valide une valeur par defaut : comme {@link #valider}, ou une reference {@code ref:<cle>} vers une AUTRE cle
     * du meme type. (Les references n'existent que dans les valeurs par defaut, jamais dans les saisies.)
     */
    public static String validerDefaut(String cle, String valeur) {
        Regle r = regle(cle);
        String v = nettoyer(valeur);
        if (v.startsWith(PREFIXE_REF)) {
            String cible = v.substring(PREFIXE_REF.length());
            Regle rc = REGLES.get(cible);
            if (rc == null || cible.equals(cle) || rc.type() != r.type()) {
                throw new IllegalArgumentException(MSG_VALEUR);
            }
            return v;
        }
        return valider(cle, v);
    }

    /**
     * Valide un jeu complet de valeurs par defaut (une valeur par cle, ni plus ni moins, pas de reference vers une
     * reference). Echec : {@link IllegalStateException} qui ne nomme que les cles fautives.
     */
    public static Map<String, String> validerDefauts(Map<String, String> brutes) {
        List<String> fautes = new ArrayList<>();
        Map<String, String> ok = new LinkedHashMap<>();
        for (String cle : brutes.keySet()) {
            if (!REGLES.containsKey(cle)) {
                fautes.add("clé inconnue dans le fichier");
            }
        }
        for (String cle : REGLES.keySet()) {
            String v = brutes.get(cle);
            if (v == null) {
                fautes.add(cle + " (manquante)");
                continue;
            }
            try {
                ok.put(cle, validerDefaut(cle, v));
            } catch (IllegalArgumentException e) {
                fautes.add(cle + " (valeur invalide)");
            }
        }
        for (Map.Entry<String, String> e : ok.entrySet()) {
            if (e.getValue().startsWith(PREFIXE_REF)) {
                String cible = e.getValue().substring(PREFIXE_REF.length());
                String valeurCible = ok.get(cible);
                if (valeurCible != null && valeurCible.startsWith(PREFIXE_REF)) {
                    fautes.add(e.getKey() + " (référence vers une référence)");
                }
            }
        }
        if (!fautes.isEmpty()) {
            throw new IllegalStateException("Valeurs de thème par défaut invalides : " + String.join(", ", fautes));
        }
        return Collections.unmodifiableMap(ok);
    }

    // Variables DERIVEES des trois reglages a choix : valeurs CONSTANTES du code, jamais issues d'un texte saisi.
    private static final Map<String, String> DENSITE_PADDING = Map.of(
            "compact", ".25rem .5rem", "normal", ".5rem .75rem", "aere", ".9rem 1rem");
    private static final Map<String, String> LIGNE_PAIRE = Map.of("oui", "#f8f9fa", "non", "transparent");
    private static final Map<String, String> STATUT_RAYON = Map.of("chevron", "0", "pilule", "999px");
    private static final Map<String, String> STATUT_POINTE = Map.of("chevron", "10px", "pilule", "0px");

    /** Noms (sans {@code --socle-}) des variables derivees, dans leur ordre fixe d'emission. */
    public static List<String> nomsVariablesDerivees() {
        return List.of("liste-cellule-padding", "liste-ligne-paire", "statut-rayon", "statut-pointe");
    }

    /**
     * Variables derivees a partir des valeurs EFFECTIVES (deja validees) des trois reglages a choix. Ordre de
     * {@link #nomsVariablesDerivees()}.
     */
    public static Map<String, String> variablesDerivees(String densite, String alternees, String statutStyle) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("liste-cellule-padding", constante(DENSITE_PADDING, densite));
        m.put("liste-ligne-paire", constante(LIGNE_PAIRE, alternees));
        m.put("statut-rayon", constante(STATUT_RAYON, statutStyle));
        m.put("statut-pointe", constante(STATUT_POINTE, statutStyle));
        return m;
    }

    private static String constante(Map<String, String> table, String cle) {
        String v = cle == null ? null : table.get(cle);
        if (v == null) {
            throw new IllegalArgumentException(MSG_VALEUR);
        }
        return v;
    }

    /** Est-ce une reference ({@code ref:<cle>}) ? */
    static boolean estReference(String valeur) {
        return valeur != null && valeur.startsWith(PREFIXE_REF);
    }

    /** Cle cible d'une reference (valeur deja validee). */
    static String cibleReference(String valeur) {
        return valeur.substring(PREFIXE_REF.length());
    }

    /**
     * Texte CSS d'une valeur validee. La valeur est re-validee (defense en profondeur) puis RE-GENEREE : entier +
     * {@code px}, couleur normalisee, mot de liste fermee, pile de police constante, ou {@code var(--socle-<cle>)}.
     */
    public static String rendreCss(String cle, String valeur) {
        Regle r = regle(cle);
        String v = validerDefaut(cle, valeur);
        if (estReference(v)) {
            return "var(--socle-" + cibleReference(v) + ")";
        }
        switch (r.type()) {
            case ENTIER:
                return v + "px";
            case POLICE:
                return POLICES.get(v);
            default:
                return v;
        }
    }
}
