-- ============================================================
-- DIAGNOSTIC — trouver tous les doublons
-- ============================================================

-- Pages avec is_home_page = true en double
SELECT id, slug, title, is_home_page
FROM pages
WHERE is_home_page = true
ORDER BY id;

-- Slugs en double dans pages (ancienne table)
SELECT slug, COUNT(*) AS nb
FROM pages
GROUP BY slug
HAVING COUNT(*) > 1;

-- Slugs en double dans public_pages (nouvelle table)
SELECT slug, COUNT(*) AS nb
FROM public_pages
GROUP BY slug
HAVING COUNT(*) > 1;

-- Emails en double dans users
SELECT email, COUNT(*) AS nb
FROM users
GROUP BY email
HAVING COUNT(*) > 1;


-- ============================================================
-- NETTOYAGE — garder uniquement la page d'accueil la plus récente
-- ============================================================

-- Décocher is_home_page sur tous sauf le dernier (id le plus grand)
UPDATE pages
SET is_home_page = false
WHERE is_home_page = true
  AND id NOT IN (
    SELECT MAX(id) FROM pages WHERE is_home_page = true
  );

-- Idem pour public_pages
UPDATE public_pages
SET home_page = false
WHERE home_page = true
  AND id NOT IN (
    SELECT MAX(id) FROM public_pages WHERE home_page = true
  );


-- ============================================================
-- CONTRAINTES UNIQUE — à ajouter si elles manquent en prod
-- (ddl-auto=update ne les crée PAS sur des tables existantes)
-- ============================================================

-- Vérifier si la contrainte existe déjà avant de l'ajouter
-- (sinon l'instruction ALTER TABLE échouera)

ALTER TABLE pages
    ADD CONSTRAINT uq_pages_slug UNIQUE (slug);

ALTER TABLE public_pages
    ADD CONSTRAINT uq_public_pages_slug UNIQUE (slug);

ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE (email);
