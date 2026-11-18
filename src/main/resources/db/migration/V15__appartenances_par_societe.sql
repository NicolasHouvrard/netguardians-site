-- Lot A, tache 6 : une personne peut appartenir a plusieurs societes clientes d'un meme tenant.
-- L'unicite d'une appartenance (V5) ne regardait que (user, tenant, fiche) : on y ajoute la societe du registre.
-- Les lignes existantes ont societe_id nul (COALESCE -> 0) : l'unicite d'avant est donc inchangee pour elles.

DROP INDEX IF EXISTS uq_appartenances_user_tenant_fiche;

CREATE UNIQUE INDEX IF NOT EXISTS uq_appartenances_user_tenant_fiche_societe
    ON appartenances (user_id, tenant_id, (COALESCE(customer_id, 0)), (COALESCE(societe_id, 0)));

-- Role manager d'une societe cliente (normalement amorce par DataInitializer ; ici pour les bases neuves).
INSERT INTO roles (name, label, type)
VALUES ('ROLE_GESTIONNAIRE', 'Gestionnaire', 'CLIENT')
ON CONFLICT (name) DO NOTHING;
