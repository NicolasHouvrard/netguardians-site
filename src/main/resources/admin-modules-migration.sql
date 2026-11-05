ALTER TABLE IF EXISTS admin_modules ADD COLUMN IF NOT EXISTS active       BOOLEAN      DEFAULT TRUE;
ALTER TABLE IF EXISTS admin_modules ADD COLUMN IF NOT EXISTS audience      VARCHAR(255) DEFAULT 'ESN';
ALTER TABLE IF EXISTS admin_modules ADD COLUMN IF NOT EXISTS description   TEXT;
ALTER TABLE IF EXISTS admin_modules ADD COLUMN IF NOT EXISTS subscription_code VARCHAR(255);

UPDATE admin_modules SET active   = TRUE  WHERE active   IS NULL;
UPDATE admin_modules SET audience = 'ESN' WHERE audience IS NULL;
