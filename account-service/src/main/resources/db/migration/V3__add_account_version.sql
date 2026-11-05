-- Optimistic-lock version column mirroring payment-service's Account.
-- The outbox poller's debit/credit writes rely on @Version to reject stale
-- concurrent updates so a conflicted poll transaction rolls back and retries.
ALTER TABLE accounts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
