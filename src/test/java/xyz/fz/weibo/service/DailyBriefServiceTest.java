package xyz.fz.weibo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import xyz.fz.weibo.client.AiClient;
import xyz.fz.weibo.domain.DailyBriefView;
import xyz.fz.weibo.entity.PostEntity;
import xyz.fz.weibo.repository.PostRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyBriefServiceTest {

    @Mock PostRepository postRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock AiClient aiClient;

    @Test
    void generate_keeps_only_real_post_links_and_saves_brief() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        PostEntity post = new PostEntity("ABC123", 1L, 42L, "正文", "正文", "", "", "[]", "", "",
                "", 0, 0, 0, 1L, 1L);
        when(postRepository.findPage(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(post)));
        when(aiClient.chat(any())).thenReturn("{\"summary\":\"今日重点\",\"items\":["
                + "{\"mblogId\":\"MISSING\",\"summary\":\"虚构\"},"
                + "{\"mblogId\":\"ABC123\",\"summary\":\"有效\"}]}");
        DailyBriefService service = new DailyBriefService(postRepository, jdbcTemplate, aiClient, new ObjectMapper());

        DailyBriefView view = service.generate(date);

        assertThat(view.summary()).isEqualTo("今日重点");
        assertThat(view.items()).containsExactly(new DailyBriefView.Item("ABC123", "有效",
                "https://weibo.com/42/ABC123"));
        verify(jdbcTemplate).update(eq("insert into daily_briefs (date, result, post_count, created_at) values (?, ?, ?, ?)"),
                eq(date.toString()), any(), eq(1), any());
    }
}
