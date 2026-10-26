-- MANUAL ONCE ONLY, after 001-004 are already applied. Never edit or rerun 001-004.
-- Apply before deploying the matching backend. Hibernate does NOT manage this table.
--
-- Optional coworker assignment during report approval. Coworker worker IDs already exist in
-- whatsapp_report_coworkers.worker_id since 001, so no new identity or relationship is stored here.
-- This adds only immutable evidence of which other people an approval affected.
--
-- No worker answer, assignment, overtime, payroll row or review status is rewritten or backfilled.
-- Existing reviews keep NULL, which reads as "this approval affected only the reporting worker".
ALTER TABLE whatsapp_report_reviews ADD COLUMN coworkers_json JSON NULL;
