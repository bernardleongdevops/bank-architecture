# Requirements Document

## Introduction

This feature propagates a completed money transfer in the RP Digital Bank Sandbox from `payment-service` to `account-service` (balances) and `transaction-service` (statement history) using the Transactional Outbox pattern plus independent `@Scheduled` pollers.

Today a completed transfer mutates state only inside `payment-service`'s own schema and publishes a `PaymentCompletedEvent` through the in-JVM `InProcessEventPublisher`; no other service reacts, so account-service balances drift out of sync and transaction-service history stays empty. This feature adds a new `OutboxEventPublisher` (active only when `provider.events=outbox`) that writes a `PaymentCompletedEvent` into a shared `event_outbox` table inside the same transaction as the money movement. Each consumer service independently polls the outbox read-only, applies its own local effect, and records the `transactionRef` in a per-service `processed_events` table for idempotency.

No new infrastructure is introduced (no Kafka/Redis/RabbitMQ, no inter-service HTTP). Per-service database isolation is preserved: the outbox table is written only by payment-service and read-only polled by the two consumers; all data a consumer needs travels inside the event payload. Scope is Tasks 1-3 (backend foundation).

All services share one MySQL server but connect to separate per-service databases (payment-service to `sandbox_payment`, account-service to `sandbox_account`, transaction-service to `sandbox_transaction`). The `event_outbox` table lives only in payment-service's database; the two consumers read it via a read-only cross-database SELECT qualified by a configurable schema name (default `sandbox_payment`). Each consumer still owns and writes only its own database (accounts, ledger_entries, processed_events), so per-service write isolation is intact while the outbox is genuinely shared.

## Glossary

