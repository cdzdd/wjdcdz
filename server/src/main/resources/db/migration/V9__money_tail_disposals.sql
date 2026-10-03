CREATE TABLE money_tail_disposals (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id BIGINT NOT NULL,snapshot_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,fund_class_id BIGINT NOT NULL,source_card_id BIGINT NOT NULL,
 original_tail DECIMAL(24,12) NOT NULL,settled_amount DECIMAL(20,2) NOT NULL,payout_id BIGINT NULL,
 reason VARCHAR(500) NOT NULL,created_at DATETIME NOT NULL,
 UNIQUE(snapshot_id,event_id,original_tail),
 FOREIGN KEY(event_id) REFERENCES liquidation_events(id),FOREIGN KEY(snapshot_id) REFERENCES money_income_snapshots(id)
);
