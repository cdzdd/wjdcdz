CREATE TABLE extension_replay_inputs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 source_type VARCHAR(30) NOT NULL,
 source_id BIGINT NOT NULL,
 trading_account_id BIGINT NOT NULL,
 phase VARCHAR(20) NOT NULL,
 effective_date DATE NOT NULL,
 applied_at DATETIME(6) NOT NULL,
 operation_sequence BIGINT NOT NULL,
 payload JSON NOT NULL,
 UNIQUE KEY uk_extension_input(source_type,source_id,phase)
);
