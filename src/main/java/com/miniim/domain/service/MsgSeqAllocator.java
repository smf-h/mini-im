package com.miniim.domain.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 会话内 msg_seq 分配器（并发安全）。
 *
 * <p>使用 MySQL 的 LAST_INSERT_ID 技巧：在同一连接内 UPDATE 并取回本次递增后的值。</p>
 */
@Component
public class MsgSeqAllocator {

    private final JdbcTemplate jdbcTemplate;

    public MsgSeqAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long allocateNextSingleChatSeq(long singleChatId) {
        return allocateNextSeq("t_single_chat", singleChatId);
    }

    public long allocateNextGroupSeq(long groupId) {
        return allocateNextSeq("t_group", groupId);
    }

    private long allocateNextSeq(String table, long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (!("t_single_chat".equals(table) || "t_group".equals(table))) {
            throw new IllegalArgumentException("unsupported table");
        }

        String updateSql = "update " + table + " set next_msg_seq = LAST_INSERT_ID(next_msg_seq + 1) where id = ?";
        String selectSql = "select LAST_INSERT_ID()";

        // 必须保证 UPDATE 与 SELECT 在同一连接上执行。
        Long out = jdbcTemplate.execute(updateSql, (PreparedStatementCallback<Long>) ps -> {
            ps.setLong(1, id);
            int updated = ps.executeUpdate();
            if (updated <= 0) {
                throw new IllegalStateException("allocate msg_seq failed: no row updated");
            }
            try (PreparedStatement s = ps.getConnection().prepareStatement(selectSql);
                 ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("allocate msg_seq failed: empty result");
                }
                long v = rs.getLong(1);
                return v > 0 ? v : null;
            }
        });

        if (out == null || out <= 0) {
            throw new IllegalStateException("allocate msg_seq failed");
        }
        return out;
    }
}

