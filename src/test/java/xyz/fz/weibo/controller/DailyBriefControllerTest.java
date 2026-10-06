package xyz.fz.weibo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import xyz.fz.weibo.domain.DailyBriefView;
import xyz.fz.weibo.service.DailyBriefService;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DailyBriefController.class)
class DailyBriefControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean DailyBriefService service;

    @Test
    void missing_brief_returns_no_content_and_generated_brief_has_link() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 5);
        mockMvc.perform(get("/post/daily-brief").param("date", date.toString()))
                .andExpect(status().isNoContent());
        when(service.generate(date)).thenReturn(new DailyBriefView(date.toString(), "重点",
                List.of(new DailyBriefView.Item("ABC", "内容", "https://weibo.com/1/ABC")), 1, 1L));
        mockMvc.perform(post("/post/daily-brief").param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postUrl").value("https://weibo.com/1/ABC"));
    }
}
