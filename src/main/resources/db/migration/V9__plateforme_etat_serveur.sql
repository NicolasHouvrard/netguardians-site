-- Plateforme de plugins (SP2) : dernier état rapporté par chaque serveur pour chaque plugin, sur le Maître
-- (POST /api/licence/plugins/etat, lu par GET /api/plateforme/serveurs-etat ; voir EtatPluginServeur).
--
-- Une ligne par (serveur, plugin) : un nouveau rapport REMPLACE le précédent (upsert sur la clé primaire).
-- licence_serveur_id est l'id du LicenceServeur authentifié par sa clé API ; volontairement SANS clé étrangère
-- vers licence_serveurs (le module plateforme ne dépend pas du module licence ; une ligne orpheline reste lisible).
-- Noms de colonnes explicites (@Column(name=...) dans l'entité), schéma public comme les autres tables du cœur.

CREATE TABLE IF NOT EXISTS plateforme_etat_serveur (
    licence_serveur_id BIGINT                      NOT NULL,
    nom                VARCHAR(40)                 NOT NULL,
    version            VARCHAR(64)                 NOT NULL,
    statut             VARCHAR(16)                 NOT NULL,
    raisons            TEXT                        NOT NULL DEFAULT '',
    rapporte_le        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (licence_serveur_id, nom)
);
