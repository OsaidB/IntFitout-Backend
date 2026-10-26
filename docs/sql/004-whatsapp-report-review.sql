-- Manual migration AFTER 001-003. Do not rerun those migrations on an existing database.
-- Apply once before deploying code. Hibernate does NOT manage these tables.
-- No worker answers, assignments, overtime or payroll are rewritten/backfilled.
-- Absence of a review row means DRAFT (unsubmitted) / AWAITING_REVIEW (submitted).
-- Existing and initially empty days start at revision zero without a backfill.
CREATE TABLE assignment_week_guards (
    week_start DATE NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE assignment_day_revisions (
    work_date DATE NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CHECK (revision >= 0)
) ENGINE=InnoDB;
CREATE TABLE whatsapp_report_reviews (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    approved_at TIMESTAMP(6) NOT NULL,
    reviewer_kind VARCHAR(32) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    decision_json JSON NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    day_revision BIGINT NOT NULL,
    CONSTRAINT fk_review_request FOREIGN KEY(request_id) REFERENCES whatsapp_report_requests(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_reporting_review_date ON whatsapp_report_requests(work_date,submitted_at,id);
