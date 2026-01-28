package com.miniim.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.miniim.domain.entity.GroupEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface GroupMapper extends BaseMapper<GroupEntity> {

    @Select("""
            <script>
            select g.*
            from t_group g
            join t_group_member gm
              on gm.group_id = g.id
             and gm.user_id = #{userId}
            <if test="lastUpdatedAt != null and lastId != null">
            where (g.updated_at &lt; #{lastUpdatedAt}
               or (g.updated_at = #{lastUpdatedAt} and g.id &lt; #{lastId}))
            </if>
            order by g.updated_at desc, g.id desc
            limit #{limit}
            </script>
            """)
    List<GroupEntity> selectGroupsForUserCursor(@Param("userId") long userId,
                                                @Param("limit") int limit,
                                                @Param("lastUpdatedAt") LocalDateTime lastUpdatedAt,
                                                @Param("lastId") Long lastId);
}
