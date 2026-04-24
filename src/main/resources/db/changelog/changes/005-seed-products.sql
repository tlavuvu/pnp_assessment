--liquibase formatted sql

--changeset pnp:005-seed-products context:dev,test
--comment: Seed data tagged with context dev,test so a future prod profile
--comment: can opt out via spring.liquibase.contexts=prod. Mix of price
--comment: tiers and stock levels gives reports + pagination something
--comment: meaningful to chew on.
INSERT INTO product (name, description, price, stock) VALUES
    ('Lavazza Qualita Rossa 1kg',   'Medium roast blend',                189.99,  50),
    ('Illy Classico 250g',          'Smooth medium roast espresso',       99.50,  30),
    ('Jacobs Kronung 500g',         'European classic roast',            129.00,  20),
    ('Nespresso Original Pods x10', 'Variety pack of 10 pods',            79.99, 100),
    ('Moka Pot 6-cup',              'Stovetop espresso maker',           449.00,   8),
    ('Milk Frother',                'Handheld electric frother',         299.00,   5),
    ('Coffee Grinder Manual',       'Conical burr manual grinder',       349.00,  12),
    ('V60 Dripper',                 'Plastic 02-size pour-over dripper', 159.00,  40),
    ('Filter Papers x100',          'Tabbed bleached paper filters',      49.99, 200),
    ('Kettle Gooseneck',            'Variable-temperature gooseneck',    899.00,   3);
--rollback DELETE FROM product WHERE name IN (
--rollback     'Lavazza Qualita Rossa 1kg', 'Illy Classico 250g', 'Jacobs Kronung 500g',
--rollback     'Nespresso Original Pods x10', 'Moka Pot 6-cup', 'Milk Frother',
--rollback     'Coffee Grinder Manual', 'V60 Dripper', 'Filter Papers x100', 'Kettle Gooseneck'
--rollback );
