CREATE TABLE IF NOT EXISTS t_call_record (
  id BIGINT NOT NULL PRIMARY KEY,
  call_id BIGINT NOT NULL,
  single_chat_id BIGINT NULL,
  caller_user_id BIGINT NOT NULL,
  callee_user_id BIGINT NOT NULL,
  status TINYINT NOT NULL COMMENT '1=RINGING,2=ACCEPTED,3=REJECTED,4=CANCELED,5=ENDED,6=MISSED,7=FAILED',
  fail_reason VARCHAR(64) NULL,
  started_at DATETIME(3) NOT NULL,
  accepted_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  duration_seconds INT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_call_record_call_id (call_id),
  KEY idx_call_record_caller_id (caller_user_id, id),
  KEY idx_call_record_callee_id (callee_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

