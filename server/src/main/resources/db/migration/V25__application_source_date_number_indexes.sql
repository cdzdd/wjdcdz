ALTER TABLE orders ADD INDEX ix_order_application_export(application_date,application_no,fund_class_id,status);
ALTER TABLE conversions ADD INDEX ix_conversion_application_export(application_date,application_no,source_class_id,status);
