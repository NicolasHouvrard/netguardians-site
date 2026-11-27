package com.logicielpourtous.site.features.commun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChiffrementSecretsTest {

    private static final String SEL = "deadbeefdeadbeef";
    private static final String CLAIR = "secret-de-test-non-reel-0123456789";

    private final ChiffrementSecrets chiffrement = new ChiffrementSecrets("cle-de-test-A-0123456789", SEL);

    @Test
    void aller_retour() {
        assertThat(chiffrement.dechiffrer(chiffrement.chiffrer(CLAIR))).isEqualTo(CLAIR);
    }

    @Test
    void deux_chiffrements_du_meme_secret_donnent_des_textes_differents() {
        String a = chiffrement.chiffrer(CLAIR);
        String b = chiffrement.chiffrer(CLAIR);
        assertThat(a).isNotEqualTo(b).doesNotContain(CLAIR);
    }

    @Test
    void un_texte_chiffre_modifie_est_refuse() {
        String c = chiffrement.chiffrer(CLAIR);
        char dernier = c.charAt(c.length() - 1);
        String modifie = c.substring(0, c.length() - 1) + (dernier == '0' ? '1' : '0');

        assertThatThrownBy(() -> chiffrement.dechiffrer(modifie)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(CLAIR);
        assertThatThrownBy(() -> chiffrement.dechiffrer("pas-un-texte-chiffre"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void une_cle_differente_est_refusee() {
        String c = chiffrement.chiffrer(CLAIR);
        ChiffrementSecrets autre = new ChiffrementSecrets("cle-de-test-B-0123456789", SEL);

        assertThatThrownBy(() -> autre.dechiffrer(c)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(CLAIR);
    }
}
