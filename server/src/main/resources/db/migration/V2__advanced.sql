CREATE TABLE offering_orders (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,order_id BIGINT NOT NULL UNIQUE,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,
 fund_class_id BIGINT NOT NULL,source_card_id BIGINT NOT NULL,status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
 interest DECIMAL(20,2) NOT NULL DEFAULT 0,created_at DATETIME NOT NULL,FOREIGN KEY(order_id) REFERENCES orders(id)
);
CREATE TABLE offering_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,fund_class_id BIGINT NOT NULL UNIQUE,outcome VARCHAR(30) NOT NULL,end_date DATE NOT NULL,
 initial_nav DECIMAL(20,6) NOT NULL,interest_rate DECIMAL(12,8) NOT NULL,status VARCHAR(30) NOT NULL DEFAULT 'PROCESSING',created_at DATETIME NOT NULL
);
CREATE TABLE sip_plans (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,bank_card_id BIGINT NOT NULL,binding_version BIGINT NOT NULL,
 fund_class_id BIGINT NOT NULL,amount DECIMAL(20,2) NOT NULL,frequency VARCHAR(20) NOT NULL,day INT NOT NULL,start_date DATE NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',request_key VARCHAR(100) NOT NULL,created_at DATETIME NOT NULL,UNIQUE(user_id,request_key)
);
CREATE TABLE sip_runs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,plan_id BIGINT NOT NULL,scheduled_date DATE NOT NULL,execution_date DATE NOT NULL,
 status VARCHAR(30) NOT NULL,order_id BIGINT NULL,reason VARCHAR(500) NULL,created_at DATETIME NOT NULL,UNIQUE(plan_id,scheduled_date)
);
CREATE TABLE dividend_preferences(user_id BIGINT NOT NULL,fund_class_id BIGINT NOT NULL,mode VARCHAR(20) NOT NULL,PRIMARY KEY(user_id,fund_class_id));
CREATE TABLE dividend_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,fund_class_id BIGINT NOT NULL,record_date DATE NOT NULL,ex_date DATE NOT NULL,payment_date DATE NOT NULL,
 per_share DECIMAL(20,6) NOT NULL,status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',created_at DATETIME NOT NULL,
 UNIQUE(fund_class_id,record_date,ex_date)
);
CREATE TABLE dividend_entitlements (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id BIGINT NOT NULL,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,source_card_id BIGINT NOT NULL,
 fund_account_id BIGINT NOT NULL,shares DECIMAL(24,4) NOT NULL,amount DECIMAL(20,2) NOT NULL,mode VARCHAR(20) NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING',cost_adjusted BOOLEAN NOT NULL DEFAULT FALSE,payout_id BIGINT NULL,created_at DATETIME NOT NULL,
 UNIQUE(event_id,trading_account_id,source_card_id)
);
CREATE TABLE dividend_lot_entitlements (
 entitlement_id BIGINT NOT NULL,lot_id BIGINT NOT NULL,shares DECIMAL(24,4) NOT NULL,amount DECIMAL(20,2) NOT NULL,PRIMARY KEY(entitlement_id,lot_id)
);
CREATE TABLE money_income_records (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,fund_class_id BIGINT NOT NULL,date DATE NOT NULL,income_per_ten_thousand DECIMAL(20,8) NOT NULL,
 annual_yield DECIMAL(12,8) NOT NULL DEFAULT 0,used BOOLEAN NOT NULL DEFAULT FALSE,UNIQUE(fund_class_id,date)
);
CREATE TABLE money_income_snapshots (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,date DATE NOT NULL,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,fund_class_id BIGINT NOT NULL,
 source_card_id BIGINT NOT NULL,fund_account_id BIGINT NOT NULL,shares DECIMAL(24,4) NOT NULL,
 income DECIMAL(24,12) NOT NULL DEFAULT 0,tail DECIMAL(24,12) NOT NULL DEFAULT 0,status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 UNIQUE(date,trading_account_id,fund_class_id,source_card_id)
);
CREATE TABLE conversion_pairs(source_class_id BIGINT NOT NULL,target_class_id BIGINT NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE,PRIMARY KEY(source_class_id,target_class_id));
CREATE TABLE conversions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,source_class_id BIGINT NOT NULL,target_class_id BIGINT NOT NULL,
 shares DECIMAL(24,4) NOT NULL,status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',application_date DATE NOT NULL,confirmation_date DATE NOT NULL,
 source_rate DECIMAL(12,8) NOT NULL,target_rate DECIMAL(12,8) NOT NULL,redemption_rate DECIMAL(12,8) NOT NULL,
 source_rule_version INT NOT NULL,target_rule_version INT NOT NULL,source_nav DECIMAL(20,6) NULL,target_nav DECIMAL(20,6) NULL,
 target_shares DECIMAL(24,4) NOT NULL DEFAULT 0,fee DECIMAL(20,2) NOT NULL DEFAULT 0,reason VARCHAR(500) NULL,
 request_key VARCHAR(100) NOT NULL,created_at DATETIME NOT NULL,UNIQUE(user_id,request_key)
);
CREATE TABLE conversion_allocations(id BIGINT PRIMARY KEY AUTO_INCREMENT,conversion_id BIGINT NOT NULL,lot_id BIGINT NOT NULL,shares DECIMAL(24,4) NOT NULL,cost DECIMAL(20,2) NOT NULL,source_card_id BIGINT NOT NULL,fund_account_id BIGINT NOT NULL,acquired_date DATE NOT NULL,UNIQUE(conversion_id,lot_id));
CREATE TABLE liquidation_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,fund_class_id BIGINT NOT NULL UNIQUE,per_share DECIMAL(20,6) NOT NULL,date DATE NOT NULL,
 reason VARCHAR(500) NOT NULL,status VARCHAR(30) NOT NULL DEFAULT 'PROCESSING',created_at DATETIME NOT NULL
);
CREATE TABLE liquidation_items(id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id BIGINT NOT NULL,user_id BIGINT NOT NULL,trading_account_id BIGINT NOT NULL,source_card_id BIGINT NOT NULL,shares DECIMAL(24,4) NOT NULL,amount DECIMAL(20,2) NOT NULL,payout_id BIGINT NOT NULL,UNIQUE(event_id,trading_account_id,source_card_id));
