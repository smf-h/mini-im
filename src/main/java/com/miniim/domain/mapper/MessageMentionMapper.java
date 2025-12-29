package com.miniim.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miniim.domain.entity.MessageMentionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface MessageMentionMapper extends BaseMapper<MessageMentionEntity> {

    @Insert("""
            <script>
            insert into t_message_mention
              (id, group_id, message_id, mentioned_user_id, mention_type, created_at)
            values
            <foreach collection="list" item="x" separator=",">
              (#{x.id}, #{x.groupId}, #{x.messageId}, #{x.mentionedUserId}, #{x.mentionType}, #{x.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<MessageMentionEntity> list);

    @Select("""
            <script>
            select message_id
            from t_message_mention
            where mentioned_user_id = #{userId}
              and message_id in
              <foreach collection="messageIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    List<Long> selectMentionedMessageIdsForUser(@Param("userId") long userId, @Param("messageIds") List<Long> messageIds);

    @Select("""
            <script>
            select mm.group_id as groupId, count(*) as mentionUnreadCount
            from t_message_mention mm
            join t_group_member gm
              on gm.group_id = mm.group_id
             and gm.user_id = #{userId}
            where mm.mentioned_user_id = #{userId}
              and mm.group_id in
              <foreach collection="groupIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
              and mm.message_id &gt; ifnull(gm.last_read_msg_id, 0)
            group by mm.group_id
            </script>
            """)
    List<Map<String, Object>> selectMentionUnreadCountsForUser(@Param("groupIds") List<Long> groupIds,
                                                               @Param("userId") long userId);
}

