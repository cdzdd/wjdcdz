CREATE TABLE application_days (
 business_date DATE PRIMARY KEY, state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 sealed_business_time DATETIME(6) NULL, sealed_at DATETIME(6) NULL,
 CHECK(state IN ('OPEN','SEALED'))
);
CREATE TABLE application_exports (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, business_date DATE NOT NULL, registrar_id BIGINT NOT NULL,
 protocol_version VARCHAR(40) NOT NULL, snapshot_version INT NOT NULL DEFAULT 1,
 state VARCHAR(20) NOT NULL DEFAULT 'QUEUED', run_id BIGINT NULL,
 row_count BIGINT NOT NULL DEFAULT 0, excluded_cancelled_count BIGINT NOT NULL DEFAULT 0,
 amount_total DECIMAL(24,2) NOT NULL DEFAULT 0, shares_total DECIMAL(28,4) NOT NULL DEFAULT 0,
 checksum CHAR(64) NULL, file_name VARCHAR(128) NULL, error_reason VARCHAR(1000) NULL,
 actor_id BIGINT NULL, created_at DATETIME(6) NOT NULL, started_at DATETIME(6) NULL, completed_at DATETIME(6) NULL,
 UNIQUE(business_date,registrar_id,protocol_version), INDEX(state,id),
 FOREIGN KEY(registrar_id) REFERENCES registrars(id)
);
CREATE TABLE application_export_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, export_id BIGINT NOT NULL, source_type VARCHAR(30) NOT NULL,
 source_id BIGINT NOT NULL, canonical_row JSON NOT NULL,
 UNIQUE(export_id,source_type,source_id), INDEX(export_id,id), FOREIGN KEY(export_id) REFERENCES application_exports(id)
);
