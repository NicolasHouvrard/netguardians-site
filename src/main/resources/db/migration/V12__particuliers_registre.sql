-- Clients particuliers dans le registre des societes : une personne sans SIREN ni TVA peut etre cliente.
-- Un particulier n'a aucun numero ; toute autre societe doit garder une TVA.
-- Le script est idempotent (rejouable).

ALTER TABLE societes_identites ADD COLUMN IF NOT EXISTS particulier BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE societes_identites ALTER COLUMN tva DROP NOT NULL;

ALTER TABLE societes_identites DROP CONSTRAINT IF EXISTS ck_societes_identites_tva_ou_particulier;
ALTER TABLE societes_identites ADD CONSTRAINT ck_societes_identites_tva_ou_particulier
    CHECK (particulier OR tva IS NOT NULL);

ALTER TABLE societes_identites DROP CONSTRAINT IF EXISTS ck_societes_identites_particulier_sans_numero;
ALTER TABLE societes_identites ADD CONSTRAINT ck_societes_identites_particulier_sans_numero
    CHECK (NOT particulier OR (siren IS NULL AND tva IS NULL));
