-- Connexion unique par Authelia, aiguillage : identifiant de client OpenID Connect de chaque serveur de licence
-- (destinataire attendu du jeton d'identite). Nullable : aucun effet sur les lignes existantes ; unique quand
-- renseigne (deux serveurs ne partagent jamais un identifiant). Idempotent.
ALTER TABLE licence_serveurs
    ADD COLUMN IF NOT EXISTS oidc_client_id VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS ux_licence_serveurs_oidc_client_id
    ON licence_serveurs (oidc_client_id) WHERE oidc_client_id IS NOT NULL;