- **Transfer**: A money movement in `payment-service` that debits a source account and credits a destination account within a single `@Transactional` operation.
- **Completed_Transfer**: A Transfer that passes ownership, funds, currency, and AML checks and whose transaction commits.
- **Payment_Service**: The `payment-service` module that owns the transfer logic and the `event_outbox` table (write-owner).
- **Account_Service**: The `account-service` module that owns the `sandbox_account` schema and projects balances from outbox events.
- **Transaction_Service**: The `transaction-service` module that owns the `sandbox_transaction` schema and projects statement history from outbox events.
- **Outbox_Publisher**: The `OutboxEventPublisher` implementation of the existing `EventPublisher` seam that serializes a `DomainEvent` to JSON and inserts one `event_outbox` row.
- **Event_Outbox**: The shared `event_outbox` table in `payment-service`'s schema, written only by Payment_Service and read-only (SELECT) by consumers.
- **PaymentCompletedEvent**: The `DomainEvent` record describing a completed transfer, serialized as the outbox row payload.
- **Account_Poller**: The `AccountOutboxPoller` `@Scheduled` component in Account_Service.
- **Transaction_Poller**: The `TransactionOutboxPoller` `@Scheduled` component in Transaction_Service.
- **Processed_Events**: A per-consumer `processed_events` table whose primary key is `transactionRef`, serving as the idempotency guard.
- **LedgerEntry**: An immutable statement-history row in Transaction_Service with a `DEBIT` or `CREDIT` type.
- **transactionRef**: The unique reference identifying a transfer; the idempotency key across services.
- **customerRef**: The reference identifying the customer that owns an account; NOT NULL on every Transaction_Service ledger row.
- **Outbox_Schema**: The database/schema that owns the single shared `event_outbox` table (payment-service's database; default name `sandbox_payment`), configurable via a property so tests and deployments can override it.

## Requirements

### Requirement 1: Atomic Outbox Write

**User Story:** As a platform engineer, I want a completed transfer to stage exactly one outbox event inside the same transaction as the money movement, so that downstream services can never observe a transfer that did not actually commit, and never miss one that did.

#### Acceptance Criteria

1. WHEN a Transfer commits successfully, THE Payment_Service SHALL insert exactly one Event_Outbox row whose `transaction_ref` equals the committed transfer's `transactionRef`.
2. WHEN the Outbox_Publisher inserts an Event_Outbox row, THE Payment_Service SHALL perform that insert within the transfer's active @Transactional transaction and SHALL NOT open a new transaction or suspend the existing one.
3. IF the Event_Outbox insert fails, THEN THE Payment_Service SHALL roll back the entire transfer transaction and leave no Event_Outbox row and no money movement persisted for that `transactionRef`.
4. IF a Transfer fails validation, AML screening, or funds checks, THEN THE Payment_Service SHALL roll back the transaction and leave zero Event_Outbox rows for that `transactionRef`.
5. WHEN the Outbox_Publisher writes an Event_Outbox row, THE Payment_Service SHALL set `event_type` to the literal string `PaymentCompleted`, `transaction_ref` to the event's `transactionRef`, `payload` to the JSON serialization of the event, `occurred_at` to the event's `occurredAt`, and `created_at` to the insertion timestamp.
6. THE Payment_Service SHALL store the Event_Outbox `payload` as a portable TEXT JSON string rather than a native database JSON type.

### Requirement 2: Pluggable Publisher Selection

**User Story:** As a platform engineer, I want the event transport to be selectable by configuration with in-process as the default, so that the outbox transport is opt-in and existing behavior is preserved until explicitly enabled.

#### Acceptance Criteria

1. WHERE `provider.events` equals `outbox`, THE Payment_Service SHALL activate the Outbox_Publisher as the active `EventPublisher` implementation at application startup.
2. WHERE `provider.events` is unset, THE Payment_Service SHALL activate the in-process `EventPublisher` implementation as the default at application startup.
3. WHERE `provider.events` equals `in-process`, THE Payment_Service SHALL activate the in-process `EventPublisher` implementation at application startup.
4. THE Payment_Service SHALL keep the count of active `EventPublisher` beans equal to exactly one for any single value of `provider.events`.
5. WHERE `provider.events` is unset or equals `in-process`, THE Payment_Service SHALL NOT create the Outbox_Publisher bean, so that the in-process implementation is the only active `EventPublisher`.

### Requirement 3: Event Payload Completeness

**User Story:** As a downstream consumer developer, I want each completed-transfer event to carry both customer references, so that Transaction_Service can populate the NOT NULL `customerRef` on every ledger row without a cross-schema read.

#### Acceptance Criteria

1. WHEN the Payment_Service builds a PaymentCompletedEvent for a committed Transfer, THE Payment_Service SHALL set `fromCustomerRef` to the source account's `customerRef` and `toCustomerRef` to the destination account's `customerRef`.
2. WHEN the Payment_Service publishes a PaymentCompletedEvent, THE PaymentCompletedEvent SHALL carry a non-null `fromCustomerRef` and a non-null `toCustomerRef`.
3. THE PaymentCompletedEvent SHALL carry `transactionRef`, `fromAccount`, `toAccount`, `amount`, `currency`, and `occurredAt` in addition to the customer references.
4. WHEN the Payment_Service sources the customer references, THE Payment_Service SHALL read them from the account entities already loaded within the transfer, without querying another service's schema.

### Requirement 4: Account Balance Propagation

**User Story:** As an account holder, I want my balance in account-service to reflect completed transfers, so that my account balance stays consistent with the money that actually moved.

#### Acceptance Criteria

1. WHEN an Account_Poller poll cycle runs, THE Account_Service SHALL read Event_Outbox rows of type `PaymentCompleted` that are not yet marked processed, ordered by ascending `id`.
2. WHEN the Account_Poller processes an unprocessed PaymentCompletedEvent whose `fromAccount` exists in `sandbox_account`, THE Account_Service SHALL decrement that account's balance by the event's `amount`.
3. WHEN the Account_Poller processes an unprocessed PaymentCompletedEvent whose `toAccount` exists in `sandbox_account`, THE Account_Service SHALL increment that account's balance by the event's `amount`.
4. WHEN both the `fromAccount` and the `toAccount` of a processed event exist in `sandbox_account`, THE Account_Service SHALL decrease the source balance and increase the destination balance by the same `amount` within a single local transaction, yielding a net balance change of zero across the pair.
5. WHEN the Account_Service successfully applies an unprocessed event, THE Account_Service SHALL mark the event's `transactionRef` processed exactly once so that its balance delta is applied exactly once.
6. IF a processed event references an account not present in `sandbox_account`, THEN THE Account_Service SHALL skip the balance change for that account, mark the `transactionRef` processed, and raise no error.
7. IF a balance update encounters a stale `@Version` optimistic-lock conflict, THEN THE Account_Service SHALL reject the stale write, leave the balance unchanged, and leave the `transactionRef` unmarked so that it is retried on a later poll cycle.
8. THE Account_Service SHALL run the Account_Poller on a fixed-delay schedule configurable via `outbox.poll-delay-ms` with a default of 1000 milliseconds.

### Requirement 5: Account Service Idempotency

**User Story:** As an account holder, I want repeated or overlapping polls to never double-apply a transfer, so that my balance reflects each transfer exactly once.

#### Acceptance Criteria

1. WHEN the Account_Poller reads an Event_Outbox row whose `transactionRef` already exists in the Account_Service Processed_Events table, THE Account_Service SHALL skip that row and apply no change to the account balance.
2. WHEN the Account_Service applies the balance effect of an event whose `transactionRef` is absent from the Processed_Events table, THE Account_Service SHALL insert that `transactionRef` into the Processed_Events table within the same local transaction that performs the balance update.
3. IF an Event_Outbox row is read in more than one poll pass, THEN THE Account_Service SHALL apply that row's balance delta to the account exactly once across all passes.
4. THE Account_Service SHALL use `transactionRef` as the primary key of the Processed_Events table so that inserting an already-present `transactionRef` is rejected.
5. IF the insert of a `transactionRef` into the Processed_Events table is rejected because the value already exists, THEN THE Account_Service SHALL roll back the enclosing local transaction so that the associated balance update is not committed, leaving the account balance unchanged for that event.

### Requirement 6: Statement History Propagation

**User Story:** As an account holder, I want my statement history in transaction-service to show completed transfers, so that I can see the debit and credit for each transfer with full detail.

#### Acceptance Criteria

1. WHEN a Transaction_Poller poll cycle runs, THE Transaction_Service SHALL read Event_Outbox rows of type `PaymentCompleted` that are not yet marked processed, ordered by ascending `id`.
2. WHEN the Transaction_Poller processes an unprocessed PaymentCompletedEvent, THE Transaction_Service SHALL append exactly two LedgerEntry legs for that `transactionRef` via the existing `TransactionService.record(...)` operation, persisting both legs or neither.
3. WHEN the Transaction_Service appends the source leg, THE Transaction_Service SHALL set its type to `DEBIT`, `accountNumber` to `fromAccount`, `customerRef` to `fromCustomerRef`, and `counterparty` to `toAccount`.
4. WHEN the Transaction_Service appends the destination leg, THE Transaction_Service SHALL set its type to `CREDIT`, `accountNumber` to `toAccount`, `customerRef` to `toCustomerRef`, and `counterparty` to `fromAccount`.
5. WHEN the Transaction_Service appends a LedgerEntry leg, THE Transaction_Service SHALL set `transactionRef`, `description`, `amount`, and `currency` from the event and set `bookedAt` to the event's `occurredAt`.
6. WHERE a LedgerEntry leg is appended by the Transaction_Service, THE LedgerEntry SHALL carry a non-null `customerRef`.
7. WHEN the Transaction_Service has persisted both legs of an event successfully, THE Transaction_Service SHALL mark that event's `transactionRef` processed.
8. THE Transaction_Service SHALL run the Transaction_Poller on a fixed-delay schedule configurable via `outbox.poll-delay-ms` with a default of 1000 milliseconds.

> Note (addendum): the LedgerEntry `transaction_ref` column must accommodate a real transfer reference of `TXN-` + UUID (40 characters); transaction-service's original `VARCHAR(36)` was too narrow and is widened to `VARCHAR(50)` (see design addendum "Ledger transaction_ref column width").

### Requirement 7: Transaction Service Idempotency and Ordering

**User Story:** As an account holder, I want redelivered events to not duplicate my statement rows and my history to read newest-first, so that my statement is accurate and readable.

#### Acceptance Criteria

1. WHEN the Transaction_Poller reads an Event_Outbox row whose `transactionRef` already exists in the Transaction_Service Processed_Events table, THE Transaction_Service SHALL skip that row and append no LedgerEntry legs.
2. WHEN the Transaction_Service appends the ledger legs of an unprocessed event, THE Transaction_Service SHALL insert the event's `transactionRef` into the Processed_Events table within the same local transaction as the ledger append.
3. IF an Event_Outbox row is read in more than one poll pass, THEN THE Transaction_Service SHALL hold exactly two LedgerEntry legs for that `transactionRef` across all passes.
4. WHEN a client requests statement history for an account, THE Transaction_Service SHALL return the LedgerEntry legs ordered by `bookedAt` descending, then by `id` descending.
5. THE Transaction_Service SHALL use `transactionRef` as the primary key of the Processed_Events table so that inserting an already-present `transactionRef` is rejected.

### Requirement 8: Per-Service Database Isolation

**User Story:** As a platform architect, I want each consumer to only read the shared outbox and never write another service's business tables, so that service schemas stay isolated and independently owned.

#### Acceptance Criteria

1. THE Account_Service SHALL access the Event_Outbox only through read-only SELECT operations and SHALL NOT INSERT, UPDATE, or DELETE Event_Outbox rows.
2. THE Transaction_Service SHALL access the Event_Outbox only through read-only SELECT operations and SHALL NOT INSERT, UPDATE, or DELETE Event_Outbox rows.
3. THE Account_Service SHALL write only to tables within the `sandbox_account` schema.
4. THE Transaction_Service SHALL write only to tables within the `sandbox_transaction` schema.
5. THE Payment_Service SHALL be the only module that writes to the Event_Outbox table.
6. WHEN a consumer applies the effect of a PaymentCompletedEvent, THE consumer SHALL derive every required value solely from the fields contained in the event payload, without reading another service's business tables.

### Requirement 9: Shared Outbox Location Across Databases

**User Story:** As a platform operator running all services against one MySQL server with separate per-service databases, I want each consumer to read the single shared outbox table owned by payment-service, so that completed transfers actually propagate instead of each consumer reading an empty local copy.

#### Acceptance Criteria

1. THE Payment_Service SHALL own the single `event_outbox` table within its own database (the Outbox_Schema) and SHALL be the only writer of that table.
2. THE Account_Service and THE Transaction_Service SHALL read the shared `event_outbox` table located in the Outbox_Schema, and SHALL NOT rely on a copy of `event_outbox` in their own database.
3. THE Account_Service and THE Transaction_Service SHALL resolve the Outbox_Schema from a configuration property with a default of `sandbox_payment`, so that the schema name can be overridden per environment and per test.
4. WHEN a consumer reads the shared `event_outbox`, THE consumer SHALL use a read-only SELECT and SHALL NOT INSERT, UPDATE, or DELETE rows in the Outbox_Schema.
5. WHERE the Outbox_Schema is configured, THE consumer SHALL continue to write its own idempotency and business tables only within its own database, preserving per-service write isolation.
6. WHEN the test environment runs on a single in-memory database where separate databases are not provisioned, THE consumer SHALL resolve the Outbox_Schema to the test database so that outbox reads succeed without a cross-database copy table.
