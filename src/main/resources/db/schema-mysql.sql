-- MySQL 8.x
-- Database: mini_im

CREATE TABLE IF NOT EXISTS t_user (
  id BIGINT NOT NULL PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NULL,
  nickname VARCHAR(64) NULL,
  avatar_url VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 单聊会话（两人唯一）
CREATE TABLE IF NOT EXISTS t_single_chat (
  id BIGINT NOT NULL PRIMARY KEY,
  user1_id BIGINT NOT NULL,
  user2_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_single_chat_pair (user1_id, user2_id),
  KEY idx_single_chat_user1 (user1_id),
  KEY idx_single_chat_user2 (user2_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 群
CREATE TABLE IF NOT EXISTS t_group (
  id BIGINT NOT NULL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  avatar_url VARCHAR(255) NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_group_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 群成员
CREATE TABLE IF NOT EXISTS t_group_member (
  id BIGINT NOT NULL PRIMARY KEY,
  group_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role TINYINT NOT NULL DEFAULT 3 COMMENT '1=owner,2=admin,3=member',
  join_at DATETIME(3) NOT NULL,
  mute_until DATETIME(3) NULL,
  last_delivered_msg_id BIGINT NULL,
  last_read_msg_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_group_member (group_id, user_id),
  KEY idx_group_member_user (user_id),
  KEY idx_group_member_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_message (
  id BIGINT NOT NULL PRIMARY KEY COMMENT 'msgId',
  chat_type TINYINT NOT NULL COMMENT '1=single,2=group',
  single_chat_id BIGINT NULL,
  group_id BIGINT NULL,
  from_user_id BIGINT NOT NULL,
  to_user_id BIGINT NULL COMMENT 'single chat target userId',
  msg_type TINYINT NOT NULL DEFAULT 1 COMMENT '1=text',
  content TEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0=SENT,1=SAVED,2=DELIVERED,3=READ,4=REVOKED',
  client_msg_id VARCHAR(64) NULL COMMENT 'client idempotency key',
  server_msg_id VARCHAR(64) NULL COMMENT 'server message id for client (usually equals id)',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_msg_single_id_id (single_chat_id, id),
  KEY idx_msg_group_id_id (group_id, id),
  KEY idx_msg_to_user (to_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_message_ack (
  id BIGINT NOT NULL PRIMARY KEY,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  device_id VARCHAR(64) NULL,
  ack_type TINYINT NOT NULL COMMENT '1=SAVED,2=READ',
  ack_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_msg_ack (message_id, user_id, device_id, ack_type),
  KEY idx_ack_user (user_id),
  KEY idx_ack_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 好友关系（一对好友只存一条记录：user1_id=min(A,B), user2_id=max(A,B)）
CREATE TABLE IF NOT EXISTS t_friend_relation (
  id BIGINT NOT NULL PRIMARY KEY,
  user1_id BIGINT NOT NULL,
  user2_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_friend_relation_pair (user1_id, user2_id),
  KEY idx_friend_relation_user1 (user1_id),
  KEY idx_friend_relation_user2 (user2_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 好友申请
CREATE TABLE IF NOT EXISTS t_friend_request (
  id BIGINT NOT NULL PRIMARY KEY,
  from_user_id BIGINT NOT NULL,
  to_user_id BIGINT NOT NULL,
  content VARCHAR(256) NULL COMMENT '申请验证信息',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=ACCEPTED,2=REJECTED,3=CANCELED,4=EXPIRED',
  handled_at DATETIME(3) NULL COMMENT '处理时间（同意/拒绝/取消/过期）',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_friend_request_to_status_time (to_user_id, status, created_at),
  KEY idx_friend_request_from_time (from_user_id, created_at),
  KEY idx_friend_request_from_to_status (from_user_id, to_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
