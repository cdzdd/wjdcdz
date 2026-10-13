ALTER TABLE trade_confirmations ADD COLUMN reversed_by_run_id BIGINT NULL;
ALTER TABLE share_entries
 ADD COLUMN round_id BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN effect_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 ADD COLUMN reversal_of BIGINT NULL,
 ADD COLUMN effective_date DATE NULL,
 ADD COLUMN operation_sequence BIGINT NULL,
 ADD COLUMN recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 DROP INDEX trading_account_id,
 ADD UNIQUE KEY uk_share_effect(trading_account_id,fund_class_id,lot_id,source_type,source_id,event_type,round_id);
ALTER TABLE cash_entries
 ADD COLUMN round_id BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN reversal_of BIGINT NULL,
 ADD COLUMN effective_date DATE NULL,
 ADD COLUMN recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 DROP INDEX trading_account_id,
 ADD UNIQUE KEY uk_cash_effect(trading_account_id,source_type,source_id,event_type,round_id);
ALTER TABLE payouts
 ADD COLUMN round_id BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN previous_payout_id BIGINT NULL,
 DROP INDEX trading_account_id,
 ADD UNIQUE KEY uk_payout_round(trading_account_id,source_type,source_id,source_card_id,round_id);

CREATE TABLE nav_revision_sets (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(200) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 actor_id BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_run_id BIGINT NULL,
 UNIQUE KEY uk_nav_revision_request(actor_id,request_key)
);
CREATE TABLE nav_revision_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 revision_set_id BIGINT NOT NULL,
 fund_class_id BIGINT NOT NULL,
 market_date DATE NOT NULL,
 previous_nav DECIMAL(20,6) NOT NULL,
 nav DECIMAL(20,6) NOT NULL,
 previous_revision_id BIGINT NULL,
 FOREIGN KEY(revision_set_id) REFERENCES nav_revision_sets(id),
 UNIQUE KEY uk_nav_revision_item(revision_set_id,fund_class_id,market_date)
);
ALTER TABLE nav_records ADD COLUMN active_revision_id BIGINT NULL;
