-- US-09-01: completion reports and rework requests. Every report is kept;
-- a rework request is recorded on the report it sends back. The summary and
-- the reasons stay in Dispatch: events carry only the intervention's state.
CREATE TABLE completion_reports (
    intervention_id      CHAR(36) CHARACTER SET ascii NOT NULL,
    number               INT NOT NULL,
    summary              VARCHAR(2000) NOT NULL,
    performed_at         DATETIME(6) NOT NULL,
    reporter_role        VARCHAR(20) CHARACTER SET ascii NOT NULL,
    reason               VARCHAR(500) NULL,
    reported_by          VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    reported_at          DATETIME(6) NOT NULL,
    intervention_version BIGINT NOT NULL,
    rework_reason        VARCHAR(500) NULL,
    rework_by            VARCHAR(255) COLLATE utf8mb4_bin NULL,
    rework_at            DATETIME(6) NULL,
    PRIMARY KEY (intervention_id, number),
    CONSTRAINT fk_completion_reports_intervention FOREIGN KEY (intervention_id) REFERENCES interventions (id),
    CONSTRAINT ck_completion_reports_role CHECK (reporter_role IN ('contractor', 'administrator')),
    CONSTRAINT ck_completion_reports_reason CHECK ((reporter_role = 'administrator') = (reason IS NOT NULL)),
    CONSTRAINT ck_completion_reports_rework CHECK ((rework_at IS NULL) = (rework_reason IS NULL))
);
