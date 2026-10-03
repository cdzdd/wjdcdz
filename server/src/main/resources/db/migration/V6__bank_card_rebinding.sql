ALTER TABLE trading_accounts ADD COLUMN bind_request_key VARCHAR(100) NULL,
 ADD COLUMN bind_fingerprint VARCHAR(64) NULL,
 ADD UNIQUE KEY trading_bind_request(user_id,bind_request_key);
UPDATE trading_accounts t JOIN card_bindings b ON b.trading_account_id=t.id AND b.binding_version=1
 JOIN bank_cards c ON c.id=b.bank_card_id SET t.bind_request_key=c.request_key;
