package com.linjian.im.chat.service;

import com.linjian.im.chat.entity.ImSession;
import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImInboxMapper;
import com.linjian.im.chat.mapper.ImSessionMapper;
import com.linjian.im.chat.mapper.ImSessionMemberMapper;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话懒创建的边界。
 *
 * <p>最要紧的一条是 bizKey 的<b>顺序无关性</b>：A 发起和 B 发起必须拼出同一个字符串，
 * 否则唯一索引拦不住重复会话，两个人会各自看到一条只属于自己的对话。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

    @Mock
    private ImSessionMapper sessionMapper;
    @Mock
    private ImSessionMemberMapper memberMapper;
    @Mock
    private ImInboxMapper inboxMapper;
    @Mock
    private ImUserMapper userMapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private PresenceService presenceService;

    @InjectMocks
    private SessionService sessionService;

    private ImUser enabledUser(long id, String nickname) {
        ImUser u = new ImUser();
        u.setId(id);
        u.setNickname(nickname);
        u.setStatus(1);
        return u;
    }

    @Test
    @DisplayName("bizKey 与发起方无关：大 id 在前、小 id 在后")
    void bizKeyOrderIndependent() {
        // 发起人是 200，对方是 100：服务端查的是「对方」，所以 selectById 的参数是 100
        when(userMapper.selectById(100L)).thenReturn(enabledUser(100L, "甲"));
        when(idGenerator.nextId()).thenReturn(999L);

        ImSession saved = new ImSession();
        saved.setId(999L);
        // 第一次查不到（走创建），插入后回查拿到 saved
        when(sessionMapper.selectByBizKey("100:200")).thenReturn(null, saved);

        ArgumentCaptor<ImSession> captor = ArgumentCaptor.forClass(ImSession.class);
        Long sessionId = sessionService.openSingle(200L, 100L);

        assertThat(sessionId).isEqualTo(999L);
        verify(sessionMapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getBizKey()).isEqualTo("100:200");
        assertThat(captor.getValue().getSessionType()).isEqualTo(ImSession.TYPE_SINGLE);
    }

    @Test
    @DisplayName("不能和自己建会话")
    void selfSessionRejected() {
        assertThatThrownBy(() -> sessionService.openSingle(100L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能和自己");
        verify(sessionMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("对方不存在或已禁用时拒绝")
    void peerNotFoundRejected() {
        when(userMapper.selectById(200L)).thenReturn(null);
        assertThatThrownBy(() -> sessionService.openSingle(100L, 200L))
                .isInstanceOf(BusinessException.class);

        ImUser disabled = enabledUser(201L, "丙");
        disabled.setStatus(0);
        when(userMapper.selectById(201L)).thenReturn(disabled);
        assertThatThrownBy(() -> sessionService.openSingle(100L, 201L))
                .isInstanceOf(BusinessException.class);

        verify(sessionMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("会话已存在时不重复创建，直接返回原 id")
    void existingSessionReused() {
        when(userMapper.selectById(200L)).thenReturn(enabledUser(200L, "乙"));
        ImSession existing = new ImSession();
        existing.setId(888L);
        existing.setBizKey("100:200");
        when(sessionMapper.selectByBizKey("100:200")).thenReturn(existing);

        Long sessionId = sessionService.openSingle(100L, 200L);

        assertThat(sessionId).isEqualTo(888L);
        verify(sessionMapper, never()).insertIgnore(any());
        // 幂等补齐两个成员
        verify(memberMapper).insertIgnore(888L, 100L);
        verify(memberMapper).insertIgnore(888L, 200L);
    }

    @Test
    @DisplayName("不是成员：会话存在时抛「不在该会话中」，不存在时抛「会话不存在」")
    void assertMemberDistinguishesTwoErrors() {
        when(memberMapper.exists(1L, 100L)).thenReturn(0);
        when(sessionMapper.selectById(1L)).thenReturn(new ImSession());
        assertThatThrownBy(() -> sessionService.assertMember(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在该会话中");

        when(memberMapper.exists(2L, 100L)).thenReturn(0);
        when(sessionMapper.selectById(2L)).thenReturn(null);
        assertThatThrownBy(() -> sessionService.assertMember(2L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("成员校验通过时不额外查会话表")
    void assertMemberFastPath() {
        when(memberMapper.exists(1L, 100L)).thenReturn(1);
        sessionService.assertMember(1L, 100L);
        verify(sessionMapper, never()).selectById(anyLong());
    }
}
