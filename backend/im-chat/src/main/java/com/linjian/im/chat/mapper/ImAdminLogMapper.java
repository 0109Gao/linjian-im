package com.linjian.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linjian.im.chat.entity.ImAdminLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 管理员操作日志的读写。
 *
 * <p>只提供 insert（继承自 {@code BaseMapper}）、分页查询、以及一个「防刷屏」用的
 * 近期重复计数。不提供 update / delete：审计日志一旦可改就失去意义，
 * 需要清理时由运维按时间范围直接操作库，并且这个动作本身应当被记在库外。</p>
 */
@Mapper
public interface ImAdminLogMapper extends BaseMapper<ImAdminLog> {

    /**
     * 分页查询。{@code action} 与 {@code keyword} 都可选。
     *
     * <p>排序用 {@code created_at DESC, id DESC}：同一毫秒内的两条记录（例如脚本连着调用）
     * 靠 id 定序，否则翻页会出现「第 1 页和第 2 页重复同一条」这种现象。
     * 雪花 ID 是递增的，所以它天然是同毫秒内的顺序。</p>
     */
    @Select("""
            <script>
            SELECT * FROM im_admin_log
            <where>
              <if test="action != null and action != ''">
                AND action = #{action}
              </if>
              <if test="keyword != null and keyword != ''">
                AND (admin_name LIKE CONCAT('%', #{keyword}, '%')
                     OR detail LIKE CONCAT('%', #{keyword}, '%'))
              </if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ImAdminLog> selectPageForAdmin(@Param("action") String action,
                                        @Param("keyword") String keyword,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM im_admin_log
            <where>
              <if test="action != null and action != ''">
                AND action = #{action}
              </if>
              <if test="keyword != null and keyword != ''">
                AND (admin_name LIKE CONCAT('%', #{keyword}, '%')
                     OR detail LIKE CONCAT('%', #{keyword}, '%'))
              </if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("action") String action, @Param("keyword") String keyword);

    /**
     * 判断「同一管理员、同一动作、同一目标」在最近 N 秒内是否已经记过。
     *
     * <p>只用于「查看消息审计」这类高频只读动作：打开抽屉、上拉加载更多都会打一次接口，
     * 不去重的话一小时的翻页就能把日志刷成一片，真正该被看到的禁用记录被埋掉。
     * 写操作（禁用/启用）不走去重——每次都要留痕，哪怕连点两下。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM im_admin_log
             WHERE admin_id = #{adminId}
               AND action = #{action}
               AND (target_id = #{targetId} OR (target_id IS NULL AND #{targetId} IS NULL))
               AND created_at >= DATE_SUB(NOW(3), INTERVAL #{seconds} SECOND)
            """)
    long countRecent(@Param("adminId") Long adminId,
                     @Param("action") String action,
                     @Param("targetId") Long targetId,
                     @Param("seconds") int seconds);
}
