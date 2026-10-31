package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.dto.SendResult;
import com.linjian.im.chat.entity.ImMessage;
import com.linjian.im.chat.mapper.ImInboxMapper;
import com.linjian.im.chat.mapper.ImMessageMapper;
import com.linjian.im.chat.mapper.ImSessionMapper;
import com.linjian.im.chat.mapper.ImSessionMemberMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发送流程的分支覆盖。重点验证三件容易写错的事：
 * <ol>
 *   <li>重发命中后<b>不能</b>再分配 seq（否则每次重发都烧一个号）</li>
 *   <li>重发返回的是<b>首次</b>的 seq，不是新分配的</li>
 *   <li>校验顺序：内容非法时不该走到查重与分配 seq</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceTest {

    @Mock
    private ImMessageMapper messageMapper;
    @Mock
    private ImSessionMapper sessionMapper;
    @Mock
    private ImSessionMemberMapper memberMapper;
    @Mock
    private ImInboxMapper inboxMapper;
    @Mock
    private SeqService seqService;
    @Mock
    private ContentValidator contentValidator;
    @Mock
    private PresenceService presenceService;
    @Mock
    private MessageDelivery delivery;
    @Mock
    private SessionService sessionService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    @InjectMocks
    private MessageService messageService;

    private static final Long SENDER = 1001L;
    private static final Long PEER = 1002L;
    private static final Long SESSION = 9001L;

    @BeforeEach
    void setUp() {
        when(idGenerator.nextId()).thenReturn(555L, 556L, 557L);
    }

    @Test
    @DisplayName("重发命中：回放首次 seq，且不再分配新 seq")
    void duplicateReturnsOriginalSeq() {
        ImMessage existing = new ImMessage();
        existing.setId(777L);
        existing.setSessionId(SESSION);
        existing.setSeq(42L);
        existing.setSenderId(SENDER);
        existing.setContent("hello");
        existing.setCreatedAt(LocalDateTime.now());
        when(messageMapper.selectByClientMsgId(SENDER, "cid-1")).thenReturn(existing);

        SendResult result = messageService.send(SENDER, new SendCommand(SESSION, "cid-1", "hello"));

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isTrue();
        assertThat(result.seq()).isEqualTo(42L);
        assertThat(result.messageId()).isEqualTo(777L);
        verify(seqService, never()).next(anyLong());
        verify(messageMapper, never()).insert(any());
    }

    @Test
    @DisplayName("clientMsgId 为空直接拒绝——它是幂等的唯一依据")
    void blankClientMsgIdRejected() {
        assertThatThrownBy(() -> messageService.send(SENDER, new SendCommand(SESSION, "  ", "hello")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("clientMsgId");
        verify(seqService, never()).next(anyLong());
    }

    @Test
    @DisplayName("内容非法时不查重、不分配 seq")
    void invalidContentStopsBeforeSeq() {
        when(contentValidator.requireValid(anyString()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CONTENT, "消息内容不能为空"));

        assertThatThrownBy(() -> messageService.send(SENDER, new SendCommand(SESSION, "cid-2", "   ")))
                .isInstanceOf(BusinessException.class);

        verify(messageMapper, never()).selectByClientMsgId(anyLong(), anyString());
        verify(seqService, never()).next(anyLong());
    }

    @Test
    @DisplayName("不是会话成员时被拒绝，且不占 seq")
    void notMemberRejected() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_IN_SESSION))
                .when(sessionService).assertMember(SESSION, SENDER);

        assertThatThrownBy(() -> messageService.send(SENDER, new SendCommand(SESSION, "cid-3", "hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_IN_SESSION);

        verify(seqService, never()).next(anyLong());
    }

    @Test
    @DisplayName("正常发送：分配 seq、落库、更新会话摘要、给对方写未读并推送")
    void happyPath() {
        when(contentValidator.requireValid("hello")).thenReturn("hello");
        when(messageMapper.selectByClientMsgId(SENDER, "cid-4")).thenReturn(null);
        when(seqService.next(SESSION)).thenReturn(7L);
        when(memberMapper.listUserIds(SESSION)).thenReturn(List.of(SENDER, PEER));
        when(presenceService.isOnline(PEER)).thenReturn(true);

        SendResult result = messageService.send(SENDER, new SendCommand(SESSION, "cid-4", "hello"));

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isFalse();
        assertThat(result.seq()).isEqualTo(7L);

        verify(messageMapper).insert(any(ImMessage.class));
        verify(sessionMapper).touchLastMessage(eq(SESSION), eq(7L), any(LocalDateTime.class), eq("hello"));
        // 对方：写一行未读 + 在线则推送
        verify(inboxMapper).insertIgnore(any());
        verify(delivery).deliverToUser(eq(PEER), any());
        // 发送者自己：不写未读、不推送（W7 不做多端同步）
        verify(delivery, never()).deliverToUser(eq(SENDER), any());
    }

    @Test
    @DisplayName("对方离线：写未读但不推送")
    void offlinePeerGetsInboxOnly() {
        when(contentValidator.requireValid("hi")).thenReturn("hi");
        when(messageMapper.selectByClientMsgId(SENDER, "cid-5")).thenReturn(null);
        when(seqService.next(SESSION)).thenReturn(8L);
        when(memberMapper.listUserIds(SESSION)).thenReturn(List.of(SENDER, PEER));
        when(presenceService.isOnline(PEER)).thenReturn(false);

        messageService.send(SENDER, new SendCommand(SESSION, "cid-5", "hi"));

        verify(inboxMapper).insertIgnore(any());
        verify(delivery, never()).deliverToUser(anyLong(), any());
    }
}
