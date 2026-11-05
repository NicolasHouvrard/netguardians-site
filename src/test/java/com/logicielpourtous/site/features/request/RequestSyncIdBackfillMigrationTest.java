package com.logicielpourtous.site.features.request;

import org.springframework.context.annotation.Import;
import com.logicielpourtous.site.config.MultiTenantJpaConfiguration;
import com.logicielpourtous.site.features.commun.MultiTenantConnectionProviderImpl;
import com.logicielpourtous.site.features.commun.SchemaTenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit le finding de la revue finale : sur une base DEJA PEUPLEE, un
 * redeploiement de Syncable/Request ajoute sync_id comme colonne NULLABLE et
 * laisse les lignes existantes a NULL pour toujours (aucun chemin
 * applicatif ne peut plus les remplir : @PrePersist seulement, colonne
 * updatable = false).
 *
 * Simule une ligne "requests" preexistante avant ce fix (INSERT SQL brut
 * sans passer par JPA, donc sans declencher Syncable#assignSyncId), puis
 * rejoue reellement le script src/main/resources/sync-id-backfill-migration.sql
 * (tel qu'il sera execute au prochain demarrage de l'application via
 * spring.sql.init) et verifie que la ligne recoit un sync_id non-null et
 * unique.
 */
// Meme EntityManagerFactory que l'application reelle (voir la Javadoc de MultiTenantJpaConfiguration) : sans cela,
// la slice JPA utiliserait l'EMF auto-configure de Boot, avec une strategie de nommage
// differente, et ecrirait dans d'autres colonnes que l'application.
@DataJpaTest
@Import({ MultiTenantJpaConfiguration.class, MultiTenantConnectionProviderImpl.class, SchemaTenantResolver.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RequestSyncIdBackfillMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RequestRepository requestRepository;

    @Test
    void backfillMigrationGivesNonNullUniqueSyncIdToPreExistingNullRows() throws IOException {
        // 1. Simule une ligne "requests" preexistante avant ce fix : INSERT SQL
        //    brut (pas via le repository JPA), sync_id explicitement NULL.
        Long preExistingId = jdbcTemplate.queryForObject(
                "INSERT INTO requests (title, sync_id) VALUES ('ligne preexistante', NULL) RETURNING id",
                Long.class);

        // Sanity check : la ligne est bien NULL avant la migration, sinon le
        // test ne reproduirait pas le bug du finding.
        UUID syncIdBefore = jdbcTemplate.queryForObject(
                "SELECT sync_id FROM requests WHERE id = ?", UUID.class, preExistingId);
        assertThat(syncIdBefore).isNull();

        // Une ligne "normale" existe deja (via JPA, Syncable#assignSyncId) pour
        // verifier l'unicite du backfill par rapport a une valeur reelle.
        Long otherId = jdbcTemplate.queryForObject(
                "INSERT INTO requests (title, sync_id) VALUES ('autre ligne', gen_random_uuid()) RETURNING id",
                Long.class);
        UUID otherSyncId = jdbcTemplate.queryForObject(
                "SELECT sync_id FROM requests WHERE id = ?", UUID.class, otherId);
        assertThat(otherSyncId).isNotNull();

        // 2. Rejoue reellement le script de migration (celui execute par
        //    spring.sql.init au prochain demarrage de l'application).
        runMigrationScript("sync-id-backfill-migration.sql");

        // 3. La ligne pre-existante a maintenant un sync_id non-null, different
        //    de celui de l'autre ligne (unicite).
        UUID syncIdAfter = jdbcTemplate.queryForObject(
                "SELECT sync_id FROM requests WHERE id = ?", UUID.class, preExistingId);
        assertThat(syncIdAfter).isNotNull();
        assertThat(syncIdAfter).isNotEqualTo(otherSyncId);

        // 4. L'index unique existe bel et bien sur sync_id (exigence
        //    "unique globalement" du design doc parent) : une violation
        //    d'unicite doit etre rejetee par la base.
        UUID duplicate = syncIdAfter;
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE requests SET sync_id = ? WHERE id = ?", duplicate, otherId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Task 3b : une ligne "requests" preexistante a l'ajout de {@code @Version} (version NULL en
     * base) casse TOUTE ecriture JPA ulterieure de la ligne (constate empiriquement : NPE
     * "current is null" au flush d'une entite geree, PropertyValueException "uninitialized version
     * value 'null'" au merge d'une instance detachee). Le script de migration doit la backfiller a 0
     * et verrouiller la colonne (NOT NULL), apres quoi une mise a jour JPA normale fonctionne et
     * incremente la version.
     */
    @Test
    void backfillMigrationGivesVersionZeroToPreExistingNullRowsAndUpdatesThenWork() throws IOException {
        // 1. Etat d'une base deja peuplee juste apres le redeploiement : Hibernate (ddl-auto=update)
        //    a ajoute "version" en NULLABLE, sans valeur pour les lignes existantes. On le recree
        //    (DDL transactionnel PostgreSQL : annule au rollback du test).
        jdbcTemplate.execute("ALTER TABLE requests ALTER COLUMN version DROP NOT NULL");
        jdbcTemplate.execute("ALTER TABLE requests ALTER COLUMN version DROP DEFAULT");
        Long preExistingId = jdbcTemplate.queryForObject(
                "INSERT INTO requests (title, sync_id) VALUES ('ligne preexistante', gen_random_uuid()) RETURNING id",
                Long.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM requests WHERE id = ?", Long.class, preExistingId)).isNull();

        // 2. Rejoue reellement le script (spring.sql.init au prochain demarrage).
        runMigrationScript("sync-id-backfill-migration.sql");

        // 3. Version backfillee a 0, colonne NOT NULL (un INSERT SQL brut ne peut plus en creer).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM requests WHERE id = ?", Long.class, preExistingId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'requests' AND column_name = 'version'",
                String.class)).isEqualTo("NO");

        // 4. Une mise a jour JPA ordinaire de la ligne backfillee fonctionne et incremente la version.
        Request loaded = requestRepository.findById(preExistingId).orElseThrow();
        assertThat(loaded.getVersion()).isZero();
        loaded.setTitle("modifiee apres migration");
        requestRepository.saveAndFlush(loaded);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM requests WHERE id = ?", Long.class, preExistingId)).isEqualTo(1L);
    }

    /**
     * Fix round 2/3 du moteur de synchronisation : {@code comments.sync_id} (cle de synchronisation des
     * commentaires) est ajoute NULLABLE par Hibernate ; les commentaires existants doivent etre backfilles
     * avec une valeur unique, garantie par un index unique.
     */
    @Test
    void backfillMigrationGivesNonNullUniqueSyncIdToPreExistingComments() throws IOException {
        Long requestId = jdbcTemplate.queryForObject(
                "INSERT INTO requests (title, sync_id) VALUES ('demande', gen_random_uuid()) RETURNING id",
                Long.class);
        // Etat juste apres redeploiement : index pas encore cree, commentaires existants sans sync_id
        // (DDL transactionnel PostgreSQL : annule au rollback du test).
        jdbcTemplate.execute("DROP INDEX IF EXISTS ux_comments_sync_id");
        Long c1 = insertCommentWithoutSyncId(requestId, "ancien 1");
        Long c2 = insertCommentWithoutSyncId(requestId, "ancien 2");

        runMigrationScript("sync-id-backfill-migration.sql");

        UUID s1 = jdbcTemplate.queryForObject("SELECT sync_id FROM comments WHERE id = ?", UUID.class, c1);
        UUID s2 = jdbcTemplate.queryForObject("SELECT sync_id FROM comments WHERE id = ?", UUID.class, c2);
        assertThat(s1).isNotNull();
        assertThat(s2).isNotNull().isNotEqualTo(s1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE comments SET sync_id = ? WHERE id = ?", s1, c2))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Long insertCommentWithoutSyncId(Long requestId, String content) {
        Long id = jdbcTemplate.queryForObject(
                // content est un @Lob (colonne oid sous PostgreSQL) : large object cree en SQL brut.
                "INSERT INTO comments (content, createdat, ispublic, request_id, sync_id) "
                        + "VALUES (lo_from_bytea(0, convert_to(?, 'UTF8')), now(), true, ?, NULL) RETURNING id",
                Long.class, content, requestId);
        assertThat(jdbcTemplate.queryForObject("SELECT sync_id FROM comments WHERE id = ?", UUID.class, id)).isNull();
        return id;
    }

    private void runMigrationScript(String classpathFileName) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathFileName);
        String sql = Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);

        // Retire les lignes 100% commentaire (le fichier n'a pas de ";" dans
        // ses commentaires) avant de decouper en instructions SQL, sinon le
        // bloc de commentaire en tete de fichier fusionnerait avec la
        // premiere instruction reelle et la ferait sauter.
        String sqlWithoutComments = sql.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

        List<String> statements = List.of(sqlWithoutComments.split(";"));
        for (String statement : statements) {
            String trimmed = statement.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            jdbcTemplate.execute(trimmed);
        }
    }
}
