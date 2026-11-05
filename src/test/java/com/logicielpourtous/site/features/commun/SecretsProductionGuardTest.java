package com.logicielpourtous.site.features.commun;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsProductionGuardTest {

    private static final String CLE = "q8Zr1v0pLmN4xYc7WdE2uTbK9sHfJaG5oPiRz3VnXyUe6CtB1AwM8DhQkS4gLjF0";
    private static final String SALT = "9f3a1c7e5b2d4a60";
    private static final String JWT = "T7yUiO9pAsDfGhJkLzXcVbNm1Q2w3E4r5T6y7U8i9O0pAsDfGhJkLzXc";

    @Test
    void accepteDesSecretsRealistes() {
        assertThatCode(() -> SecretsProductionGuard.verifier(CLE, SALT, JWT)).doesNotThrowAnyException();
    }

    @Test
    void refuseLaCleDeChiffrementParDefautDuDepot() {
        assertThatThrownBy(() -> SecretsProductionGuard.verifier("changeme-dev-key-32chars!!", SALT, JWT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.crypto.key");
    }

    @Test
    void refuseLeSaltParDefautEtLeSaltVide() {
        assertThatThrownBy(() -> SecretsProductionGuard.verifier(CLE, "deadbeefdeadbeef", JWT))
                .hasMessageContaining("app.crypto.salt");
        assertThatThrownBy(() -> SecretsProductionGuard.verifier(CLE, "", JWT))
                .hasMessageContaining("app.crypto.salt");
    }

    @Test
    void refuseLesSecretsJwtParDefautDuDepotEtLesPlaceholdersDesExemples() {
        for (String jwt : new String[] {
                "dev-secret-key-must-be-at-least-32-bytes!!",
                "CHANGEME-min-32-bytes",
                "CHANGEME-votre-cle-jwt-longue-et-aleatoire-min-32-chars", "", null}) {
            assertThatThrownBy(() -> SecretsProductionGuard.verifier(CLE, SALT, jwt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.jwt.secret");
        }
    }

    @Test
    void lesMessagesNAffichentJamaisLesValeurs() {
        assertThatThrownBy(() -> SecretsProductionGuard.verifier("changeme-dev-key-32chars!!", SALT, JWT))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("changeme-dev-key-32chars!!");
                    assertThat(e.getMessage()).doesNotContain(SALT).doesNotContain(JWT);
                });
    }
}
