package com.luobo.hoyoagent.adapter.http;

import com.luobo.hoyoagent.agent.transcript.TranscriptPage;
import com.luobo.hoyoagent.agent.transcript.TranscriptStore;
import com.luobo.hoyoagent.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TranscriptControllerTest {

    private final TranscriptStore store = mock(TranscriptStore.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TranscriptController(store))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void exposesNoWriteEndpoint() throws Exception {
        when(store.sessions()).thenReturn(List.of());

        mvc.perform(get("/api/transcripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // 全局异常处理器约定一律 HTTP 200，业务码放在 body 里
        mvc.perform(post("/api/transcripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405));

        mvc.perform(delete("/api/transcripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    void readPassesPagingThrough() throws Exception {
        when(store.read("qqbot:c2c:u", 4, 2))
                .thenReturn(new TranscriptPage("qqbot:c2c:u", "qq:u", List.of(), true));

        mvc.perform(get("/api/transcripts/{id}", "qqbot:c2c:u")
                        .param("before", "4")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("qqbot:c2c:u"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }
}
