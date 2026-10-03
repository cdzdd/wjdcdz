ALTER TABLE orders ADD COLUMN planned_confirmation_date DATE NULL,
 ADD COLUMN confirmed_at DATETIME NULL;
UPDATE orders SET planned_confirmation_date=confirmation_date;
UPDATE orders o JOIN order_events e ON e.order_id=o.id AND e.event_type='CONFIRM'
 SET o.confirmed_at=e.created_at;
