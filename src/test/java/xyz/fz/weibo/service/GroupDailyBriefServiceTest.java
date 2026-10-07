package xyz.fz.weibo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import xyz.fz.weibo.client.AiClient;
import xyz.fz.weibo.domain.GroupDailyBriefView;
import xyz.fz.weibo.entity.MessageEntity;
import xyz.fz.weibo.repository.GroupRepository;
import xyz.fz.weibo.repository.MessageRepository;
import xyz.fz.weibo.service.exception.InvalidRequestException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupDailyBriefServiceTest {

    @Mock GroupRepository groupRepository;
    @Mock MessageRepository messageRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock AiClient aiClient;

    @Test
    void generate_keeps_only_existing_message_references_and_saves_result() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        MessageEntity message = new MessageEntity(42L, 7L, 1, "文本", 0, 9L,
                "甲", "", "一条测试消息", "", "", "", "[]", "[]", "", "{}",
                "[]", "", 1_000L, 1_000L);
        when(groupRepository.existsById(7L)).thenReturn(true);
        when(messageRepository.findPage(eq(7L), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(message)));
        when(aiClient.chat(any())).thenReturn("{\"summary\":\"当日概览\",\"items\":["
                + "{\"mid\":999,\"summary\":\"无效\"},"
                + "{\"mid\":42,\"summary\":\"有效\"},"
                + "{\"mid\":42,\"summary\":\"重复\"}]}");
        GroupDailyBriefService service = new GroupDailyBriefService(groupRepository,
                messageRepository, jdbcTemplate, aiClient, new ObjectMapper());

        GroupDailyBriefView view = service.generate(7L, date);

        assertThat(view.summary()).isEqualTo("当日概览");
        assertThat(view.items()).containsExactly(new GroupDailyBriefView.Item(
                42L, "有效", "甲", "一条测试消息", 1_000L));
        assertThat(view.messageCount()).isEqualTo(1);
        assertThat(view.analyzedCount()).isEqualTo(1);
        verify(jdbcTemplate).update(eq("insert into group_daily_briefs (gid, date, result, message_count, created_at) values (?, ?, ?, ?, ?)"),
                eq(7L), eq(date.toString()), any(), eq(1L), any());
    }

    @Test
    void generate_rejects_day_without_local_messages_before_ai_call() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(groupRepository.existsById(7L)).thenReturn(true);
        when(messageRepository.findPage(eq(7L), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        GroupDailyBriefService service = new GroupDailyBriefService(groupRepository,
                messageRepository, jdbcTemplate, aiClient, new ObjectMapper());

        assertThatThrownBy(() -> service.generate(7L, date))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("无本地消息");
        verify(aiClient, never()).chat(any());
    }
}
