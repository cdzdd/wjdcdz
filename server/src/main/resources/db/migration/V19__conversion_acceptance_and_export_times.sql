ALTER TABLE conversions ADD accepted_source_card_id BIGINT NULL, ADD accepted_binding_version BIGINT NULL, ADD accepted_fund_account_id BIGINT NULL;
ALTER TABLE application_exports ADD deadline_business_time DATETIME(6) NULL, ADD completed_business_time DATETIME(6) NULL, ADD completed_before_deadline BOOLEAN NULL;
