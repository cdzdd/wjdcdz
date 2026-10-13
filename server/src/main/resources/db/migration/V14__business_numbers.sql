CREATE TABLE business_number_sequences (
 kind VARCHAR(20) NOT NULL, business_date DATE NOT NULL, sequence_value INT NOT NULL DEFAULT 0,
 PRIMARY KEY(kind,business_date), CHECK(kind IN ('APPLICATION','CONFIRMATION')),
 CHECK(sequence_value BETWEEN 0 AND 999999)
);
CREATE TABLE business_number_registry (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, kind VARCHAR(20) NOT NULL, business_no CHAR(14) NOT NULL,
 business_date DATE NOT NULL, sequence_no INT NOT NULL,
 source_type VARCHAR(30) NULL, source_id BIGINT NULL, round_id BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, business_time DATETIME(6) NOT NULL,
 UNIQUE(kind,business_no), UNIQUE(kind,business_date,sequence_no), UNIQUE(kind,source_type,source_id,round_id),
 CHECK(kind IN ('APPLICATION','CONFIRMATION')), CHECK(sequence_no BETWEEN 1 AND 999999)
);
CREATE TABLE trade_confirmations (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, confirmation_no CHAR(14) NOT NULL UNIQUE,
 source_type VARCHAR(30) NOT NULL, source_id BIGINT NOT NULL, round_id BIGINT NOT NULL,
 confirmation_number_date DATE NOT NULL, effective_date DATE NOT NULL, result JSON NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', previous_confirmation_id BIGINT NULL,
 confirmed_at DATETIME(6) NOT NULL, recorded_at DATETIME(6) NOT NULL,
 UNIQUE(source_type,source_id,round_id), CHECK(state IN ('ACTIVE','REVERSED')),
 FOREIGN KEY(previous_confirmation_id) REFERENCES trade_confirmations(id)
);
ALTER TABLE orders ADD application_no CHAR(14) NULL UNIQUE, ADD current_confirmation_id BIGINT NULL,
 ADD FOREIGN KEY(current_confirmation_id) REFERENCES trade_confirmations(id);
ALTER TABLE conversions ADD application_no CHAR(14) NULL UNIQUE, ADD current_confirmation_id BIGINT NULL,
 ADD FOREIGN KEY(current_confirmation_id) REFERENCES trade_confirmations(id);
