-- Inscription d'une societe, etape 3 : liberation des inscriptions jamais verifiees.
--
-- Origine d'une ligne du registre. 'INSCRIPTION_CLIENT' = client inscrit seul sur le site : seul cas que la
-- liberation des inscriptions non verifiees peut supprimer. NULL = tout le reste (lignes reprises de l'existant,
-- partenaires approuves, lignes anterieures a cette migration) : jamais liberees automatiquement.

ALTER TABLE societes_identites ADD COLUMN IF NOT EXISTS origine VARCHAR(30);
