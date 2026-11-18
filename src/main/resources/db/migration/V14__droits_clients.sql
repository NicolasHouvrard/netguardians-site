-- Cadre de gestion des clients, lot A : droits a trois couches. Idempotent.
--  1. plafonds_client : droits que le partenaire referent autorise pour la societe cliente (une ligne par droit) ;
--  2. reglages_manager : droits que le manager du client accorde a un role dans sa societe (une ligne par droit) ;
--  3. appartenances.societe_id : rattache une appartenance a une societe du registre (nullable : l'existant
--     reste a NULL ; tenant_id reste obligatoire).

CREATE TABLE IF NOT EXISTS plafonds_client (
    client_societe_id BIGINT NOT NULL REFERENCES societes_identites (id),
    droit             VARCHAR(100) NOT NULL,
    PRIMARY KEY (client_societe_id, droit)
);

CREATE TABLE IF NOT EXISTS reglages_manager (
    client_societe_id BIGINT NOT NULL REFERENCES societes_identites (id),
    role_nom          VARCHAR(100) NOT NULL,
    droit             VARCHAR(100) NOT NULL,
    PRIMARY KEY (client_societe_id, role_nom, droit)
);

ALTER TABLE appartenances ADD COLUMN IF NOT EXISTS societe_id BIGINT REFERENCES societes_identites (id);

CREATE INDEX IF NOT EXISTS ix_appartenances_user_societe ON appartenances (user_id, societe_id);
