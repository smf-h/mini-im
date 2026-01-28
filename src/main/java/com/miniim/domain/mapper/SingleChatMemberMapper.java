package com.miniim.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miniim.domain.entity.SingleChatMemberEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SingleChatMemberMapper extends BaseMapper<SingleChatMemberEntity> {

    @org.apache.ibatis.annotations.Select("""
            <script>
            select *
            from t_single_chat_member
            where single_chat_id in
            <foreach collection="singleChatIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    java.util.List<SingleChatMemberEntity> selectBySingleChatIds(@Param("singleChatIds") java.util.List<Long> singleChatIds);

    @Update("""
            update t_single_chat_member
            set last_delivered_msg_id = greatest(ifnull(last_delivered_msg_id, 0), #{msgId})
            where single_chat_id = #{singleChatId} and user_id = #{userId}
            """)
    int markDelivered(@Param("singleChatId") long singleChatId, @Param("userId") long userId, @Param("msgId") long msgId);

    @Update("""
            update t_single_chat_member
            set last_read_msg_id = greatest(ifnull(last_read_msg_id, 0), #{msgId}),
                last_delivered_msg_id = greatest(ifnull(last_delivered_msg_id, 0), #{msgId})
            where single_chat_id = #{singleChatId} and user_id = #{userId}
            """)
    int markRead(@Param("singleChatId") long singleChatId, @Param("userId") long userId, @Param("msgId") long msgId);

    @Update("""
            update t_single_chat_member
            set last_delivered_msg_seq = greatest(ifnull(last_delivered_msg_seq, 0), #{msgSeq})
            where single_chat_id = #{singleChatId} and user_id = #{userId}
            """)
    int markDeliveredSeq(@Param("singleChatId") long singleChatId, @Param("userId") long userId, @Param("msgSeq") long msgSeq);

    @Update("""
            update t_single_chat_member
            set last_read_msg_seq = greatest(ifnull(last_read_msg_seq, 0), #{msgSeq}),
                last_delivered_msg_seq = greatest(ifnull(last_delivered_msg_seq, 0), #{msgSeq})
            where single_chat_id = #{singleChatId} and user_id = #{userId}
            """)
    int markReadSeq(@Param("singleChatId") long singleChatId, @Param("userId") long userId, @Param("msgSeq") long msgSeq);
}
