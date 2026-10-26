-- Representative legacy schema from the existing entity mappings. Synthetic fixtures only.
-- Exclusively used AFTER the per-run disposable-container guard has been checked.
DROP TABLE IF EXISTS whatsapp_report_reviews;
DROP TABLE IF EXISTS assignment_day_revisions;
DROP TABLE IF EXISTS assignment_week_guards;
DROP TABLE IF EXISTS whatsapp_report_outbound;
DROP TABLE IF EXISTS whatsapp_daily_members;
DROP TABLE IF EXISTS whatsapp_daily_runs;
DROP TABLE IF EXISTS whatsapp_report_coworkers;
DROP TABLE IF EXISTS whatsapp_report_inbox;
DROP TABLE IF EXISTS whatsapp_report_sites;
DROP TABLE IF EXISTS whatsapp_report_requests;
DROP TABLE IF EXISTS payroll_adjustment;
DROP TABLE IF EXISTS payroll_line;
DROP TABLE IF EXISTS payroll_week;
DROP TABLE IF EXISTS work_assignment_overtime;
DROP TABLE IF EXISTS work_assignments;
DROP TABLE IF EXISTS master_worksites;
DROP TABLE IF EXISTS team_members;

CREATE TABLE team_members (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL, role VARCHAR(255) NOT NULL,
    experience VARCHAR(255), contact VARCHAR(255), daily_wage DOUBLE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE master_worksites (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    approved_name VARCHAR(255) NOT NULL UNIQUE, notes VARCHAR(255),
    city VARCHAR(255), area VARCHAR(255), sub_area VARCHAR(255), location_details VARCHAR(255),
    project_size_tier VARCHAR(20), estimated_aream2 DOUBLE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE work_assignments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, team_member_id BIGINT NOT NULL,
    master_worksite_id BIGINT NOT NULL, date DATE NOT NULL,
    regular_hours_override DECIMAL(65,30), overtime_hours_override DECIMAL(65,30),
    FOREIGN KEY (team_member_id) REFERENCES team_members(id),
    FOREIGN KEY (master_worksite_id) REFERENCES master_worksites(id)
) ENGINE=InnoDB;
CREATE TABLE work_assignment_overtime (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, team_member_id BIGINT NOT NULL,
    date DATE NOT NULL, overtime_hours DOUBLE NOT NULL,
    UNIQUE (team_member_id,date), FOREIGN KEY (team_member_id) REFERENCES team_members(id)
) ENGINE=InnoDB;
CREATE TABLE payroll_week (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, week_start DATE NOT NULL UNIQUE, week_end DATE NOT NULL,
    status VARCHAR(20), created_at TIMESTAMP(6) NULL, updated_at TIMESTAMP(6) NULL,
    total_base DOUBLE, total_ot_pay DOUBLE, total_adjustments DOUBLE, total_to_pay DOUBLE
) ENGINE=InnoDB;
CREATE TABLE payroll_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, payroll_week_id BIGINT NOT NULL,
    team_member_id BIGINT, team_member_name VARCHAR(255), base_wages DOUBLE,
    computed_ot_hours DOUBLE, computed_ot_pay DOUBLE, ot_hours_override DOUBLE,
    ot_override_note VARCHAR(255), effective_ot_pay DOUBLE, adjustments_total DOUBLE, final_total DOUBLE,
    FOREIGN KEY (payroll_week_id) REFERENCES payroll_week(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE payroll_adjustment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, payroll_line_id BIGINT NOT NULL,
    amount DOUBLE NOT NULL, note VARCHAR(500), FOREIGN KEY (payroll_line_id) REFERENCES payroll_line(id)
) ENGINE=InnoDB;

INSERT INTO team_members VALUES
    (7,'Fixture worker seven','fixture role','fixture experience','Free text: +970599000007 / +972599000007; do not infer eligibility',240),
    (8,'Fixture coworker eight','fixture role',NULL,'Unstructured contact',300),
    (9,'Fixture worker nine','fixture role',NULL,NULL,260);
INSERT INTO master_worksites(id,approved_name,notes,city) VALUES
    (11,'موقع تجريبي أول','preserve existing notes','fixture city'),
    (12,'موقع تجريبي ثان',NULL,'fixture city'),
    (13,'Unselected fixture site',NULL,NULL);
INSERT INTO work_assignments VALUES (101,7,11,'2026-10-22',5,1),(102,7,12,'2026-10-22',3,0.5),(103,8,11,'2026-10-22',8,2);
INSERT INTO work_assignment_overtime VALUES (201,7,'2026-10-22',1.5),(202,8,'2026-10-22',2);
INSERT INTO payroll_week VALUES (301,'2026-10-17','2026-10-22','FINALIZED',NULL,NULL,540,100,15,655);
INSERT INTO payroll_line VALUES (401,301,7,'Fixture snapshot seven',240,1.5,60,NULL,NULL,60,15,315);
INSERT INTO payroll_adjustment VALUES (501,401,15,'fixture adjustment snapshot');
