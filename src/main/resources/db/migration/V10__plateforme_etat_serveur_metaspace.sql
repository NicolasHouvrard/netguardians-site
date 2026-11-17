-- Plateforme de plugins (SP2) : mesure du Metaspace que chaque serveur joint à son rapport d'état (spec §7 : le
-- Maître voit quels serveurs PARTNER/CLIENT recommandent un redémarrage). Colonnes NULLABLES : un serveur plus ancien,
-- ou une mesure rapportée invalide (ignorée par le Maître), laisse NULL. Valeurs rapportées par le serveur, donc
-- non fiables : bornées et typées avant écriture (DistributionController).

ALTER TABLE plateforme_etat_serveur ADD COLUMN IF NOT EXISTS metaspace_mo BIGINT;
ALTER TABLE plateforme_etat_serveur ADD COLUMN IF NOT EXISTS seuil_mo BIGINT;
ALTER TABLE plateforme_etat_serveur ADD COLUMN IF NOT EXISTS redemarrage_recommande BOOLEAN;
