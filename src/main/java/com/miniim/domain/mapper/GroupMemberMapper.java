package com.miniim.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miniim.domain.entity.GroupMemberEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface GroupMemberMapper extends BaseMapper<GroupMemberEntity> {

    @Update("""
            update t_group_member
            set last_delivered_msg_id = greatest(ifnull(last_delivered_msg_id, 0), #{msgId})
            where group_id = #{groupId} and user_id = #{userId}
            """)
    int markDelivered(@Param("groupId") long groupId, @Param("userId") long userId, @Param("msgId") long msgId);

    @Update("""
            update t_group_member
            set last_read_msg_id = greatest(ifnull(last_read_msg_id, 0), #{msgId}),
                last_delivered_msg_id = greatest(ifnull(last_delivered_msg_id, 0), #{msgId})
            where group_id = #{groupId} and user_id = #{userId}
            """)
    int markRead(@Param("groupId") long groupId, @Param("userId") long userId, @Param("msgId") long msgId);
}
