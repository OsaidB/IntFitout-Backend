-- MANUAL, ONCE ONLY, MySQL 8.0.16+ / InnoDB. Review against a schema-only staging copy first.
-- Not a Spring schema.sql or a Flyway migration: application startup NEVER executes this file.
-- DDL implicitly commits in MySQL. Take the normal operator-approved backup beforehand.
-- Do not run against a live application with reporting enabled. No workers/sites are opted in here.

ALTER TABLE team_members
    ADD COLUMN whatsapp_reporting_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN whatsapp_number VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uq_team_whatsapp_number UNIQUE (whatsapp_number),
    ADD CONSTRAINT ck_team_whatsapp_number CHECK (whatsapp_number IS NULL OR whatsapp_number REGEXP '^[1-9][0-9]{6,14}$'),
    ADD CONSTRAINT ck_team_whatsapp_eligibility CHECK (whatsapp_reporting_eligible IN (0,1) AND (whatsapp_reporting_eligible = FALSE OR whatsapp_number IS NOT NULL));

ALTER TABLE master_worksites
    ADD COLUMN whatsapp_reporting_selectable BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE whatsapp_report_requests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    worker_id BIGINT NOT NULL,
    recipient VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    phone_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_date DATE NOT NULL,
    step VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    current_site BIGINT NULL,
    outcome VARCHAR(20) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_inbound_at TIMESTAMP(6) NULL,
    submitted_at TIMESTAMP(6) NULL,
    CONSTRAINT uq_report_worker_date UNIQUE (worker_id,work_date),
    CONSTRAINT ck_report_step CHECK (step IN ('START','SITES','COWORKERS','CONFIRM_WORK','CONFIRM_NONE','SUBMITTED')),
    CONSTRAINT ck_report_outcome CHECK (outcome IS NULL OR outcome IN ('WORKED','DID_NOT_WORK')),
    CONSTRAINT ck_report_submitted CHECK ((step = 'SUBMITTED' AND submitted_at IS NOT NULL AND outcome IS NOT NULL) OR (step <> 'SUBMITTED' AND submitted_at IS NULL)),
    INDEX ix_report_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Logical IDs for existing workers/master worksites deliberately have NO new foreign keys to
-- legacy tables: existing deletion behavior is preserved, and report label snapshots survive.
CREATE TABLE whatsapp_report_sites (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    site_id BIGINT NOT NULL,
    label VARCHAR(255) NOT NULL,
    PRIMARY KEY (request_id,site_id),
    CONSTRAINT fk_report_site_request FOREIGN KEY (request_id) REFERENCES whatsapp_report_requests(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE whatsapp_report_coworkers (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    site_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    label VARCHAR(255) NOT NULL,
    PRIMARY KEY (request_id,site_id,worker_id),
    CONSTRAINT fk_report_coworker_site FOREIGN KEY (request_id,site_id) REFERENCES whatsapp_report_sites(request_id,site_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE whatsapp_report_inbox (
    event_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    message_id VARCHAR(518) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_id VARCHAR(518) CHARACTER SET ascii COLLATE ascii_bin NULL,
    kind VARCHAR(24) NOT NULL,
    action VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    disposition VARCHAR(24) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    message_at TIMESTAMP(6) NULL,
    INDEX ix_report_inbox_message (message_id),
    CONSTRAINT fk_report_inbox_request FOREIGN KEY (request_id) REFERENCES whatsapp_report_requests(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE whatsapp_report_outbound (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    payload LONGTEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    meta_id VARCHAR(518) CHARACTER SET ascii COLLATE ascii_bin NULL,
    delivery VARCHAR(16) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_report_outbound_revision UNIQUE (request_id,revision),
    CONSTRAINT uq_report_outbound_meta UNIQUE (meta_id),
    CONSTRAINT fk_report_outbound_request FOREIGN KEY (request_id) REFERENCES whatsapp_report_requests(id) ON DELETE RESTRICT,
    CONSTRAINT ck_report_outbound_state CHECK (state IN ('PENDING','SENDING','ACCEPTED','REJECTED','UNCERTAIN','CANCELLED')),
    INDEX ix_report_outbound_pending (state,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
