-- Inscription d'une societe, etape 3 : inscription autonome.
--
-- 1) Demandes « societe connue » : quand quelqu'un tente d'inscrire une societe deja presente au registre,
--    on ne lui dit pas laquelle ; on garde une trace pour qu'un responsable la traite.
-- 2) Numeros d'identification (SIREN, TVA) saisis sur la demande d'ESN.

CREATE TABLE IF NOT EXISTS demandes_societe_connue (
    id                  BIGSERIAL PRIMARY KEY,
    societe_identite_id BIGINT NOT NULL REFERENCES societes_identites (id),
    email_demandeur     VARCHAR(255) NOT NULL,
    nom_demandeur       VARCHAR(255),
    cree_a              TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    traitee             BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_demandes_societe_connue_societe ON demandes_societe_connue (societe_identite_id);

ALTER TABLE subscription_requests ADD COLUMN IF NOT EXISTS siren VARCHAR(9);
ALTER TABLE subscription_requests ADD COLUMN IF NOT EXISTS vat_number VARCHAR(20);
