-- Cost, frozen shares and share reconciliation sum all audit rounds by immutable lot ID.
CREATE INDEX ix_share_entries_lot ON share_entries(lot_id);
