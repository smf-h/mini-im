-- Flyway migration: group speak mute (forbid sending group chat messages)

ALTER TABLE t_group_member
  ADD COLUMN speak_mute_until DATETIME(3) NULL AFTER mute_until;

