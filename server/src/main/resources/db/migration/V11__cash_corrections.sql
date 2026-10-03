CREATE TABLE cash_corrections (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,original_entry_id BIGINT NOT NULL,actor_id BIGINT NOT NULL,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,
 available_delta DECIMAL(20,2) NOT NULL,reason VARCHAR(1000) NOT NULL,request_key VARCHAR(100) NOT NULL UNIQUE,request_fingerprint VARCHAR(64) NOT NULL,
 created_at DATETIME NOT NULL,FOREIGN KEY(original_entry_id) REFERENCES cash_entries(id)
);
