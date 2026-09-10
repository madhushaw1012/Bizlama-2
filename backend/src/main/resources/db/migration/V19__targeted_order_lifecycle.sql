-- Align the product-facing lifecycle with QUEUED -> PREPARING -> DONE.
-- Existing READY/COMPLETED rows are terminal and therefore migrate to DONE.

ALTER TABLE customer_orders
  DROP CONSTRAINT ck_customer_orders_status;

ALTER TABLE order_status_history
  DROP CONSTRAINT ck_order_status_history_status;

UPDATE customer_orders
SET status = 'DONE'
WHERE status IN ('READY', 'COMPLETED');

UPDATE order_status_history
SET status = 'DONE'
WHERE status IN ('READY', 'COMPLETED');

ALTER TABLE customer_orders
  ADD CONSTRAINT ck_customer_orders_status
  CHECK (status IN ('QUEUED', 'PREPARING', 'DONE', 'CANCELLED'));

ALTER TABLE order_status_history
  ADD CONSTRAINT ck_order_status_history_status
  CHECK (status IN ('QUEUED', 'PREPARING', 'DONE', 'CANCELLED'));
