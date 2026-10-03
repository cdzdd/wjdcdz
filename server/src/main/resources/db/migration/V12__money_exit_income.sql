ALTER TABLE money_income_snapshots ADD COLUMN payout_id BIGINT NULL;
CREATE TABLE money_exit_disposals (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,snapshot_id BIGINT NOT NULL UNIQUE,
 user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,fund_class_id BIGINT NOT NULL,source_card_id BIGINT NOT NULL,
 carried_tail DECIMAL(24,12) NOT NULL,original_tail DECIMAL(24,12) NOT NULL,
 settled_amount DECIMAL(20,2) NOT NULL,payout_id BIGINT NULL,reason VARCHAR(500) NOT NULL,created_at DATETIME NOT NULL,
 FOREIGN KEY(snapshot_id) REFERENCES money_income_snapshots(id),FOREIGN KEY(payout_id) REFERENCES payouts(id)
);
