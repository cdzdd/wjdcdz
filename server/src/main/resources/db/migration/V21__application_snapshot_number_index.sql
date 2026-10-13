ALTER TABLE application_export_items
 ADD application_no CHAR(14) GENERATED ALWAYS AS (LEFT(JSON_UNQUOTE(JSON_EXTRACT(canonical_row,'$.application_no')),14)) STORED,
 ADD INDEX ix_export_application_no(export_id,application_no,id);
