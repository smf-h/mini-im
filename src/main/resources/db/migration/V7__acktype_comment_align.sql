-- Align t_message_ack.ack_type comment with AckType enum
-- NOTE: Do NOT modify V1__init.sql to avoid Flyway checksum mismatch.

ALTER TABLE t_message_ack
  MODIFY COLUMN ack_type TINYINT NOT NULL COMMENT '1=SAVED,2=DELIVERED,3=READ';

