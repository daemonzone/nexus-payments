-- Nullable: only populated once a payment reaches COMPLETED. PENDING and
-- FAILED payments never have a provider transaction id.
ALTER TABLE payments ADD COLUMN provider_transaction_id VARCHAR(100);
ALTER TABLE payments ADD CONSTRAINT uq_payments_provider_transaction_id UNIQUE (provider_transaction_id);
