-- Lot B, sous-projet 1 : creation de partenaires par le maitre. Idempotent.

-- Droits donnes par defaut a un nouveau partenaire (modifiable par le maitre).
CREATE TABLE IF NOT EXISTS modele_droits_partenaire (
    droit VARCHAR(60) PRIMARY KEY
);

INSERT INTO modele_droits_partenaire (droit) VALUES ('clients.lire'), ('clients.creer'), ('clients.entrer')
ON CONFLICT DO NOTHING;

-- Journal des actions du maitre : volontairement SANS cles etrangeres (une trace doit survivre a tout).
CREATE TABLE IF NOT EXISTS journal_actions_maitre (
    id                    BIGSERIAL PRIMARY KEY,
    admin_user_id         BIGINT NOT NULL,
    action                VARCHAR(40) NOT NULL,
    partenaire_societe_id BIGINT,
    objet_societe_id      BIGINT,
    cree_a                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_journal_actions_maitre_admin ON journal_actions_maitre (admin_user_id, cree_a);
CREATE INDEX IF NOT EXISTS ix_journal_actions_maitre_partenaire ON journal_actions_maitre (partenaire_societe_id, cree_a);

-- Etat d'un partenaire (acces limite ou bloque).
CREATE TABLE IF NOT EXISTS partenaires_etat (
    societe_id       BIGINT PRIMARY KEY REFERENCES societes_identites (id) ON DELETE CASCADE,
    etat             VARCHAR(20) NOT NULL CHECK (etat IN ('ACTIF', 'LIMITE', 'BLOQUE')),
    debut_validation TIMESTAMPTZ NOT NULL
);
