package com.logicielpourtous.site.features.sso;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.features.sso.EmpreinteAuthelia.ClientGenere;

/** Secret client et empreinte PBKDF2-SHA512 au format d'Authelia. Aucun secret reel. */
class EmpreinteAutheliaTest {

    private static final Pattern FORMAT =
            Pattern.compile("^\\$pbkdf2-sha512\\$310000\\$([A-Za-z0-9./]+)\\$([A-Za-z0-9./]+)$");

    private static byte[] decoder(String ab64) {
        return Base64.getDecoder().decode(ab64.replace('.', '+'));
    }

    @Test
    void l_empreinte_a_le_format_pbkdf2_sha512_310000_sel_hash() {
        ClientGenere c = EmpreinteAuthelia.generer();

        Matcher m = FORMAT.matcher(c.empreinte());
        assertThat(m.matches()).as("format de l'empreinte").isTrue();
        assertThat(c.empreinte()).doesNotContain("=").doesNotContain("+");
        assertThat(decoder(m.group(1))).hasSize(16);
        assertThat(decoder(m.group(2))).hasSize(64);
        assertThat(c.secretClair()).hasSize(72).matches("[A-Za-z0-9\\-._~]{72}");
    }

    @Test
    void l_empreinte_se_verifie_avec_le_secret_par_l_implementation_standard() throws Exception {
        ClientGenere c = EmpreinteAuthelia.generer();
        Matcher m = FORMAT.matcher(c.empreinte());
        assertThat(m.matches()).isTrue();

        KeySpec spec = new PBEKeySpec(c.secretClair().toCharArray(), decoder(m.group(1)), 310000, 512);
        byte[] attendu = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();

        assertThat(attendu).isEqualTo(decoder(m.group(2)));
        assertThat(EmpreinteAuthelia.verifie(c.secretClair(), c.empreinte())).isTrue();
        assertThat(EmpreinteAuthelia.verifie(c.secretClair() + "x", c.empreinte())).isFalse();
    }

    @Test
    void deux_generations_donnent_des_secrets_et_des_sels_differents() {
        ClientGenere a = EmpreinteAuthelia.generer();
        ClientGenere b = EmpreinteAuthelia.generer();

        assertThat(a.secretClair()).isNotEqualTo(b.secretClair());
        assertThat(a.empreinte()).isNotEqualTo(b.empreinte());
        Matcher ma = FORMAT.matcher(a.empreinte());
        Matcher mb = FORMAT.matcher(b.empreinte());
        assertThat(ma.matches() && mb.matches()).isTrue();
        assertThat(ma.group(1)).isNotEqualTo(mb.group(1));
    }

    @Test
    void le_toString_ne_revele_pas_le_secret() {
        ClientGenere c = EmpreinteAuthelia.generer();
        assertThat(c.toString()).doesNotContain(c.secretClair());
    }
}
