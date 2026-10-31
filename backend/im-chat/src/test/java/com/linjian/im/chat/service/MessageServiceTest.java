package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.dto.SendResult;
import com.linjian.im.chat.entity.ImInbox;
import com.linjian.im.chat.entity.ImMessage;
import com.linjian.im.chat.entity.ImSession;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
 * 发送流程的分支覆盖。重点是三件容易写错的事：
 * <ol>
 *   <li>重发命中后<b>不能</b>再分配 seq（否则每次重发都烧一个号）</li>
 *   <li>重发返回的是<b>首次</b>的 seq，不是新分配的</li>
 *   <li>单聊与群聊的投递路径不同：一个逐个推，一个广播一次</li>
 * </ol>
 *
 * <p>W8 新增的两条断言值得单独说明：群聊路径<b>不应调用</b>
 * {@code presenceService.isOnline}——广播天然只到活着的连接，逐个判断 200 次
 * 正是这次改造要消灭的开销；单聊路径<b>要</b>推给发送者自己，否则他的第二台
 * 设备永远不知道这条消息。</p>
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
    private static final Long GROUP = 9002L;

    private final AtomicLong idSeq = new AtomicLong(500L);

    @BeforeEach
    void setUp() {
        when(idGenerator.nextId()).thenAnswer(inv -> idSeq.incrementAndGet());
    }

    private ImSession session(int type) {
        ImSession s = new ImSession();
        s.setId(type == ImSession.TYPE_GROUP ? GROUP : SESSION);
        s.setSessionType(type);
        return s;
    }

    @Test
    @DisplayName("重发命中：回放首次 seq，且不再分配新 seq")
    void duplicateReturnsOriginalSeq() {
        ImMessage existing = new ImMessage();
        existing.setId(777L);
        existing.setSessionId(SESSION);
        existing.setSeq(42L);
        existing.setSenderId(SENDER);
        existing.setClientMsgId("cid-1");
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
    @DisplayName("单聊：给对方写未读并推送，发送者也收到推送（多端）")
    void singleChatPushesBothSides() {
        when(contentValidator.requireValid("hello")).thenReturn("hello");
        when(messageMapper.selectByClientMsgId(SENDER, "cid-4")).thenReturn(null);
        when(seqService.next(SESSION)).thenReturn(7L);
        when(memberMapper.listUserIds(SESSION)).thenReturn(List.of(SENDER, PEER));
        when(sessionMapper.selectById(SESSION)).thenReturn(session(ImSession.TYPE_SINGLE));
        when(presenceService.isOnline(PEER)).thenReturn(true);
        when(presenceService.isOnline(SENDER)).thenReturn(true);

        SendResult result = messageService.send(SENDER, new SendCommand(SESSION, "cid-4", "hello"));

        assertThat(result.success()).isTrue();
        assertThat(result.duplicated()).isFalse();
        assertThat(result.seq()).isEqualTo(7L);

        verify(messageMapper).insert(any(ImMessage.class));
        verify(sessionMapper).touchLastMessage(eq(SESSION), eq(7L), any(LocalDateTime.class), eq("hello"));
        // 对方与发送者各推一次；发送者不写未读行（自己发的消息不该给自己亮红点）
        verify(delivery).deliverToUser(eq(PEER), any());
        verify(delivery).deliverToUser(eq(SENDER), any());
        verify(delivery, never()).deliverToSession(anyLong(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImInbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(inboxMapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).extracting(ImInbox::getUserId).containsExactly(PEER);
    }

    @Test
    @DisplayName("对方离线：照样写未读，但不推送")
    void offlinePeerGetsInboxOnly() {
        when(contentValidator.requireValid("hi")).thenReturn("hi");
        when(messageMapper.selectByClientMsgId(SENDER, "cid-5")).thenReturn(null);
        when(seqService.next(SESSION)).thenReturn(8L);
        when(memberMapper.listUserIds(SESSION)).thenReturn(List.of(SENDER, PEER));
        when(sessionMapper.selectById(SESSION)).thenReturn(session(ImSession.TYPE_SINGLE));
        when(presenceService.isOnline(PEER)).thenReturn(false);
        when(presenceService.isOnline(SENDER)).thenReturn(false);

        messageService.send(SENDER, new SendCommand(SESSION, "cid-5", "hi"));

        verify(inboxMapper).insertBatch(any());
        verify(delivery, never()).deliverToUser(anyLong(), any());
    }

    @Test
    @DisplayName("群聊：广播一次，不逐个推，也不查在线状态")
    void groupChatBroadcastsOnce() {
        when(contentValidator.requireValid("hi all")).thenReturn("hi all");
        when(messageMapper.selectByClientMsgId(SENDER, "cid-g")).thenReturn(null);
        when(seqService.next(GROUP)).thenReturn(9L);
        when(memberMapper.listUserIds(GROUP)).thenReturn(List.of(SENDER, 2001L, 2002L, 2003L));
        when(sessionMapper.selectById(GROUP)).thenReturn(session(ImSession.TYPE_GROUP));

        messageService.send(SENDER, new SendCommand(GROUP, "cid-g", "hi all"));

        verify(delivery).deliverToSession(eq(GROUP), any());
        verify(delivery, never()).deliverToUser(anyLong(), any());
        // 广播天然只到「连着且订阅了」的人，逐个查在线在这里是纯浪费
        verify(presenceService, never()).isOnline(anyLong());
    }

    @Test
    @DisplayName("200 人群：199 行未读走一条批量 INSERT，不是 199 次单插")
    void groupInboxWrittenInOneBatch() {
        List<Long> members = new ArrayList<>();
        members.add(SENDER);
        for (long i = 0; i < 199; i++) {
            members.add(5000L + i);
        }
        when(contentValidator.requireValid("broadcast")).thenReturn("broadcast");
        when(messageMapper.selectByClientMsgId(SENDER, "cid-200")).thenReturn(null);
        when(seqService.next(GROUP)).thenReturn(10L);
        when(memberMapper.listUserIds(GROUP)).thenReturn(members);
        when(sessionMapper.selectById(GROUP)).thenReturn(session(ImSession.TYPE_GROUP));

        messageService.send(SENDER, new SendCommand(GROUP, "cid-200", "broadcast"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImInbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(inboxMapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(199);
        assertThat(captor.getValue()).extracting(ImInbox::getUserId).doesNotContain(SENDER);
        // 未读行的 seq 必须与被推送的消息一致，否则「已读清零」会删错范围
        assertThat(captor.getValue()).allMatch(row -> row.getSeq().equals(10L));
    }
}
