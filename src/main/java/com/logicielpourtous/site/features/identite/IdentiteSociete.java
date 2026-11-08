package com.logicielpourtous.site.features.identite;

/**
 * Identite normalisee d'une societe. {@code siren} vaut {@code null} pour une societe etrangere, qui n'est
 * identifiee que par sa TVA.
 */
public record IdentiteSociete(String siren, String tva, boolean etrangere) {

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
        return tva.startsWith("FR") ? francaise(siren, siret, tva) : etrangere(siren, siret, tva);
    }

    private static IdentiteSociete francaise(String siren, String siret, String tva) {
        if (siren == null && siret == null) {
            throw new IdentiteInvalideException("siren", "Le SIREN ou le SIRET est obligatoire pour une société française.");
        }
        if (siret != null) {
            if (!NumerosSociete.siretValide(siret)) {
                throw new IdentiteInvalideException("siret", "Le SIRET est invalide.");
            }
            String sirenDuSiret = NumerosSociete.sirenDe(siret);
            if (siren != null && !siren.equals(sirenDuSiret)) {
                throw new IdentiteInvalideException("siret", "Le SIRET ne correspond pas au SIREN.");
            }
            siren = sirenDuSiret;
        }
        if (!NumerosSociete.sirenValide(siren)) {
            throw new IdentiteInvalideException("siren", "Le SIREN est invalide.");
        }
        if (!tva.equals(NumerosSociete.tvaFrancaise(siren))) {
            throw new IdentiteInvalideException("tva", "Le numéro de TVA ne correspond pas au SIREN.");
        }
        return new IdentiteSociete(siren, tva, false);
    }

    private static IdentiteSociete etrangere(String siren, String siret, String tva) {
        if (siren != null || siret != null) {
            throw new IdentiteInvalideException("siren",
                    "Un SIREN ou un SIRET français est incompatible avec une TVA étrangère.");
        }
        if (!NumerosSociete.tvaEtrangereValide(tva)) {
            throw new IdentiteInvalideException("tva", "Le numéro de TVA européen est invalide.");
        }
        return new IdentiteSociete(null, tva, true);
    }
}
