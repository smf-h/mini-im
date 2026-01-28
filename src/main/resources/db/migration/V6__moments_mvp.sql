-- Moments (朋友圈) MVP

CREATE TABLE IF NOT EXISTS t_moment_post (
  id BIGINT NOT NULL PRIMARY KEY,
  author_id BIGINT NOT NULL,
  content VARCHAR(512) NOT NULL,
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_mp_author_id_id (author_id, id),
  KEY idx_mp_id (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_moment_like (
  id BIGINT NOT NULL PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_ml_post_user (post_id, user_id),
  KEY idx_ml_user_id (user_id),
  KEY idx_ml_post_id (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_moment_comment (
  id BIGINT NOT NULL PRIMARY KEY,
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  content VARCHAR(512) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_mc_post_id_id (post_id, id),
  KEY idx_mc_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

