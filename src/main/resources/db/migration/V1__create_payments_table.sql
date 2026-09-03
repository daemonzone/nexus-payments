CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    user_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Not idempotency yet (no dedupe/lookup-before-insert logic), just a
    -- domain invariant a given order has at most one payment - this is
    -- what idempotent consumption will later rely on.
    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);
