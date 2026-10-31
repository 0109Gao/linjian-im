package com.linjian.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linjian.im.chat.entity.ImUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImUserMapper extends BaseMapper<ImUser> {

    /**
     * 管理端的用户分页。与用户端的 {@code search} 有三点不同：
     * 包含已被禁用的账号（否则禁用后那个账号会从后台「消失」，无法再启用）、
     * 不排除自己、返回全部字段（前端要显示状态与角色）。
     *
     * <p>关键字走参数化的 {@code LIKE CONCAT}，不拼字符串：管理员输入框是
     * 唯一一个「内容由人随手指”的地方，这里是注入风险最高的一处。</p>
     */
    @Select("""
            <script>
            SELECT * FROM im_user
            <where>
                <if test="keyword != null and keyword != ''">
                    (username LIKE CONCAT('%', #{keyword}, '%')
                     OR nickname LIKE CONCAT('%', #{keyword}, '%'))
                </if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ImUser> selectPageForAdmin(@Param("keyword") String keyword,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM im_user
            <where>
                <if test="keyword != null and keyword != ''">
                    (username LIKE CONCAT('%', #{keyword}, '%')
                     OR nickname LIKE CONCAT('%', #{keyword}, '%'))
                </if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("keyword") String keyword);

    @Update("UPDATE im_user SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") int status);

    @Select("SELECT COUNT(*) FROM im_user WHERE role = 1")
    long countAdmins();
}
