package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DockerControlTest {
    @Test
    void demarrerVersionAppelleDockerRunAvecLeBonPort() {
        var executeur = mock(CommandeExecuteur.class); // wrapper autour de ProcessBuilder, injecte
        when(executeur.executer(any())).thenReturn(new ResultatCommande(0, "abc123\n", ""));

        var control = new DockerControl(executeur);
        String id = control.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081);

        assertThat(id).isEqualTo("abc123");
        verify(executeur).executer(List.of("docker", "run", "--name", "site-8081",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8081:8080",
                "ghcr.io/owner/site:v1.2.3"));
    }

    @Test
    void demarrerVersionSupprimeLeConteneurDuPortAvantDeLancerLeNouveau() {
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(any())).thenReturn(new ResultatCommande(0, "abc123\n", ""));

        new DockerControl(executeur).demarrerVersion("ghcr.io/owner/site:v1.2.3", 8080);

        InOrder ordre = inOrder(executeur);
        ordre.verify(executeur).executer(List.of("docker", "rm", "-f", "site-8080"));
        ordre.verify(executeur).executer(List.of("docker", "run", "--name", "site-8080",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8080:8080",
                "ghcr.io/owner/site:v1.2.3"));
    }

    @Test
    void lEchecDeDockerRmEstIgnoreMaisDockerRunDoitReussir() {
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(List.of("docker", "ps", "--filter", "publish=8080", "-q")))
                .thenReturn(new ResultatCommande(0, "", ""));
        when(executeur.executer(List.of("docker", "rm", "-f", "site-8080")))
                .thenReturn(new ResultatCommande(1, "", "No such container: site-8080"));
        when(executeur.executer(List.of("docker", "run", "--name", "site-8080",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8080:8080",
                "ghcr.io/owner/site:v1.2.3")))
                .thenReturn(new ResultatCommande(0, "def456\n", ""));

        String id = new DockerControl(executeur).demarrerVersion("ghcr.io/owner/site:v1.2.3", 8080);

        assertThat(id).isEqualTo("def456");
    }

    @Test
    void optionsDeRunConfigureesSontInjecteesAvantLImage() {
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(any())).thenReturn(new ResultatCommande(0, "abc123\n", ""));

        var control = new DockerControl(executeur,
                "--env-file /etc/site/.env  --network site-network -v /data:/app/data");
        control.demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081);

        verify(executeur).executer(List.of("docker", "run", "--name", "site-8081",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8081:8080",
                "--env-file", "/etc/site/.env", "--network", "site-network",
                "-v", "/data:/app/data", "ghcr.io/owner/site:v1.2.3"));
    }

    @Test
    void modeReseauHoteFixeLeportParVariablesEtNePublieAucunPort() {
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(any())).thenReturn(new ResultatCommande(0, "abc123\n", ""));

        new DockerControl(executeur, "-e DATABASE_USERNAME=site", true)
                .demarrerVersion("ghcr.io/owner/site:v1.2.3", 18081);

        verify(executeur).executer(List.of("docker", "run", "--name", "site-18081",
                "--restart", "unless-stopped", "-d", "--network", "host",
                "-e", "SERVER_PORT=18081", "-e", "SERVER_ADDRESS=127.0.0.1",
                "-e", "DATABASE_USERNAME=site", "ghcr.io/owner/site:v1.2.3"));
    }

    @Test
    void optionsDeRunVidesNAjoutentAucunJeton() {
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(any())).thenReturn(new ResultatCommande(0, "abc123\n", ""));

        new DockerControl(executeur, "   ").demarrerVersion("ghcr.io/owner/site:v1.2.3", 8081);

        verify(executeur).executer(List.of("docker", "run", "--name", "site-8081",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8081:8080",
                "ghcr.io/owner/site:v1.2.3"));
    }

    @Test
    void conteneurNonNommeLiePortEstSupprimeParSonId() {
        var executeur = mock(CommandeExecuteur.class);
        // Conteneur lance par compose : pas de nom site-8080, mais lie au port 8080.
        when(executeur.executer(List.of("docker", "ps", "--filter", "publish=8080", "-q")))
                .thenReturn(new ResultatCommande(0, "compose123\n", ""));
        when(executeur.executer(List.of("docker", "rm", "-f", "compose123")))
                .thenReturn(new ResultatCommande(0, "compose123\n", ""));
        when(executeur.executer(List.of("docker", "run", "--name", "site-8080",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8080:8080",
                "ghcr.io/owner/site:v1.2.3")))
                .thenReturn(new ResultatCommande(0, "def456\n", ""));

        new DockerControl(executeur).demarrerVersion("ghcr.io/owner/site:v1.2.3", 8080);

        InOrder ordre = inOrder(executeur);
        ordre.verify(executeur).executer(List.of("docker", "ps", "--filter", "publish=8080", "-q"));
        ordre.verify(executeur).executer(List.of("docker", "rm", "-f", "compose123"));
        ordre.verify(executeur).executer(List.of("docker", "run", "--name", "site-8080",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8080:8080",
                "ghcr.io/owner/site:v1.2.3"));
    }

    @Test
    void psVideNAppelleAucunRmPourLePortEtLanceLeRun() {
        var executeur = mock(CommandeExecuteur.class);
        when(executeur.executer(List.of("docker", "ps", "--filter", "publish=8080", "-q")))
                .thenReturn(new ResultatCommande(0, "", ""));
        when(executeur.executer(List.of("docker", "run", "--name", "site-8080",
                "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8080:8080",
                "ghcr.io/owner/site:v1.2.3")))
                .thenReturn(new ResultatCommande(0, "def456\n", ""));

        String id = new DockerControl(executeur).demarrerVersion("ghcr.io/owner/site:v1.2.3", 8080);

        assertThat(id).isEqualTo("def456");
        ArgumentCaptor<List<String>> appels = ArgumentCaptor.forClass(List.class);
        verify(executeur, times(3)).executer(appels.capture());
        assertThat(appels.getAllValues()).containsExactly(
                List.of("docker", "ps", "--filter", "publish=8080", "-q"),
                List.of("docker", "rm", "-f", "site-8080"),
                List.of("docker", "run", "--name", "site-8080",
                        "--restart", "unless-stopped", "-d", "-p", "127.0.0.1:8080:8080",
                        "ghcr.io/owner/site:v1.2.3"));
    }
}
