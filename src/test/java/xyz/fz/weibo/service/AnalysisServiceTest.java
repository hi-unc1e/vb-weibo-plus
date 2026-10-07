package xyz.fz.weibo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;
import xyz.fz.weibo.client.AiClient;
import xyz.fz.weibo.domain.AnalysisPageResult;
import xyz.fz.weibo.domain.AnalysisView;
import xyz.fz.weibo.entity.AnalysisEntity;
import xyz.fz.weibo.entity.MessageEntity;
import xyz.fz.weibo.repository.AnalysisRepository;
import xyz.fz.weibo.repository.MessageRepository;
import xyz.fz.weibo.service.exception.InvalidRequestException;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    private static final long GID = 100L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 7);

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private AnalysisRepository analysisRepository;
    @Mock
    private AiClient aiClient;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(messageRepository, analysisRepository, aiClient, jdbcTemplate);
    }

    @Test
    void analyze_prepares_calls_ai_and_saves() {
        // 覆盖 buildMessageText 三分支：sender null、text blank 有 typeName、text blank 无 typeName
        List<MessageEntity> messages = List.of(
                message(1L, null, "[图片]", null, 1_000L),
                message(2L, "张三", "[图片]", null, 2_000L),
                message(3L, "李四", "文本消息", "文本", 3_000L)
        );
        when(messageRepository.findPage(eq(GID), anyLong(), anyLong(), any(), any(),
                eq(MessageRepository.pageRequest(1, Integer.MAX_VALUE))))
                .thenReturn(new PageImpl<>(messages, MessageRepository.pageRequest(1, Integer.MAX_VALUE), messages.size()));
        when(aiClient.chat(any())).thenReturn("分析结果");
        when(analysisRepository.save(any())).thenAnswer(inv -> {
            AnalysisEntity entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 99L);
            return entity;
        });

        AnalysisView view = analysisService.analyze(GID, DATE, "分析一下");

        assertThat(view.id()).isEqualTo(99L);
        assertThat(view.gid()).isEqualTo(GID);
        assertThat(view.date()).isEqualTo("2026-08-07");
        assertThat(view.prompt()).isEqualTo("分析一下");
        assertThat(view.result()).isEqualTo("分析结果");
        assertThat(view.messageCount()).isEqualTo(3);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(promptCaptor.capture());
        String promptText = promptCaptor.getValue();
        assertThat(promptText).startsWith("分析一下\n\n以下是群聊消息：\n\n");
        assertThat(promptText).contains("[08:00] 未知: [图片]");
        assertThat(promptText).contains("[08:00] 张三: [图片]");
        assertThat(promptText).contains("[08:00] 李四: 文本消息");

        ArgumentCaptor<AnalysisEntity> entityCaptor = ArgumentCaptor.forClass(AnalysisEntity.class);
        verify(analysisRepository).save(entityCaptor.capture());
        AnalysisEntity savedEntity = entityCaptor.getValue();
        assertThat(savedEntity.getGid()).isEqualTo(GID);
        assertThat(savedEntity.getPrompt()).isEqualTo("分析一下");
        assertThat(savedEntity.getResult()).isEqualTo("分析结果");
        assertThat(savedEntity.getMessageCount()).isEqualTo(3);
    }

    @Test
    void analyze_throws_when_gid_invalid() {
        assertThatThrownBy(() -> analysisService.analyze(0, DATE, "分析一下"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("gid 必须大于 0");
        verify(messageRepository, never()).findPage(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void analyze_throws_when_date_null() {
        assertThatThrownBy(() -> analysisService.analyze(GID, null, "分析一下"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("date 不能为空");
    }

    @Test
    void analyze_throws_when_prompt_blank() {
        assertThatThrownBy(() -> analysisService.analyze(GID, DATE, "  "))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("prompt 不能为空");
    }

    @Test
    void analyze_throws_when_no_messages() {
        when(messageRepository.findPage(eq(GID), anyLong(), anyLong(), any(), any(),
                eq(MessageRepository.pageRequest(1, Integer.MAX_VALUE))))
                .thenReturn(new PageImpl<>(List.of(), MessageRepository.pageRequest(1, Integer.MAX_VALUE), 0));

        assertThatThrownBy(() -> analysisService.analyze(GID, DATE, "分析一下"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("该日期无本地消息");
    }

    @Test
    void analyzeStreaming_invokes_chat_stream_and_passes_deltas() {
        List<MessageEntity> messages = List.of(message(1L, "张三", "文本", "文本", 1_000L));
        when(messageRepository.findPage(eq(GID), anyLong(), anyLong(), any(), any(),
                eq(MessageRepository.pageRequest(1, Integer.MAX_VALUE))))
                .thenReturn(new PageImpl<>(messages, MessageRepository.pageRequest(1, Integer.MAX_VALUE), 1));
        // 桩 chatStream：模拟 AI 逐段回调
        when(aiClient.chatStream(any(), any())).thenAnswer(inv -> {
            Consumer<String> consumer = inv.getArgument(1);
            consumer.accept("第一");
            consumer.accept("段");
            return "第一段";
        });
        when(analysisRepository.save(any())).thenAnswer(inv -> {
            AnalysisEntity entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 99L);
            return entity;
        });

        List<String> deltas = new ArrayList<>();
        AnalysisView view = analysisService.analyzeStreaming(GID, DATE, "分析一下", deltas::add);

        assertThat(deltas).containsExactly("第一", "段");
        assertThat(view.result()).isEqualTo("第一段");
        assertThat(view.id()).isEqualTo(99L);
    }

    @Test
    void list_validates_page_and_size() {
        assertThatThrownBy(() -> analysisService.list(GID, 0, 20))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("page 必须大于等于 1");
        assertThatThrownBy(() -> analysisService.list(GID, 1, 0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("size 必须介于 1 和 100");
        assertThatThrownBy(() -> analysisService.list(GID, 1, 101))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("size 必须介于 1 和 100");
        verify(analysisRepository, never()).findPage(anyLong(), any());
    }

    @Test
    void list_throws_when_gid_invalid() {
        assertThatThrownBy(() -> analysisService.list(0, 1, 20))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("gid 必须大于 0");
    }

    @Test
    void list_returns_summaries_with_pagination() {
        AnalysisEntity e1 = new AnalysisEntity(GID, 0L, "提示一", "结果一", 5, 100L);
        ReflectionTestUtils.setField(e1, "id", 1L);
        AnalysisEntity e2 = new AnalysisEntity(GID, 0L, "提示二", "结果二", 3, 200L);
        ReflectionTestUtils.setField(e2, "id", 2L);
        Pageable pageable = AnalysisRepository.pageRequest(1, 20);
        when(analysisRepository.findPage(eq(GID), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(e2, e1), pageable, 2));

        AnalysisPageResult result = analysisService.list(GID, 1, 20);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).id()).isEqualTo(2L);
        assertThat(result.items().get(0).promptPreview()).isEqualTo("提示二");
        assertThat(result.items().get(0).messageCount()).isEqualTo(3);
    }

    @Test
    void list_summary_returns_full_prompt() {
        String longPrompt = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十超过";
        AnalysisEntity entity = new AnalysisEntity(GID, 0L, longPrompt, "结果", 1, 100L);
        ReflectionTestUtils.setField(entity, "id", 1L);
        when(analysisRepository.findPage(eq(GID), any()))
                .thenReturn(new PageImpl<>(List.of(entity), AnalysisRepository.pageRequest(1, 20), 1));

        AnalysisPageResult result = analysisService.list(GID, 1, 20);

        assertThat(result.items().get(0).promptPreview()).isEqualTo(longPrompt);
    }

    @Test
    void get_returns_view_when_found() {
        AnalysisEntity entity = new AnalysisEntity(GID, 0L, "提示", "结果", 1, 100L);
        ReflectionTestUtils.setField(entity, "id", 1L);
        when(analysisRepository.findById(1L)).thenReturn(Optional.of(entity));

        AnalysisView view = analysisService.get(1L);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.prompt()).isEqualTo("提示");
        assertThat(view.result()).isEqualTo("结果");
    }

    @Test
    void get_throws_when_not_found() {
        when(analysisRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisService.get(1L))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("分析记录不存在");
    }

    @Test
    void preview_recent_ranges_reports_message_count_and_coverage_limit() {
        when(messageRepository.countByGidAndCreatedAtBetween(eq(GID), anyLong(), anyLong()))
                .thenReturn(1_200L);

        var threeDays = analysisService.preview(GID, null, "last3");
        var sevenDays = analysisService.preview(GID, null, "last7");

        assertThat(threeDays.messageCount()).isEqualTo(1_200);
        assertThat(threeDays.analyzedCount()).isEqualTo(1_000);
        assertThat(threeDays.rangeStart()).isNotNull();
        assertThat(sevenDays.rangeStart().compareTo(threeDays.rangeStart())).isNegative();
    }

    @Test
    void analyze_since_last_uses_previous_successful_analysis_boundary() {
        AnalysisEntity previous = new AnalysisEntity(GID, 0L, "旧提示", "旧结果", 1, 1_000L);
        ReflectionTestUtils.setField(previous, "id", 7L);
        when(analysisRepository.findTopByGidOrderByCreatedAtDescIdDesc(GID))
                .thenReturn(Optional.of(previous));
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(7L))).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("day");
            when(rs.getLong(2)).thenReturn(0L);
            when(rs.getLong(3)).thenReturn(500L);
            when(rs.getLong(4)).thenReturn(1L);
            RowMapper<?> mapper = inv.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
        when(messageRepository.findPage(eq(GID), eq(501L), anyLong(), any(), any(),
                eq(MessageRepository.pageRequest(1, 1_000))))
                .thenReturn(new PageImpl<>(List.of(message(8L, "甲", "新增消息", "文本", 2_000L))));
        when(aiClient.chat(any())).thenReturn("新增摘要");
        when(analysisRepository.save(any())).thenAnswer(inv -> {
            AnalysisEntity entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 9L);
            return entity;
        });

        AnalysisView view = analysisService.analyze(GID, null, "since_last", "总结新增内容");

        assertThat(view.result()).isEqualTo("新增摘要");
        assertThat(view.messageCount()).isEqualTo(1);
        verify(jdbcTemplate).update(eq("insert into analysis_ranges (analysis_id, range_mode, range_start, range_end, total_count) values (?, ?, ?, ?, ?)"),
                eq(9L), eq("since_last"), eq(501L), anyLong(), eq(1L));
    }

    @Test
    void since_last_requires_previous_analysis() {
        assertThatThrownBy(() -> analysisService.analyze(GID, null, "since_last", "总结新增内容"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("暂无上次分析记录");
        verify(aiClient, never()).chat(any());
    }

    @Test
    void recent_range_rejects_more_than_limit_without_calling_ai() {
        Pageable pageable = MessageRepository.pageRequest(1, 1_000);
        when(messageRepository.findPage(eq(GID), anyLong(), anyLong(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(message(8L, "甲", "消息", "文本", 2_000L)),
                        pageable, 1_001));

        assertThatThrownBy(() -> analysisService.analyze(GID, null, "last7", "总结"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("超过 1000 条");
        verify(aiClient, never()).chat(any());
    }

    private MessageEntity message(long mid, String senderName, String text, String msgTypeName, long createdAt) {
        return new MessageEntity(mid, GID, 0, msgTypeName, 0, 0, senderName, null, text,
                null, null, null, null, null, null, null, null, null, createdAt, 0);
    }
}
