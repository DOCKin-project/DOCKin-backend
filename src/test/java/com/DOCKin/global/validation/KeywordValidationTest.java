package com.DOCKin.global.validation;

import com.DOCKin.safetyCourse.controller.SafetyUserController;
import com.DOCKin.safetyCourse.service.SafetyCourseService;
import com.DOCKin.safetyCourse.service.SafetyTrainingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: 검색 {@code keyword}가 없거나 비어 있으면 400이다 (P2-20-3).
 *
 * <h3>있었던 상태</h3>
 * 세 검색 엔드포인트의 {@code String keyword}에 애노테이션이 없었다. 안 보내면 null이 리포지토리까지 가서
 * {@code CONCAT('%', null, '%')}가 NULL이 되고 <b>조용히 빈 목록</b>, 빈 문자열이면 전체 매칭이었다.
 * 둘 다 클라이언트가 "내가 뭘 잘못 보냈는지" 알 수 없는 응답이다.
 *
 * <h3>두 실패 경로가 다르다 — 그래서 둘 다 본다</h3>
 * 파라미터가 <b>없으면</b> {@code MissingServletRequestParameterException}, <b>비어 있으면</b>
 * 스프링 6.1+ 내장 메서드 검증의 {@code HandlerMethodValidationException}이다. 후자는 이번에 핸들러를
 * 새로 달았다 — 없으면 {@code @NotBlank}를 붙인 것이 오히려 500을 만든다. 본문의 {@code status}까지
 * 보는 것은 HTTP 상태와 본문이 어긋나면 클라이언트가 분기를 잘못 타기 때문이다({@code GlobalExceptionHandlerTest}와 같은 기준).
 */
@WebMvcTest(SafetyUserController.class)
@WithMockUser
@DisplayName("검색 keyword - 없거나 비면 400")
class KeywordValidationTest {

    private static final String PATH = "/api/safety/user/courses/search";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyCourseService safetyCourseService;

    @MockitoBean
    private SafetyTrainingService safetyTrainingService;

    @Test
    @DisplayName("keyword 없음 - 400, 조용히 빈 목록이 아니다")
    void missing() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(safetyCourseService, never()).searchSafetyCourse(any(), any());
    }

    @Test
    @DisplayName("keyword 공백 - 400, 전체 매칭이 아니다. 500이면 HandlerMethodValidationException 핸들러가 빠진 것")
    void blank() throws Exception {
        mockMvc.perform(get(PATH).param("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(safetyCourseService, never()).searchSafetyCourse(any(), any());
    }

    @Test
    @DisplayName("keyword 있음 - 그대로 서비스로")
    void present() throws Exception {
        when(safetyCourseService.searchSafetyCourse(eq("용접"), any())).thenReturn(Page.empty());

        mockMvc.perform(get(PATH).param("keyword", "용접"))
                .andExpect(status().isOk());

        verify(safetyCourseService).searchSafetyCourse(eq("용접"), any());
    }
}
