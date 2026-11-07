-- Connexion deleguee au Maitre, etape 1 : modele de rattachement.
--
-- tenants : serveur d'accueil (id d'un licence_serveurs ; NULL = rattache au Maitre lui-meme) et
-- tenant partenaire (pour un tenant client). Noms de colonnes explicites avec soulignes, comme les
-- autres colonnes de cette table (schema_name, is_esn...).
-- licence_serveurs : URL publique HTTPS du serveur (pour rediriger le navigateur d'un utilisateur vers
-- son serveur). Nom IMPLICITE en minuscules sans souligne, comme les autres colonnes de cette table
-- (voir V2__ordres_deploiement.sql).
-- parametres_federation : ligne unique (id = 1) portant le serveur par defaut des nouveaux tenants.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS serveur_licence_id BIGINT,
    ADD COLUMN IF NOT EXISTS tenant_partenaire_id BIGINT;

ALTER TABLE licence_serveurs
    ADD COLUMN IF NOT EXISTS urlpublique VARCHAR(255);

CREATE TABLE IF NOT EXISTS parametres_federation (
    id                BIGINT PRIMARY KEY,
    serveur_defaut_id BIGINT
);

INSERT INTO parametres_federation (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
