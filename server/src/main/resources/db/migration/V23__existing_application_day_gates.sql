-- Preserve old applications' submission/cancellation fence without inventing business numbers.
INSERT IGNORE INTO application_days(business_date,state)
SELECT DISTINCT application_date,'OPEN' FROM orders;
INSERT IGNORE INTO application_days(business_date,state)
SELECT DISTINCT application_date,'OPEN' FROM conversions;
