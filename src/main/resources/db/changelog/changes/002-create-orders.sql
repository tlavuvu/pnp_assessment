--liquibase formatted sql

--changeset pnp:002-create-orders
--comment: Order header. Named 'orders' (plural) because ORDER is a SQL
--comment: reserved word. status is constrained at DB level so an app bug
--comment: cannot leak an unknown enum value into history. total is
--comment: persisted (= SUM(order_item.line_total)) so reports avoid an
--comment: extra join — ADR-014 documents the drift trade-off.
CREATE TABLE orders (
    id                  BIGSERIAL     PRIMARY KEY,
    customer_reference  VARCHAR(64)   NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'CONFIRMED',
    total               NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_orders_customer_ref_not_blank CHECK (length(trim(customer_reference)) >= 1),
    CONSTRAINT chk_orders_status_valid           CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_orders_total_non_neg          CHECK (total >= 0)
);
--rollback DROP TABLE orders;
