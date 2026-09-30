package com.school.counseling.module.feed.controller;

import com.school.counseling.module.auth.entity.Role;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.dto.PostReportResponseDto;
import com.school.counseling.module.feed.dto.PostResponseDto;
import com.school.counseling.module.feed.service.PostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationControllerTest {

    @Mock
    private PostService postService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ModerationController moderationController;

    @Test
    @DisplayName("viewPendingPosts: Trả về view moderation/pending-posts với DTO phân trang")
    void testViewPendingPosts() {
        PostResponseDto dto = PostResponseDto.builder()
                .id(1L)
                .title("Bài viết sinh viên chờ duyệt")
                .content("Nội dung bài viết")
                .authorName("Sinh viên Nguyễn Văn A")
                .authorEmail("sv@hcmute.edu.vn")
                .createdAt(LocalDateTime.now())
                .build();

        when(postService.getPendingPostsDto(any())).thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));
        when(postService.countPendingPosts()).thenReturn(1L);
        when(postService.countPendingReports()).thenReturn(0L);

        Model model = new ConcurrentModel();
        String view = moderationController.viewPendingPosts(0, model);

        assertThat(view).isEqualTo("moderation/pending-posts");
        assertThat(model.getAttribute("posts")).isNotNull();
        assertThat(model.getAttribute("pendingCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("viewReports: Trả về view moderation/reports-list với DTO phân trang")
    void testViewReports() {
        PostReportResponseDto repDto = PostReportResponseDto.builder()
                .id(10L)
                .reason("SPAM")
                .details("Quảng cáo khoá học")
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        when(postService.getReportsDto(eq("PENDING"), any())).thenReturn(new PageImpl<>(List.of(repDto), PageRequest.of(0, 10), 1));
        when(postService.countPendingPosts()).thenReturn(0L);
        when(postService.countPendingReports()).thenReturn(1L);

        Model model = new ConcurrentModel();
        String view = moderationController.viewReports("PENDING", 0, model);

        assertThat(view).isEqualTo("moderation/reports-list");
        assertThat(model.getAttribute("reports")).isNotNull();
        assertThat(model.getAttribute("reportCount")).isEqualTo(1L);
    }
}
