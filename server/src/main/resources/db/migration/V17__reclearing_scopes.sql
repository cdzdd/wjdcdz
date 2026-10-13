CREATE TABLE reclearing_previews (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 revision_set_id BIGINT NOT NULL,
 scope_version CHAR(64) NOT NULL,
 actor_id BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL,
 result JSON NOT NULL,
 run_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(revision_set_id) REFERENCES nav_revision_sets(id),
 FOREIGN KEY(run_id) REFERENCES clearing_runs(id)
);
CREATE TABLE reclearing_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 run_id BIGINT NOT NULL,
 source_type VARCHAR(40) NOT NULL,
 source_id BIGINT NOT NULL,
 trading_account_id BIGINT NOT NULL,
 effective_date DATE NOT NULL,
 operation_sequence BIGINT NOT NULL,
 before_result JSON NOT NULL,
 after_result JSON NOT NULL,
 FOREIGN KEY(run_id) REFERENCES clearing_runs(id),
 UNIQUE KEY uk_reclearing_item(run_id,source_type,source_id)
);
