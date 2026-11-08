package com.logicielpourtous.site.features.identite;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Controle et normalisation des numeros d'identite d'une societe. Fonctions pures, sans service externe. */
public final class NumerosSociete {

    /** Pays de l'Union europeenne hors France (la TVA francaise se controle par le SIREN). EL = Grece, XI = Irlande du Nord. */
    private static final Set<String> PAYS_TVA_ETRANGERS = Set.of(
            "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "EL", "ES", "FI", "HR", "HU", "IE", "IT",
            "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK", "XI");

    private static final Pattern TVA_ETRANGERE = Pattern.compile("^([A-Z]{2})[A-Z0-9]{2,12}$");

    /** Les SIRET de La Poste ne respectent pas Luhn : somme des chiffres multiple de 5 a la place. */
    private static final String SIREN_LA_POSTE = "356000000";

    private NumerosSociete() {
    }

    /** Retire espaces, points et tirets ; {@code null} si le resultat est vide. */
    public static String chiffres(String brut) {
        if (brut == null) {
            return null;
        }
        String s = brut.replaceAll("[\\s.\\-]", "");
        return s.isEmpty() ? null : s;
    }

    /** Comme {@link #chiffres}, en majuscules. */
    public static String tva(String brut) {
        String s = chiffres(brut);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    public static boolean sirenValide(String s) {
        return s != null && s.matches("\\d{9}") && luhn(s);
    }

    public static boolean siretValide(String s) {
        if (s == null || !s.matches("\\d{14}")) {
            return false;
        }
        return luhn(s) || (s.startsWith(SIREN_LA_POSTE) && sommeDesChiffres(s) % 5 == 0);
    }

    public static String sirenDe(String siret) {
        return siret.substring(0, 9);
    }

    /** {@code FR} + cle a deux chiffres + SIREN, avec {@code cle = (12 + 3 * (SIREN mod 97)) mod 97}. */
    public static String tvaFrancaise(String siren) {
        long cle = (12 + 3 * (Long.parseLong(siren) % 97)) % 97;
        return "FR" + String.format("%02d", cle) + siren;
    }

    /** Format seulement : prefixe d'un pays de l'Union europeenne autre que la France, puis 2 a 12 caracteres. */
    public static boolean tvaEtrangereValide(String tva) {
        if (tva == null) {
            return false;
        }
        Matcher m = TVA_ETRANGERE.matcher(tva);
        return m.matches() && PAYS_TVA_ETRANGERS.contains(m.group(1));
    }

    private static boolean luhn(String s) {
        int somme = 0;
        for (int i = 0; i < s.length(); i++) {
            int chiffre = s.charAt(s.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                chiffre *= 2;
                if (chiffre > 9) {
                    chiffre -= 9;
                }
            }
            somme += chiffre;
        }
        return somme % 10 == 0;
    }

    private static int sommeDesChiffres(String s) {
        int somme = 0;
        for (int i = 0; i < s.length(); i++) {
            somme += s.charAt(i) - '0';
        }
        return somme;
    }
}
