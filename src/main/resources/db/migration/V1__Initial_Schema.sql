CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Restaurants table
CREATE TABLE restaurants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Deals table
CREATE TABLE deals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,
    price_paise INT NOT NULL CHECK (price_paise >= 1 AND price_paise <= 1000000),
    stock_total INT NOT NULL CHECK (stock_total >= 1 AND stock_total <= 10000),
    stock_available INT NOT NULL CHECK (stock_available >= 0),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_deal_time_window CHECK (ends_at > starts_at),
    CONSTRAINT chk_stock_bounds CHECK (stock_available <= stock_total)
);

CREATE INDEX idx_deals_time ON deals (starts_at, ends_at);

-- 3. Claims table
CREATE TABLE claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 1 AND quantity <= 3),
    amount_paise INT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Core Invariant: Max 1 active claim per customer per deal
-- Active statuses: RESERVED, PAID, NEEDS_REVIEW
CREATE UNIQUE INDEX uq_active_claim_per_customer 
ON claims (deal_id, customer_id) 
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Expiry worker index: quickly locate active reserved claims that have timed out
CREATE INDEX idx_claims_reserved_expiry 
ON claims (status, expires_at) 
WHERE status = 'RESERVED';

-- 4. Idempotency Records
CREATE TABLE idempotency_records (
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    deal_id UUID NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL, -- 'IN_PROGRESS', 'COMPLETED'
    response_status_code INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (customer_id, idempotency_key)
);

CREATE INDEX idx_idempotency_created_at ON idempotency_records (created_at);

-- 5. Processed Webhooks (Deduplication)
CREATE TABLE processed_webhooks (
    event_id VARCHAR(128) PRIMARY KEY,
    claim_id UUID REFERENCES claims(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL,
    raw_payload TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
