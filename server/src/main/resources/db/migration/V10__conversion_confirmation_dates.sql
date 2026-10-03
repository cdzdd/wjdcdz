ALTER TABLE conversions ADD COLUMN planned_confirmation_date DATE NULL, ADD COLUMN confirmed_at DATETIME NULL, ADD COLUMN cutoff_time TIME NOT NULL DEFAULT '15:00:00';
UPDATE conversions SET planned_confirmation_date=confirmation_date;
