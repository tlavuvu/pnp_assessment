--liquibase formatted sql

--changeset pnp:001-create-product
--comment: Catalogue table. Stock and price live here; price snapshots into
--comment: order_item.unit_price at order time so price changes never
--comment: rewrite history.
CREATE TABLE product (
    id           BIGSERIAL     PRIMARY KEY,
    name         VARCHAR(120)  NOT NULL,
    description  VARCHAR(500),
    price        NUMERIC(12,2) NOT NULL,
    stock        INTEGER       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_name_not_blank CHECK (length(trim(name)) >= 1),
    CONSTRAINT chk_product_price_non_neg  CHECK (price >= 0),
    CONSTRAINT chk_product_stock_non_neg  CHECK (stock >= 0)
);
--rollback DROP TABLE product;
