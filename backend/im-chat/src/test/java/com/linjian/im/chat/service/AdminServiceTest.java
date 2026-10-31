package com.linjian.im.chat.service;

import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImMessageMapper;
import com.linjian.im.chat.mapper.ImSessionMapper;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端的业务规则单测。
 *
 * <p>只测「规则」，不测「管道」：分页参数怎么夹、SQL 怎么写，由
 * {@code W9AdminIT} 在真实库上验证；这里锁的是两条会被误改的规则——
 * 不能禁用自己、不能禁用管理员。这两条一旦失效，不会报错、不会崩，
 * 只会让最后一次误操作把所有人挡在后台外面。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    private static final long OPERATOR = 9001L;

    @Mock
    private ImUserMapper userMapper;
    @Mock
    private ImSessionMapper sessionMapper;
    @Mock
    private ImMessageMapper messageMapper;
    @Mock
    private PresenceService presenceService;
    @Mock
    private AccountKicker accountKicker;

    @InjectMocks
    private AdminService adminService;

    @Test
    @DisplayName("不能禁用自己——点错一次就把自己锁在门外")
    void cannotDisableSelf() {
        assertThatThrownBy(() -> adminService.updateStatus(OPERATOR, OPERATOR, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁用");
        // 关键：一个写操作都没发生。这里若不验证，即便抛了异常也可能已经改过库。
        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("不能禁用管理员——两个管理员互相禁用，后台就没人进得去")
    void cannotDisableAnotherAdmin() {
        ImUser admin = user(2L, "root2", 1, 1);
        when(userMapper.selectById(2L)).thenReturn(admin);

        assertThatThrownBy(() -> adminService.updateStatus(OPERATOR, 2L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ADMIN_OPERATION_REJECTED);
        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("status 只接受 0/1，其他值不写库")
    void statusMustBeBinary() {
        assertThatThrownBy(() -> adminService.updateStatus(OPERATOR, 3L, 7))
                .isInstanceOf(BusinessException.class);
        verify(userMapper, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    @DisplayName("目标用户不存在时返回 4110，而不是静默成功")
    void unknownTargetIsExplicitError() {
        when(userMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> adminService.updateStatus(OPERATOR, 404L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("正常禁用：写库、踢线、返回更新后的行")
    void disableNormalUserWritesAndReturnsRow() {
        ImUser target = user(3L, "alice", 1, 0);
        when(userMapper.selectById(3L)).thenReturn(target);
        when(userMapper.updateStatus(3L, 0)).thenReturn(1);

        var view = adminService.updateStatus(OPERATOR, 3L, 0);

        verify(userMapper).updateStatus(3L, 0);
        // 踢线是禁用的一部分，不是可选优化：漏掉它的表现是「界面显示已禁用，
        // 那个人还在收消息」，而这种错误不会有任何报错。
        verify(accountKicker).kick(3L);
        assertThat(view.id()).isEqualTo(3L);
        assertThat(view.status()).isZero();
    }

    @Test
    @DisplayName("恢复账号不踢线——那人本来就连不上，没理由掐掉他侥幸活着的连接")
    void enablingDoesNotKick() {
        ImUser target = user(4L, "bob", 0, 0);
        when(userMapper.selectById(4L)).thenReturn(target);
        when(userMapper.updateStatus(4L, 1)).thenReturn(1);

        adminService.updateStatus(OPERATOR, 4L, 1);

        verify(userMapper).updateStatus(4L, 1);
        verify(accountKicker, never()).kick(anyLong());
    }

    private static ImUser user(long id, String username, int status, int role) {
        ImUser u = new ImUser();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(username);
        u.setStatus(status);
        u.setRole(role);
        return u;
    }
}
