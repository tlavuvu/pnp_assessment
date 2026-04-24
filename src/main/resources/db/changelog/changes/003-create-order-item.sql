--liquibase formatted sql

--changeset pnp:003-create-order-item
--comment: Order line. ON DELETE CASCADE on order_id is convenience for
--comment: dev/test fixture cleanup; production must cancel orders, never
--comment: delete them. ON DELETE RESTRICT on product_id protects history:
--comment: a product that has been ordered cannot disappear from the
--comment: catalogue without an explicit migration. unit_price is the
--comment: snapshot at order time; line_total is enforced by CHECK to
--comment: equal quantity * unit_price so app-side bugs cannot drift.
CREATE TABLE order_item (
    id          BIGSERIAL     PRIMARY KEY,
    order_id    BIGINT        NOT NULL,
    product_id  BIGINT        NOT NULL,
    quantity    INTEGER       NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL,
    line_total  NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id)   REFERENCES orders(id)  ON DELETE CASCADE,
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_item_qty_pos        CHECK (quantity > 0),
    CONSTRAINT chk_order_item_unit_price_nn  CHECK (unit_price >= 0),
    CONSTRAINT chk_order_item_line_total_eq  CHECK (line_total = quantity * unit_price)
);
--rollback DROP TABLE order_item;
