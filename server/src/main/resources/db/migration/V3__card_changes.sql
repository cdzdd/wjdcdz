CREATE TABLE card_change_requests(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,old_card_id BIGINT NOT NULL,new_card_id BIGINT NOT NULL,
 old_binding_version BIGINT NOT NULL,new_binding_version BIGINT NULL,status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 reason VARCHAR(500) NOT NULL,approval_reason VARCHAR(500) NULL,approved_by BIGINT NULL,approved_at DATETIME NULL,
 request_key VARCHAR(100) NOT NULL,created_at DATETIME NOT NULL,UNIQUE(user_id,request_key)
);
