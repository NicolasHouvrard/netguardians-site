package com.logicielpourtous.site.features.deploiement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.logicielpourtous.site.features.licence.EtatLicenceLocale;
import com.logicielpourtous.site.features.licence.EtatLicenceLocaleRepository;

/**
 * Unitaire (pas {@code @SpringBootTest}) : {@link AgentCleApiFileWriter} n'a besoin que d'un
 * {@link EtatLicenceLocaleRepository} (mocke) et d'un chemin de fichier — pas de contexte Spring.
 *
 * <p>Fix de securite (Task 3) : ce fichier REMPLACE l'ancienne route HTTP {@code
 * GET /internal/agent/cle-api} (supprimee — voir le rapport de Task 3, section "fix round").
 */
class AgentCleApiFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void ecritLaCleEnClairQuandElleExiste() throws Exception {
        EtatLicenceLocaleRepository repository = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-test-xyz-123");
        when(repository.findAll()).thenReturn(List.of(etat));

        Path fichier = tempDir.resolve("sous-dossier").resolve("cle-api-licence");
        new AgentCleApiFileWriter(repository, fichier.toString()).ecrireSiPresente();

        assertThat(Files.readString(fichier, StandardCharsets.UTF_8)).isEqualTo("cle-test-xyz-123");
    }

    @Test
    void neCreePasLeFichierTantQueLaCleEstAbsente() {
        EtatLicenceLocaleRepository repository = mock(EtatLicenceLocaleRepository.class);
        when(repository.findAll()).thenReturn(List.of());

        Path fichier = tempDir.resolve("cle-api-licence");
        new AgentCleApiFileWriter(repository, fichier.toString()).ecrireSiPresente();

        assertThat(Files.exists(fichier)).isFalse();
    }

    @Test
    void neCreePasLeFichierQuandLaCleEstVide() {
        EtatLicenceLocaleRepository repository = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("   ");
        when(repository.findAll()).thenReturn(List.of(etat));

        Path fichier = tempDir.resolve("cle-api-licence");
        new AgentCleApiFileWriter(repository, fichier.toString()).ecrireSiPresente();

        assertThat(Files.exists(fichier)).isFalse();
    }

    // Fix round 2, finding 2 : app.agent.cle-api-file-path n'a plus AUCUNE valeur par defaut
    // (un chemin devine resoudrait dans l'arbre source en dev) — une propriete absente/vide ne
    // doit jamais faire planter l'app (meme discipline que "pas encore enrole"), juste logguer une
    // erreur et ne RIEN ecrire, meme si une cle est par ailleurs disponible.
    @Test
    void neRienEcrireNiLeverQuandLeCheminEstVide() {
        EtatLicenceLocaleRepository repository = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-test-xyz-123");
        when(repository.findAll()).thenReturn(List.of(etat));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new AgentCleApiFileWriter(repository, "").ecrireSiPresente());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new AgentCleApiFileWriter(repository, null).ecrireSiPresente());
    }

    // Garde par assumeTrue (pas de @EnabledOnOs) : POSIX permissions dependent du systeme de
    // fichiers reel, pas seulement de l'OS (ex. NTFS sous WSL) — l'assertion de contenu ci-dessus
    // tourne elle TOUJOURS, y compris sous Windows ; seule celle-ci est conditionnelle.
    @Test
    void restreintLesPermissionsA0600QuandLeSystemeDeFichiersEstPosix() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "Systeme de fichiers sans attributs POSIX (ex. Windows/NTFS) : permissions non applicables ici.");

        EtatLicenceLocaleRepository repository = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-test-xyz-123");
        when(repository.findAll()).thenReturn(List.of(etat));

        Path fichier = tempDir.resolve("cle-api-licence");
        new AgentCleApiFileWriter(repository, fichier.toString()).ecrireSiPresente();

        Set<java.nio.file.attribute.PosixFilePermission> permissions = Files.getPosixFilePermissions(fichier);
        assertThat(PosixFilePermissions.toString(permissions)).isEqualTo("rw-------");
    }
}
