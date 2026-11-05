-- Sur une base DEJA PEUPLEE (NAS maitre), Hibernate (ddl-auto=update) ajoute
-- la colonne sync_id comme NULLABLE : les lignes "requests" existantes
-- recoivent NULL et aucun chemin applicatif (Syncable n'assigne qu'au
-- @PrePersist, colonne updatable=false) ne peut plus jamais les remplir.
-- Ce script backfille ces lignes puis garantit l'unicite exigee par la cle
-- de synchronisation.
--
-- Comme admin-modules-migration.sql, ce script s'execute (spring.sql.init)
-- AVANT que Hibernate ne cree les tables : sur une base neuve, "requests"
-- n'existe pas encore -> IF EXISTS / IF NOT EXISTS partout pour ne rien
-- casser dans ce cas (voir -Dspring.jpa.defer-datasource-initialization=true).
ALTER TABLE IF EXISTS requests ADD COLUMN IF NOT EXISTS sync_id UUID;
UPDATE requests SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_requests_sync_id ON requests(sync_id);

-- Verrouillage optimiste (@Version sur Syncable, Task 3b du moteur de
-- synchronisation). Hibernate (ddl-auto=update) ajoute "version" comme colonne
-- NULLABLE : les lignes existantes recoivent NULL, et une version NULL casse
-- TOUTE ecriture ulterieure de la ligne (verifie empiriquement : NPE
-- "current is null" au flush d'une entite geree, PropertyValueException
-- "uninitialized version value 'null'" au merge d'une instance detachee).
-- Backfill a 0 (valeur initiale d'Hibernate a la creation), puis DEFAULT 0 /
-- NOT NULL pour qu'un INSERT SQL brut ne puisse plus reintroduire une ligne
-- sans version. Hibernate fournit toujours la version a l'INSERT : le DEFAULT
-- ne change rien pour l'application.
ALTER TABLE IF EXISTS requests ADD COLUMN IF NOT EXISTS version BIGINT;
UPDATE requests SET version = 0 WHERE version IS NULL;
ALTER TABLE IF EXISTS requests ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE IF EXISTS requests ALTER COLUMN version SET NOT NULL;

-- Cle de synchronisation des commentaires (fix round 2 du moteur de synchronisation,
-- reception des commentaires publics synchronises). Meme raisonnement que
-- requests.sync_id : colonne ajoutee NULLABLE par Hibernate, jamais remplie par
-- l'application pour les lignes existantes (generee au @PrePersist seulement).
ALTER TABLE IF EXISTS comments ADD COLUMN IF NOT EXISTS sync_id UUID;
UPDATE comments SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_comments_sync_id ON comments(sync_id);

-- Statut terminal ECHEC_DEFINITIF de l'outbox (fix round 4 du moteur de
-- synchronisation : plafond de tentatives). Hibernate genere une contrainte CHECK
-- listant les valeurs de l'enum a la CREATION de la table, mais ddl-auto=update ne
-- la met JAMAIS a jour : sur une base existante, ecrire ECHEC_DEFINITIF viole
-- sync_outbox_entries_statut_check (verifie empiriquement), l'entree n'est jamais
-- marquee et serait repoussee a chaque cycle sans backoff. Contrainte recreee avec
-- toutes les valeurs actuelles (idempotent ; sans effet si la table n'existe pas).
ALTER TABLE IF EXISTS sync_outbox_entries DROP CONSTRAINT IF EXISTS sync_outbox_entries_statut_check, ADD CONSTRAINT sync_outbox_entries_statut_check CHECK (statut IN ('EN_ATTENTE', 'ENVOYE', 'ECHEC', 'ECHEC_DEFINITIF'));
