package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasculeNginxTest {
    @Test
    void ecritLeBonPortEtRechargeNginx(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        var executeur = mock(CommandeExecuteur.class);

        var bascule = new BasculeNginx(fichierConfig, executeur);
        bascule.basculerVers(8081);

        assertThat(Files.readString(fichierConfig)).contains("server 127.0.0.1:8081;");
        verify(executeur).executer(List.of("nginx", "-s", "reload"));
    }

    @Test
    void reloadEchoueRestaureLAncienPortEtRelanceLException(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        var executeur = mock(CommandeExecuteur.class);
        var bascule = new BasculeNginx(fichierConfig, executeur);
        bascule.basculerVers(8080);

        when(executeur.executer(any())).thenReturn(new ResultatCommande(1, "", "reload refuse"));

        assertThatThrownBy(() -> bascule.basculerVers(8081))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nginx -s reload a echoue");
        assertThat(Files.readString(fichierConfig)).contains("server 127.0.0.1:8080;")
                .doesNotContain("8081");
    }

    @Test
    void reloadQuiLeveUneExceptionRestaureLAncienPort(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        var executeur = mock(CommandeExecuteur.class);
        var bascule = new BasculeNginx(fichierConfig, executeur);
        bascule.basculerVers(8080);

        when(executeur.executer(any())).thenThrow(new IllegalStateException("nginx introuvable"));

        assertThatThrownBy(() -> bascule.basculerVers(8081))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nginx introuvable");
        assertThat(Files.readString(fichierConfig)).contains("server 127.0.0.1:8080;")
                .doesNotContain("8081");
    }

    @Test
    void reloadEchoueAuPremierAppelSupprimeLeFichier(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(any())).thenReturn(new ResultatCommande(1, "", "reload refuse"));

        assertThatThrownBy(() -> new BasculeNginx(fichierConfig, executeur).basculerVers(8081))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fichierConfig).doesNotExist();
    }

    @Test
    void portActifActuelRenvoie8080SiLaConfigNExistePasEncore(@TempDir Path tmp) {
        var bascule = new BasculeNginx(tmp.resolve("upstream.conf"), mock(CommandeExecuteur.class));

        assertThat(bascule.portActifActuel()).isEqualTo(8080);
    }

    @Test
    void portActifActuelLitLePortDansLaConfig(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        var bascule = new BasculeNginx(fichierConfig, mock(CommandeExecuteur.class));
        bascule.basculerVers(8081);

        assertThat(bascule.portActifActuel()).isEqualTo(8081);
    }

    @Test
    void portActifActuelLeveSiLaConfigEstCorrompue(@TempDir Path tmp) throws IOException {
        Path fichierConfig = tmp.resolve("upstream.conf");
        Files.writeString(fichierConfig, "contenu modifie a la main\n");
        var bascule = new BasculeNginx(fichierConfig, mock(CommandeExecuteur.class));

        assertThatThrownBy(bascule::portActifActuel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server 127.0.0.1:<port>;");
    }
}
