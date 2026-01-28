-- Flyway migration: sparse mentions for group important notifications (@me / reply)

CREATE TABLE IF NOT EXISTS t_message_mention (
  id BIGINT NOT NULL PRIMARY KEY,
  group_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  mentioned_user_id BIGINT NOT NULL,
  mention_type TINYINT NOT NULL COMMENT '1=MENTION,2=REPLY,3=AT_ALL',
  created_at DATETIME(3) NOT NULL,
  KEY idx_mm_user_group_msg (mentioned_user_id, group_id, message_id),
  KEY idx_mm_group_msg (group_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

