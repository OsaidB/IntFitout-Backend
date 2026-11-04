-- MANUAL. After 001-007. Never edit or rerun earlier scripts.
-- PREPARED FOR REVIEW - NOT APPLIED.
--
-- Closeout usability batch: permanent measurement numbers and the optional floor field.
--
-- WHAT THIS ADDS
--   closeout_drafts.next_measurement_seq   the allocation high-water mark per closeout
--   closeout_final_row.measurement_seq     the number a finalized measurement carried
--   closeout_final_row.floor               the floor a finalized measurement was taken on
--
-- Three additive columns. No existing row is rewritten, no total is recalculated, and no
-- formula or rounding rule changes.
--
-- WHY THE HIGH-WATER MARK IS A COLUMN AND NOT A JSON FIELD
--   A closeout draft is a single JSON blob supplied by the client. The app build currently
--   released sends a valid revision but round-trips that blob while dropping fields it does
--   not recognise. If the mark lived inside the blob, that client would reset it to nothing
--   and the next new measurement would reuse a number somebody has already written down.
--   In a column, only the server can move it, and it only ever moves upwards.
--
--   The server also refuses a save that arrives with measurement numbers missing from rows
--   that are stored WITH them - the signature of exactly that older client - with
--   428 DRAFT_METADATA_REQUIRED. Revision checking alone cannot catch this, because the
--   revision such a client sends is genuinely current.
--
-- WHY ddl-auto IS NOT ENOUGH
--   spring.jpa.hibernate.ddl-auto=update would add next_measurement_seq as NULL on every
--   existing draft. The entity treats null as 1, so nothing would break immediately, but a
--   NOT NULL column with a default is the shape the mapping declares and the one that keeps
--   the invariant - "the mark exists and only rises" - true in the database rather than only
--   in application code. The two final_row columns are nullable by design and ddl-auto would
--   add them correctly; they are included here so one script describes the whole change.
--
-- EXISTING DATA
--   Existing drafts start with next_measurement_seq = 1. Their measurements have no numbers
--   yet; the server assigns them on the first save that carries them, in the order they
--   appear in the draft - type order, then row order. That is deterministic, so the same
--   draft always yields the same assignment, but it is a reconstruction: the original entry
--   order of measurements taken before this feature cannot be proven and is not claimed.
--
--   Existing finalized rows keep measurement_seq and floor NULL. They are not backfilled.
--   A finalized record is the historical record of what was agreed; it is not rewritten.
--
-- ROLLOUT ORDER
--   1. Apply this script.
--   2. Deploy the backend.
--   3. Release the app build that sends and displays measurement numbers.
--   Between 2 and 3, an older build that strips the metadata is refused with 428 and keeps
--   its work locally, exactly as it is for a missing revision. Keep the window short.
--
-- ROLLBACK
--   Dropping all three columns is safe once the backend that maps them is no longer running.
--   Numbers already shown to users would be lost and later reissued from 1, so prefer rolling
--   the application back and leaving the columns in place.


-- 0) PRECONDITION - expect 0 for each. Skip any that returns 1.
--
--    SELECT COUNT(*) FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'closeout_drafts'
--       AND COLUMN_NAME = 'next_measurement_seq';
--
--    SELECT COUNT(*) FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'closeout_final_row'
--       AND COLUMN_NAME IN ('measurement_seq','floor');


-- 1) APPLY

ALTER TABLE closeout_drafts
    ADD COLUMN next_measurement_seq BIGINT NOT NULL DEFAULT 1;

ALTER TABLE closeout_final_row
    ADD COLUMN measurement_seq BIGINT NULL,
    ADD COLUMN floor VARCHAR(80) NULL;


-- 2) VERIFY
--
--    SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
--      FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'closeout_drafts'
--       AND COLUMN_NAME = 'next_measurement_seq';
--    -- expect: bigint / NO / 1
--
--    SELECT COUNT(*) AS drafts, COUNT(next_measurement_seq) AS with_mark FROM closeout_drafts;
--    -- the two numbers must match.
--
--    SELECT COUNT(*) AS finalized_rows,
--           COUNT(measurement_seq) AS with_number
--      FROM closeout_final_row;
--    -- with_number is expected to be 0 immediately after this script: historical records
--    -- are not backfilled.
