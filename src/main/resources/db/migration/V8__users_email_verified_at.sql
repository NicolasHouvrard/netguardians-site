-- Inscription d'une societe, etape 3 : date de verification de l'e-mail d'un compte.
--
-- « Inactif avec un jeton de verification » ne prouve pas qu'un compte n'a JAMAIS ete verifie : un compte
-- verifie puis desactive peut recevoir un nouveau jeton (renvoi de verification). La liberation des inscriptions
-- jamais verifiees exige donc email_verified_at IS NULL. Une fois renseignee, la date n'est jamais effacee.
--
-- Reprise : tout compte qui n'est pas PROUVABLEMENT non verifie (actif, ou sans jeton en attente) est considere
-- verifie ; seuls les comptes inactifs avec un jeton restent a NULL.

ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP(6) WITHOUT TIME ZONE;

UPDATE users
SET email_verified_at = COALESCE(created_at, now())
WHERE email_verified_at IS NULL
  AND (active OR email_verification_token IS NULL OR email_verification_token = '');
