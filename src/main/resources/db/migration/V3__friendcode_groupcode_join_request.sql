-- FriendCode / GroupCode / Group join requests

ALTER TABLE t_user
  ADD COLUMN friend_code VARCHAR(16) NULL,
  ADD COLUMN friend_code_updated_at DATETIME(3) NULL,
  ADD UNIQUE KEY uk_user_friend_code (friend_code);

ALTER TABLE t_group
  ADD COLUMN group_code VARCHAR(16) NULL,
  ADD COLUMN group_code_updated_at DATETIME(3) NULL,
  ADD UNIQUE KEY uk_group_group_code (group_code);

CREATE TABLE IF NOT EXISTS t_group_join_request (
  id BIGINT NOT NULL PRIMARY KEY,
  group_id BIGINT NOT NULL,
  from_user_id BIGINT NOT NULL,
  message VARCHAR(256) NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=PENDING,2=ACCEPTED,3=REJECTED,4=CANCELED',
  handled_by BIGINT NULL,
  handled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_gjr_group_status_id (group_id, status, id),
  KEY idx_gjr_from_status_id (from_user_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
