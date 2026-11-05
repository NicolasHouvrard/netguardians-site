-- ============================================================
-- Migration : passage au rapprochement manuel
-- Les transactions Qonto/Stripe ne créent plus d'écritures auto.
-- Ce script détache les liens existants et nettoie les écritures
-- auto-créées à l'import (références QTO/BQ/STR) qui ne sont
-- pas liées à des factures (FAC-).
-- ============================================================

-- 1. Vérification préalable : ce qui sera affecté
-- (executer d'abord pour voir l'impact, sans modifier)

SELECT 'QONTO' AS source, qt.id, qt.label, qt.amount, qt.settled_at,
       qt.reconciled, ae.reference, ae.entry_date
FROM qonto_transactions qt
JOIN accounting_entries ae ON ae.id = qt.accounting_entry_id
WHERE qt.reconciled = false
ORDER BY qt.settled_at DESC;

SELECT 'STRIPE' AS source, st.id, st.description, st.amount, st.event_date,
       st.reconciled, ae.reference, ae.entry_date
FROM stripe_transactions st
JOIN accounting_entries ae ON ae.id = st.accounting_entry_id
WHERE st.reconciled = false
ORDER BY st.event_date DESC;

-- ============================================================
-- 2. Étape 1 : Mémoriser les entry_id à supprimer
--    (seulement les non-rapprochées, écritures auto d'import)
-- ============================================================

CREATE TEMP TABLE entries_to_delete AS
SELECT ae.id
FROM accounting_entries ae
WHERE ae.reference NOT LIKE 'FAC-%'
  AND (
      EXISTS (SELECT 1 FROM qonto_transactions qt
              WHERE qt.accounting_entry_id = ae.id AND qt.reconciled = false)
   OR EXISTS (SELECT 1 FROM stripe_transactions st
              WHERE st.accounting_entry_id = ae.id AND st.reconciled = false)
  );

-- Vérifier ce qui sera supprimé :
SELECT ae.id, ae.reference, ae.entry_date, ae.description
FROM accounting_entries ae
WHERE ae.id IN (SELECT id FROM entries_to_delete);

-- ============================================================
-- 3. Étape 2 : Détacher les transactions non rapprochées
-- ============================================================

UPDATE qonto_transactions
SET accounting_entry_id = NULL
WHERE reconciled = false
  AND accounting_entry_id IS NOT NULL;

UPDATE stripe_transactions
SET accounting_entry_id = NULL
WHERE reconciled = false
  AND accounting_entry_id IS NOT NULL;

-- ============================================================
-- 4. Étape 3 : Supprimer les lignes des écritures orphelines
-- ============================================================

DELETE FROM accounting_entry_lines
WHERE entry_id IN (SELECT id FROM entries_to_delete);

-- ============================================================
-- 5. Étape 4 : Supprimer les écritures orphelines
-- ============================================================

DELETE FROM accounting_entries
WHERE id IN (SELECT id FROM entries_to_delete);

-- ============================================================
-- Résultat attendu :
-- - Les transactions rapprochées (reconciled = true) sont intactes
-- - Les transactions non rapprochées n'ont plus d'écriture liée
-- - Les écritures auto-créées à l'import (QTO/BQ/STR) sont supprimées
-- - Les écritures de facture (FAC-) ne sont pas touchées
-- ============================================================

DROP TABLE IF EXISTS entries_to_delete;
