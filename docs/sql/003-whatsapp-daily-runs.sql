-- MANUAL ONCE ONLY, after already-applied 001 and 002. Not application startup SQL.
-- Pause sending, stop application instances, back up and inspect schema before DDL.
-- MySQL DDL commits implicitly; inspect partial execution rather than blindly rerunning.
CREATE TABLE whatsapp_daily_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    phone_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_date DATE NOT NULL,
    phase VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    cutoff_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    owner_recipient VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    father_recipient VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    template_language VARCHAR(10) NOT NULL,
    failure_code VARCHAR(40) NULL,
    summary_text TEXT NULL,
    summary_parameters TEXT NULL,
    CONSTRAINT uq_daily_business_date UNIQUE (phone_id,work_date),
    CONSTRAINT ck_daily_phase CHECK (phase IN ('NEW','DISPATCHING','FAILED','SNAPSHOT')),
    CONSTRAINT ck_daily_snapshot CHECK ((phase='SNAPSHOT' AND summary_text IS NOT NULL AND summary_parameters IS NOT NULL) OR (phase<>'SNAPSHOT' AND summary_text IS NULL AND summary_parameters IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE whatsapp_daily_members (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    worker_id BIGINT NOT NULL,
    configured BOOLEAN NOT NULL,
    exclusion_code VARCHAR(40) NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (run_id,worker_id),
    CONSTRAINT fk_daily_member_run FOREIGN KEY (run_id) REFERENCES whatsapp_daily_runs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_daily_member_request FOREIGN KEY (request_id) REFERENCES whatsapp_report_requests(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE whatsapp_report_outbound
    MODIFY request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recipient_type VARCHAR(16) NOT NULL DEFAULT 'WORKER',
    ADD COLUMN recipient_identity VARCHAR(16) NOT NULL DEFAULT 'WORKER',
    ADD COLUMN recipient VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN phone_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN failure_code VARCHAR(40) NULL;
UPDATE whatsapp_report_outbound o JOIN whatsapp_report_requests r ON r.id=o.request_id
    SET o.recipient=r.recipient,o.phone_id=r.phone_id;
ALTER TABLE whatsapp_report_outbound
    MODIFY recipient VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY phone_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT fk_outbound_daily_run FOREIGN KEY (run_id) REFERENCES whatsapp_daily_runs(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_daily_recipient UNIQUE (run_id,recipient_type,recipient),
    ADD CONSTRAINT ck_outbound_owner CHECK ((recipient_type='WORKER' AND recipient_identity='WORKER' AND request_id IS NOT NULL) OR (recipient_type='MANAGEMENT' AND recipient_identity IN ('OWNER','FATHER') AND request_id IS NULL AND run_id IS NOT NULL AND kind='MGMT_SUMMARY' AND revision=0)),
    ADD CONSTRAINT ck_outbound_recipient CHECK (recipient REGEXP '^[1-9][0-9]{6,14}$');
