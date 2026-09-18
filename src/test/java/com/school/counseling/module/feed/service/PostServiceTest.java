package com.school.counseling.module.feed.service;

import com.school.counseling.common.exception.ResourceNotFoundException;
import com.school.counseling.common.storage.IStorageService;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.dto.CreatePostRequest;
import com.school.counseling.module.feed.dto.PostResponseDto;
import com.school.counseling.module.feed.entity.Comment;
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.entity.PostAttachment;
import com.school.counseling.module.feed.entity.PostReport;
import com.school.counseling.module.feed.repository.CommentRepository;
import com.school.counseling.module.feed.repository.PostAttachmentRepository;
import com.school.counseling.module.feed.repository.PostReportRepository;
import com.school.counseling.module.feed.repository.PostRepository;
import com.school.counseling.module.integration.dto.VideoWebhookPayload;
import com.school.counseling.module.integration.service.VideoIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostAttachmentRepository attachmentRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private PostReportRepository postReportRepository;

    @Mock
    private IStorageService storageService;

    @Mock
    private VideoIntegrationService videoIntegrationService;

    @InjectMocks
    private PostService postService;

    private User mockStaff;
    private User mockStudent;

    @BeforeEach
    void setUp() {
        mockStaff = new User();
        mockStaff.setId(1L);
        mockStaff.setUsername("staff_tuyensinh");
        mockStaff.setFullName("Cán bộ Tuyển sinh");

        mockStudent = new User();
        mockStudent.setId(2L);
        mockStudent.setUsername("student01");
        mockStudent.setFullName("Trương Lê Trung Hiếu");
    }

    @Test
    @DisplayName("TDD-01 [Happy Path]: Đăng bài chính thức kèm cờ requestVideo -> Lưu status APPROVED và trigger sang Node.js")
    void shouldCreateOfficialPostAndTriggerVideoGeneration_HappyPath() {
        // Arrange
        CreatePostRequest request = new CreatePostRequest();
        request.setTitle("Thông báo tuyển sinh 2026");
        request.setContent("Chi tiết đề án tuyển sinh...");
        request.setRequestVideo(true);
        request.setFileUrl("https://storage.hcmute.edu.vn/dean2026.pdf");
        request.setFileName("dean2026.pdf");
        request.setFileType("PDF");
        request.setFileSize(5242880L);

        Post savedPost = Post.builder()
                .id(100L)
                .title(request.getTitle())
                .content(request.getContent())
                .postType("OFFICIAL_ANNOUNCEMENT")
                .status("APPROVED")
                .videoStatus("PROCESSING")
                .author(mockStaff)
                .build();

        when(postRepository.save(any(Post.class))).thenReturn(savedPost);

        // Act
        PostResponseDto result = postService.createOfficialPost(request, mockStaff);

        // Assert
        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals("APPROVED", result.getStatus());
        assertEquals("PROCESSING", result.getVideoStatus());

        // Xác nhận đã gọi sang Node.js Video Integration Service
        verify(videoIntegrationService, times(1)).requestVideoGeneration(eq(savedPost));
        verify(attachmentRepository, times(1)).save(any(PostAttachment.class));
    }

    @Test
    @DisplayName("TDD-02 [Happy Path]: Nhận Webhook từ Node.js -> Lưu MP4 vào post_attachments và đổi videoStatus = COMPLETED")
    void shouldHandleNodejsWebhookCallbackAndAttachVideo_Success() {
        // Arrange
        Long postId = 100L;
        Post existingPost = Post.builder()
                .id(postId)
                .title("Thông báo tuyển sinh 2026")
                .videoStatus("PROCESSING")
                .build();

        VideoWebhookPayload webhookPayload = new VideoWebhookPayload();
        webhookPayload.setPostId(postId);
        webhookPayload.setVideoUrl("http://localhost:5000/videos/post_100/video.mp4");
        webhookPayload.setDurationSeconds(40);
        webhookPayload.setStatus("COMPLETED");

        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        // Act
        postService.handleVideoWebhookCallback(webhookPayload);

        // Assert
        assertEquals("COMPLETED", existingPost.getVideoStatus());
        assertEquals("http://localhost:5000/videos/post_100/video.mp4", existingPost.getVideoUrl());
        verify(postRepository, times(1)).save(existingPost);

        // Kiểm tra attachment được lưu đúng chuẩn BRULE-NODE-002
        ArgumentCaptor<PostAttachment> captor = ArgumentCaptor.forClass(PostAttachment.class);
        verify(attachmentRepository, times(1)).save(captor.capture());
        PostAttachment savedAttachment = captor.getValue();

        assertEquals("MP4", savedAttachment.getFileType());
        assertEquals("NODEJS_WEBHOOK", savedAttachment.getSourceType());
        assertEquals("http://localhost:5000/videos/post_100/video.mp4", savedAttachment.getFileUrl());
    }

    @Test
    @DisplayName("TDD-03 [Violation]: Webhook gửi postId không tồn tại -> Ném ResourceNotFoundException")
    void shouldThrowExceptionWhenPostNotFoundOnWebhookCallback() {
        // Arrange
        VideoWebhookPayload webhookPayload = new VideoWebhookPayload();
        webhookPayload.setPostId(999L);
        webhookPayload.setStatus("COMPLETED");

        when(postRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> {
            postService.handleVideoWebhookCallback(webhookPayload);
        });

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("TDD-04 [BRULE-POST-002]: Sinh viên đăng bài diễn đàn -> Trạng thái bắt buộc PENDING_APPROVAL")
    void shouldCreateForumPostWithPendingApprovalStatus() {
        // Arrange
        String title = "Xin tài liệu ôn thi TOEIC";
        String content = "Mọi người cho mình xin tài liệu với ạ...";

        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post p = invocation.getArgument(0);
            p.setId(200L);
            return p;
        });

        // Act
        Post saved = postService.createForumPost(title, content, null, mockStudent);

        // Assert
        assertNotNull(saved);
        assertEquals(200L, saved.getId());
        assertEquals("PENDING_APPROVAL", saved.getStatus());
        assertEquals("STUDENT_FORUM", saved.getPostType());
        assertEquals(mockStudent, saved.getAuthor());
        verify(postRepository, times(1)).save(any(Post.class));
    }

    @Test
    @DisplayName("TDD-05 [BRULE-POST-003]: Staff phê duyệt bài viết PENDING -> Trạng thái APPROVED và lưu approvedBy, approvedAt")
    void shouldApprovePendingPostByStaff() {
        // Arrange
        Long postId = 200L;
        Post pendingPost = Post.builder()
                .id(postId)
                .title("Bài viết chờ duyệt")
                .status("PENDING_APPROVAL")
                .build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(pendingPost));

        // Act
        postService.approvePost(postId, mockStaff);

        // Assert
        assertEquals("APPROVED", pendingPost.getStatus());
        assertEquals(mockStaff, pendingPost.getApprovedBy());
        assertNotNull(pendingPost.getApprovedAt());
        verify(postRepository, times(1)).save(pendingPost);
    }

    @Test
    @DisplayName("TDD-06: Staff từ chối bài viết PENDING kèm lý do -> Trạng thái REJECTED và lưu rejectionReason")
    void shouldRejectPendingPostWithReason() {
        // Arrange
        Long postId = 200L;
        Post pendingPost = Post.builder()
                .id(postId)
                .title("Bài viết vi phạm")
                .status("PENDING_APPROVAL")
                .build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(pendingPost));

        // Act
        postService.rejectPost(postId, "Nội dung spam quảng cáo", mockStaff);

        // Assert
        assertEquals("REJECTED", pendingPost.getStatus());
        assertEquals("Nội dung spam quảng cáo", pendingPost.getRejectionReason());
        verify(postRepository, times(1)).save(pendingPost);
    }

    @Test
    @DisplayName("TDD-07: Sinh viên bấm Like bài viết -> Số lượt like tăng thêm 1")
    void shouldIncrementLikeCountWhenUserLikesPost() {
        // Arrange
        Long postId = 200L;
        Post post = Post.builder()
                .id(postId)
                .title("Bài viết hữu ích")
                .likeCount(5)
                .build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        // Act
        int newLikes = postService.likePost(postId);

        // Assert
        assertEquals(6, newLikes);
        assertEquals(6, post.getLikeCount());
        verify(postRepository, times(1)).save(post);
    }

    @Test
    @DisplayName("TDD-08: Thêm bình luận vào bài viết -> Bình luận được lưu và gắn đúng bài viết, tác giả")
    void shouldAddCommentToPostSuccessfully() {
        // Arrange
        Long postId = 200L;
        Post post = Post.builder()
                .id(postId)
                .title("Bài viết thảo luận")
                .build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(500L);
            return c;
        });

        // Act
        Comment comment = postService.addComment(postId, "Lời khuyên rất hay, cảm ơn bạn!", mockStudent);

        // Assert
        assertNotNull(comment);
        assertEquals(500L, comment.getId());
        assertEquals("Lời khuyên rất hay, cảm ơn bạn!", comment.getContent());
        assertEquals(mockStudent, comment.getAuthor());
        verify(commentRepository, times(1)).save(any(Comment.class));
    }

    @Test
    @DisplayName("TDD-09 [BRULE-POST-004]: Báo cáo vi phạm bài viết -> Tạo bản ghi PostReport với status PENDING")
    void shouldReportPostViolationSuccessfully() {
        // Arrange
        Long postId = 200L;
        Post post = Post.builder()
                .id(postId)
                .title("Bài viết có dấu hiệu sai phạm")
                .build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(postReportRepository.save(any(PostReport.class))).thenAnswer(inv -> {
            PostReport r = inv.getArgument(0);
            r.setId(888L);
            return r;
        });

        // Act
        PostReport report = postService.reportPost(postId, "SPAM", "Quảng cáo khóa học ngoài trường", mockStudent);

        // Assert
        assertNotNull(report);
        assertEquals(888L, report.getId());
        assertEquals("SPAM", report.getReason());
        assertEquals("PENDING", report.getStatus());
        assertEquals(mockStudent, report.getReporter());
        verify(postReportRepository, times(1)).save(any(PostReport.class));
    }
}
