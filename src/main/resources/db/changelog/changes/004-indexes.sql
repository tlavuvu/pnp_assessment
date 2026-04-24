--liquibase formatted sql

--changeset pnp:004-indexes
--comment: Indexes shaped to support concrete query paths:
--comment:   - ix_orders_status_created_at: report query filters by status
--comment:     and created_at half-open range — leftmost columns covered.
--comment:   - ix_order_item_product_id: report aggregation joins
--comment:     order_item to product on product_id.
--comment:   - ix_order_item_order_id: fetch lines for an order detail GET.
--comment:   - ix_orders_customer_reference: per-customer lookups.
--comment:   - ix_product_name: catalogue search/sort by name.
CREATE INDEX ix_product_name              ON product    (name);
CREATE INDEX ix_orders_status_created_at  ON orders     (status, created_at);
CREATE INDEX ix_orders_customer_reference ON orders     (customer_reference);
CREATE INDEX ix_order_item_order_id       ON order_item (order_id);
CREATE INDEX ix_order_item_product_id     ON order_item (product_id);
--rollback DROP INDEX IF EXISTS ix_order_item_product_id;
--rollback DROP INDEX IF EXISTS ix_order_item_order_id;
--rollback DROP INDEX IF EXISTS ix_orders_customer_reference;
--rollback DROP INDEX IF EXISTS ix_orders_status_created_at;
--rollback DROP INDEX IF EXISTS ix_product_name;
