package com.miniim.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miniim.domain.entity.MessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MessageMapper extends BaseMapper<MessageEntity> {

    @Select("""
            <script>
            select m.*
            from t_message m
            join (
              select single_chat_id, max(msg_seq) as max_seq
              from t_message
              where chat_type = 1
                and single_chat_id in
              <foreach collection="singleChatIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
              group by single_chat_id
            ) x
              on m.single_chat_id = x.single_chat_id
             and m.msg_seq = x.max_seq
            </script>
            """)
    List<MessageEntity> selectLastMessagesBySingleChatIds(@Param("singleChatIds") List<Long> singleChatIds);

    @Select("""
            <script>
            select m.*
            from t_message m
            join (
              select group_id, max(msg_seq) as max_seq
              from t_message
              where chat_type = 2
                and group_id in
              <foreach collection="groupIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
              group by group_id
            ) x
              on m.group_id = x.group_id
             and m.msg_seq = x.max_seq
            </script>
            """)
    List<MessageEntity> selectLastMessagesByGroupIds(@Param("groupIds") List<Long> groupIds);

    @Select("""
            <script>
            select m.*
            from t_message m
            join t_single_chat_member scm
              on scm.single_chat_id = m.single_chat_id
             and scm.user_id = #{userId}
            where m.chat_type = 1
              and m.to_user_id = #{userId}
              and m.msg_seq &gt; ifnull(scm.last_delivered_msg_seq, 0)
            order by m.msg_seq asc
            limit #{limit}
            </script>
            """)
    List<MessageEntity> selectPendingSingleChatMessagesForUser(@Param("userId") long userId, @Param("limit") int limit);

    @Select("""
            <script>
            select m.*
            from t_message m
            join t_group_member gm
              on gm.group_id = m.group_id
             and gm.user_id = #{userId}
            where m.chat_type = 2
              and m.msg_seq &gt; ifnull(gm.last_delivered_msg_seq, 0)
              and (m.from_user_id is null or m.from_user_id != #{userId})
            order by m.msg_seq asc
            limit #{limit}
            </script>
            """)
    List<MessageEntity> selectPendingGroupMessagesForUser(@Param("userId") long userId, @Param("limit") int limit);

    @Select("""
            <script>
            select m.single_chat_id as singleChatId, count(*) as unreadCount
            from t_message m
            join t_single_chat_member scm
              on scm.single_chat_id = m.single_chat_id
             and scm.user_id = #{userId}
            where m.chat_type = 1
              and m.to_user_id = #{userId}
              and m.single_chat_id in
              <foreach collection="singleChatIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
              and m.msg_seq &gt; ifnull(scm.last_read_msg_seq, 0)
            group by m.single_chat_id
            </script>
            """)
    List<java.util.Map<String, Object>> selectUnreadCountsForUser(@Param("singleChatIds") List<Long> singleChatIds,
                                                                  @Param("userId") long userId);

    @Select("""
            <script>
            select m.group_id as groupId, count(*) as unreadCount
            from t_message m
            join t_group_member gm
              on gm.group_id = m.group_id
             and gm.user_id = #{userId}
            where m.chat_type = 2
              and m.group_id in
              <foreach collection="groupIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
              and m.msg_seq &gt; ifnull(gm.last_read_msg_seq, 0)
              and (m.from_user_id is null or m.from_user_id != #{userId})
            group by m.group_id
            </script>
            """)
    List<java.util.Map<String, Object>> selectGroupUnreadCountsForUser(@Param("groupIds") List<Long> groupIds,
                                                                       @Param("userId") long userId);
}
