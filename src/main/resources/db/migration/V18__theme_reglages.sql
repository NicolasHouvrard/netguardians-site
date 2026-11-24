-- Socle du front : reglages de theme modifiables par l'administrateur, et leur journal.
-- Une ligne de theme_reglages = une valeur qui remplace la valeur par defaut livree avec le logiciel (pas de ligne = valeur heritee).
-- Journal d'audit : volontairement SANS cles etrangeres. Idempotent.
CREATE TABLE IF NOT EXISTS theme_reglages (
    cle         VARCHAR(60) PRIMARY KEY,
    valeur      VARCHAR(40) NOT NULL,
    modifie_par BIGINT,
    modifie_a   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS theme_journal (
    id              BIGSERIAL PRIMARY KEY,
    cle             VARCHAR(60) NOT NULL,
    ancienne_valeur VARCHAR(40),
    nouvelle_valeur VARCHAR(40),
    modifie_par     BIGINT,
    cree_a          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_theme_journal_cle ON theme_journal (cle, cree_a);
