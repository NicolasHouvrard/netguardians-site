package com.logicielpourtous.site.features.identite;

/**
 * Identite normalisee d'une societe. {@code siren} vaut {@code null} pour une societe etrangere, qui n'est
 * identifiee que par sa TVA.
 *
 * <p>Le constructeur verifie TOUS les invariants (numeros bien formes, TVA normalisee et coherente avec le
 * SIREN) : une identite ne peut pas exister sans etre valide, y compris si elle est construite directement
 * plutot que par {@link #depuis}. Sans cela, une valeur non normalisee passerait les index uniques de la base
 * (par exemple une TVA en minuscules) et creerait un doublon.
 */
public record IdentiteSociete(String siren, String tva, boolean etrangere) {

    public IdentiteSociete {
        if (tva == null) {
            throw new IdentiteInvalideException("tva", "Le numéro de TVA est obligatoire.");
        }
        if (!tva.equals(NumerosSociete.tva(tva))) {
            throw new IdentiteInvalideException("tva",
                    "Le numéro de TVA doit être normalisé (majuscules, sans espace ni séparateur).");
        }
        if (etrangere) {
            if (siren != null) {
                throw new IdentiteInvalideException("siren",
                        "Un SIREN ou un SIRET français est incompatible avec une TVA étrangère.");
            }
            if (!NumerosSociete.tvaEtrangereValide(tva)) {
                throw new IdentiteInvalideException("tva", "Le numéro de TVA européen est invalide.");
            }
        } else {
            if (siren == null) {
                throw new IdentiteInvalideException("siren",
                        "Le SIREN ou le SIRET est obligatoire pour une société française.");
            }
            if (!NumerosSociete.sirenValide(siren)) {
                throw new IdentiteInvalideException("siren", "Le SIREN est invalide.");
            }
            if (!tva.equals(NumerosSociete.tvaFrancaise(siren))) {
                throw new IdentiteInvalideException("tva", "Le numéro de TVA ne correspond pas au SIREN.");
            }
        }
    }

    /**
     * Controle et normalise les numeros saisis.
     *
     * @throws IdentiteInvalideException si un numero est invalide ou incoherent avec les autres
     */
    public static IdentiteSociete depuis(String sirenBrut, String siretBrut, String tvaBrute) {
        String siren = NumerosSociete.chiffres(sirenBrut);
        String siret = NumerosSociete.chiffres(siretBrut);
        String tva = NumerosSociete.tva(tvaBrute);

        if (tva == null) {
            throw new IdentiteInvalideException("tva", "Le numéro de TVA est obligatoire.");
        }
        boolean etrangere = !tva.startsWith("FR");
        if (etrangere && (siren != null || siret != null)) {
            throw new IdentiteInvalideException("siren",
                    "Un SIREN ou un SIRET français est incompatible avec une TVA étrangère.");
        }
        if (!etrangere && siret != null) {
            if (!NumerosSociete.siretValide(siret)) {
                throw new IdentiteInvalideException("siret", "Le SIRET est invalide.");
            }
            String sirenDuSiret = NumerosSociete.sirenDe(siret);
            if (siren != null && !siren.equals(sirenDuSiret)) {
                throw new IdentiteInvalideException("siret", "Le SIRET ne correspond pas au SIREN.");
            }
            siren = sirenDuSiret;
        }
        return new IdentiteSociete(siren, tva, etrangere);
    }
}
