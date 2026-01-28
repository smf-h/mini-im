-- Introduce per-conversation msg_seq and migrate cursors from msg_id -> msg_seq
-- NOTE: Avoid window functions here. On large tables it can be extremely slow and blocks startup.
-- We only need a stable, per-conversation ordering/cursor. Using existing `id` as `msg_seq` is monotonic
-- and unique within a conversation, and new messages will continue from `next_msg_seq`.

-- 1) Schema changes (idempotent: tolerate re-run after partial apply)

-- MySQL has no "ADD INDEX IF NOT EXISTS". Use dynamic SQL to make DDL idempotent.
-- Pattern: IF exists -> SELECT 1; ELSE -> execute DDL.

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_message'
        AND COLUMN_NAME = 'msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_message ADD COLUMN msg_seq BIGINT NULL COMMENT ''conversation-local sequence (SSOT for ordering/cursor)'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_single_chat'
        AND COLUMN_NAME = 'next_msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_single_chat ADD COLUMN next_msg_seq BIGINT NOT NULL DEFAULT 0 COMMENT ''allocator cursor for msg_seq'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_group'
        AND COLUMN_NAME = 'next_msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_group ADD COLUMN next_msg_seq BIGINT NOT NULL DEFAULT 0 COMMENT ''allocator cursor for msg_seq'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_single_chat_member'
        AND COLUMN_NAME = 'last_delivered_msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_single_chat_member ADD COLUMN last_delivered_msg_seq BIGINT NULL COMMENT ''delivered cursor by msg_seq'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_single_chat_member'
        AND COLUMN_NAME = 'last_read_msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_single_chat_member ADD COLUMN last_read_msg_seq BIGINT NULL COMMENT ''read cursor by msg_seq'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_group_member'
        AND COLUMN_NAME = 'last_delivered_msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_group_member ADD COLUMN last_delivered_msg_seq BIGINT NULL COMMENT ''delivered cursor by msg_seq'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_group_member'
        AND COLUMN_NAME = 'last_read_msg_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_group_member ADD COLUMN last_read_msg_seq BIGINT NULL COMMENT ''read cursor by msg_seq'''
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) Backfill t_message.msg_seq

-- Use `id` as a stable ordering key, fast to backfill.
UPDATE t_message
SET msg_seq = id
WHERE msg_seq IS NULL;

-- 3) Backfill next_msg_seq (max msg_seq per conversation)

UPDATE t_single_chat sc
JOIN (
  SELECT single_chat_id, MAX(msg_seq) AS max_seq
  FROM t_message
  WHERE chat_type = 1
    AND single_chat_id IS NOT NULL
  GROUP BY single_chat_id
) x ON sc.id = x.single_chat_id
SET sc.next_msg_seq = x.max_seq;

UPDATE t_group g
JOIN (
  SELECT group_id, MAX(msg_seq) AS max_seq
  FROM t_message
  WHERE chat_type = 2
    AND group_id IS NOT NULL
  GROUP BY group_id
) x ON g.id = x.group_id
SET g.next_msg_seq = x.max_seq;

-- 4) Backfill member cursors (msg_id -> msg_seq mapping)

UPDATE t_single_chat_member scm
JOIN t_message m
  ON m.id = scm.last_delivered_msg_id
SET scm.last_delivered_msg_seq = m.msg_seq
WHERE scm.last_delivered_msg_id IS NOT NULL;

UPDATE t_single_chat_member scm
JOIN t_message m
  ON m.id = scm.last_read_msg_id
SET scm.last_read_msg_seq = m.msg_seq
WHERE scm.last_read_msg_id IS NOT NULL;

UPDATE t_group_member gm
JOIN t_message m
  ON m.id = gm.last_delivered_msg_id
SET gm.last_delivered_msg_seq = m.msg_seq
WHERE gm.last_delivered_msg_id IS NOT NULL;

UPDATE t_group_member gm
JOIN t_message m
  ON m.id = gm.last_read_msg_id
SET gm.last_read_msg_seq = m.msg_seq
WHERE gm.last_read_msg_id IS NOT NULL;

-- 5) Constraints & indexes (add AFTER backfill to avoid UNIQUE conflicts on default values)

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_message'
        AND COLUMN_NAME = 'msg_seq'
    ),
    'ALTER TABLE t_message MODIFY COLUMN msg_seq BIGINT NOT NULL COMMENT ''conversation-local sequence (SSOT for ordering/cursor)''',
    'SELECT 1'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_message'
        AND INDEX_NAME = 'idx_msg_single_id_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_message ADD KEY idx_msg_single_id_seq (single_chat_id, msg_seq)'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_message'
        AND INDEX_NAME = 'idx_msg_group_id_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_message ADD KEY idx_msg_group_id_seq (group_id, msg_seq)'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_message'
        AND INDEX_NAME = 'uk_single_chat_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_message ADD UNIQUE KEY uk_single_chat_seq (single_chat_id, msg_seq)'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @stmt := (
  SELECT IF(
    EXISTS(
      SELECT 1
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 't_message'
        AND INDEX_NAME = 'uk_group_seq'
    ),
    'SELECT 1',
    'ALTER TABLE t_message ADD UNIQUE KEY uk_group_seq (group_id, msg_seq)'
  )
);
PREPARE stmt FROM @stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
