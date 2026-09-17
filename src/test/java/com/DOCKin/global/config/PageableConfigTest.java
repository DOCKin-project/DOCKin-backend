package com.DOCKin.global.config;

import com.DOCKin.safetyCourse.controller.SafetyUserController;
import com.DOCKin.safetyCourse.service.SafetyCourseService;
import com.DOCKin.safetyCourse.service.SafetyTrainingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: 요청의 {@code sort}는 무시되고 {@code size}는 상한에서 깎인다 ({@link PageableConfig}).
 *
 * <h3>왜 이 컨트롤러인가</h3>
 * {@code GET /api/safety/user/courses}는 컨트롤러의 {@code Pageable}을 리포지토리에 그대로 넘기던
 * 경로다 — {@code ?sort=foo}가 500이 되던 자리. 서비스는 목으로 두고 <b>서비스에 도착한
 * {@code Pageable}</b>을 본다. 리졸버가 만든 값이 곧 검증 대상이고, DB는 필요 없다.
 *
 * <h3>왜 리졸버 목록 순서까지 보는가</h3>
 * {@code PageableConfig}는 스프링 데이터 리졸버를 빼지 않고 앞에 하나 더 세운다. 그 "앞에"가
 * 설정자 순서({@code Ordered})에 기대므로, 동작만 보면 우연히 맞은 것인지 알 수 없다.
 * 목록에 <b>둘 다 있고</b> 우리 것이 먼저인지를 직접 단언한다. 이 슬라이스에 스프링 데이터 리졸버가
 * 없다면(자동 설정이 빠졌다면) 그 단언이 실패하고, 그건 이 테스트가 순서를 검증하지 못하고 있다는 뜻이다.
 */
@WebMvcTest(SafetyUserController.class)
@WithMockUser
@DisplayName("Pageable 해석 - sort는 서버가, size는 상한까지")
class PageableConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerAdapter adapter;

    @MockitoBean
    private SafetyCourseService safetyCourseService;

    @MockitoBean
    private SafetyTrainingService safetyTrainingService;

    /** 컨트롤러 선언: {@code @PageableDefault(size = 20, sort = "courseId", direction = DESC)} */
    private static final Sort DECLARED = Sort.by(Sort.Direction.DESC, "courseId");

    @Test
    @DisplayName("?sort=없는컬럼 - 500이 아니라 선언된 정렬로 간다")
    void requestSortIsIgnored() throws Exception {
        when(safetyCourseService.readSafetyCourse(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/safety/user/courses").param("sort", "nope,asc"))
                .andExpect(status().isOk());

        assertThat(pageableReachedService().getSort()).isEqualTo(DECLARED);
    }

    @Test
    @DisplayName("sort를 안 줘도 같은 정렬 - 있든 없든 서버가 정한다")
    void defaultSortWithoutParameter() throws Exception {
        when(safetyCourseService.readSafetyCourse(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/safety/user/courses")).andExpect(status().isOk());

        assertThat(pageableReachedService().getSort()).isEqualTo(DECLARED);
    }

    @Test
    @DisplayName("?size=5000 - 상한 100으로 깎인다. page는 그대로 존중한다")
    void sizeIsCapped() throws Exception {
        when(safetyCourseService.readSafetyCourse(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/safety/user/courses").param("page", "3").param("size", "5000"))
                .andExpect(status().isOk());

        Pageable reached = pageableReachedService();
        assertThat(reached.getPageSize()).isEqualTo(100);
        assertThat(reached.getPageNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("리졸버 목록에 둘 다 있고 우리 것이 앞이다")
    void ourResolverPrecedesSpringDataOne() {
        List<HandlerMethodArgumentResolver> custom = adapter.getCustomArgumentResolvers();
        assertThat(custom).isNotNull();

        List<HandlerMethodArgumentResolver> pageable = custom.stream()
                .filter(PageableHandlerMethodArgumentResolver.class::isInstance)
                .toList();

        assertThat(pageable).as("스프링 데이터 리졸버와 우리 리졸버가 함께 있어야 순서를 검증하는 것이다")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(pageable.get(0)).isInstanceOf(PageableConfig.ServerSortedPageableResolver.class);
    }

    private Pageable pageableReachedService() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(safetyCourseService).readSafetyCourse(captor.capture());
        return captor.getValue();
    }
}
