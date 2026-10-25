-- MANUAL ONCE ONLY, after the already-applied 001. Tested using disposable MySQL 9.7.2.
-- Stop/pause reporting and all application instances before DDL; MySQL DDL commits implicitly.
-- Back up and inspect actual schema first. Never run this file at application startup.
ALTER TABLE master_worksites ADD COLUMN nickname VARCHAR(80) NULL;

-- Keep legacy master IDs and issued payloads. Each entry now has its own per-request identity.
-- Seed existing identities from their old master IDs; current_site and old coworker references
-- therefore continue to identify the same entry, without rewriting inbox/outbox history.
ALTER TABLE whatsapp_report_sites ADD COLUMN entry_id BIGINT NULL;
UPDATE whatsapp_report_sites SET entry_id=site_id;
ALTER TABLE whatsapp_report_coworkers DROP FOREIGN KEY fk_report_coworker_site;
ALTER TABLE whatsapp_report_sites
    DROP PRIMARY KEY,
    MODIFY entry_id BIGINT NOT NULL,
    MODIFY site_id BIGINT NULL,
    ADD PRIMARY KEY (request_id,entry_id),
    ADD CONSTRAINT uq_report_known_site UNIQUE (request_id,site_id),
    ADD COLUMN original_description VARCHAR(300) NULL,
    ADD COLUMN resolved_master_id BIGINT NULL,
    ADD COLUMN resolution_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN resolved_at TIMESTAMP(6) NULL,
    ADD CONSTRAINT ck_report_entry CHECK ((site_id IS NOT NULL AND original_description IS NULL) OR (site_id IS NULL AND original_description IS NOT NULL)),
    ADD CONSTRAINT ck_report_resolution CHECK ((resolved_master_id IS NULL AND resolution_key IS NULL AND resolved_at IS NULL) OR (site_id IS NULL AND resolved_master_id IS NOT NULL AND resolution_key IS NOT NULL AND resolved_at IS NOT NULL));
ALTER TABLE whatsapp_report_coworkers
    RENAME COLUMN site_id TO entry_id,
    ADD CONSTRAINT fk_report_coworker_entry FOREIGN KEY (request_id,entry_id) REFERENCES whatsapp_report_sites(request_id,entry_id) ON DELETE RESTRICT;
ALTER TABLE whatsapp_report_requests
    DROP CHECK ck_report_step,
    ADD CONSTRAINT ck_report_step CHECK (step IN ('START','SITES','OTHER_TEXT','COWORKERS','CONFIRM_WORK','CONFIRM_NONE','SUBMITTED'));
-- Only accepted, bounded Other descriptions are stored here, including deferred contextual replies.
-- Invalid/ambiguous input is acknowledged/deduplicated without retaining its raw text.
ALTER TABLE whatsapp_report_inbox ADD COLUMN accepted_text VARCHAR(300) NULL;
CREATE INDEX ix_assignment_reporting_worker_date ON work_assignments(team_member_id,date);
CREATE INDEX ix_assignment_reporting_site_date ON work_assignments(master_worksite_id,date);
