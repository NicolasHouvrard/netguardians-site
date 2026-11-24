-- Cadre de gestion des clients, lot A : journal des entrees du partenaire dans l'espace d'un client.
-- Journal d'audit : volontairement SANS cles etrangeres (une trace doit survivre a tout et s'ecrit dans sa propre
-- transaction, avant que le reste soit visible). Idempotent.
CREATE TABLE IF NOT EXISTS journal_acces_partenaire (
    id                 BIGSERIAL PRIMARY KEY,
    partenaire_user_id BIGINT NOT NULL,
    client_societe_id  BIGINT NOT NULL,
    action             VARCHAR(40) NOT NULL,
    cree_a             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_journal_acces_partenaire_user ON journal_acces_partenaire (partenaire_user_id, cree_a);
CREATE INDEX IF NOT EXISTS ix_journal_acces_partenaire_client ON journal_acces_partenaire (client_societe_id, cree_a);
