package com.school.counseling.module.feed.service;

import com.school.counseling.common.exception.ResourceNotFoundException;
import com.school.counseling.common.storage.IStorageService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.dto.CreatePostRequest;
import com.school.counseling.module.feed.dto.PostAttachmentDto;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final PostAttachmentRepository attachmentRepository;
    private final DepartmentRepository departmentRepository;
    private final CommentRepository commentRepository;
    private final PostReportRepository postReportRepository;
    private final UserRepository userRepository;
    private final IStorageService storageService;
    private final VideoIntegrationService videoIntegrationService;

    /**
     * Cán bộ đăng thông báo chính thức kèm upload tệp
     */
    @Transactional
    public PostResponseDto createOfficialPostWithUpload(CreatePostRequest request, MultipartFile file, User author) {
        if (file != null && !file.isEmpty()) {
            IStorageService.StorageResult uploadResult = storageService.uploadFile(file, "documents");
            if (uploadResult.isSuccess()) {
                request.setFileUrl(uploadResult.publicUrl());
                request.setFileName(uploadResult.originalFileName());
                request.setFileType(uploadResult.fileType());
                request.setFileSize(uploadResult.fileSizeBytes());
            } else {
                log.warn("Tải tệp lên thất bại: {}", uploadResult.errorMessage());
            }
        }
        return createOfficialPost(request, author);
    }

    /**
     * Cán bộ đăng thông báo chính thức kèm tài liệu và tùy chọn tạo video
     */
    @Transactional
    public PostResponseDto createOfficialPost(CreatePostRequest request, User author) {
        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId()).orElse(null);
        } else if (author != null && author.getDepartment() != null) {
            department = author.getDepartment();
        }

        String videoStatus = request.isRequestVideo() ? "PROCESSING" : "NONE";

        Post post = Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .postType("OFFICIAL_ANNOUNCEMENT")
                .status("APPROVED") // BRULE-POST-001: Thông báo chính thức của Staff được duyệt ngay
                .videoStatus(videoStatus)
                .author(author)
                .department(department)
                .isPinned(request.isPinned())
                .build();

        Post savedPost = postRepository.save(post);

        // Lưu file tài liệu đính kèm nếu có
        if (request.getFileUrl() != null && !request.getFileUrl().isBlank()) {
            PostAttachment docAttachment = PostAttachment.builder()
                    .post(savedPost)
                    .fileName(request.getFileName() != null ? request.getFileName() : "tailieu_dinhkem")
                    .fileUrl(request.getFileUrl())
                    .fileType(request.getFileType() != null ? request.getFileType().toUpperCase() : "PDF")
                    .fileSize(request.getFileSize() != null ? request.getFileSize() : 0L)
                    .sourceType("DIRECT_UPLOAD")
                    .build();
            attachmentRepository.save(docAttachment);
            savedPost.addAttachment(docAttachment);
        }

        // Kích hoạt Microservice Node.js sinh video tóm tắt nếu có yêu cầu
        if (request.isRequestVideo()) {
            videoIntegrationService.requestVideoGeneration(savedPost);
        }

        return mapToDto(savedPost);
    }

    /**
     * Sinh viên đăng bài thảo luận (Khởi tạo trạng thái PENDING_APPROVAL)
     */
    @Transactional
    public Post createForumPost(String title, String content, MultipartFile image, User author) {
        Post post = Post.builder()
                .title(title)
                .content(content)
                .postType("STUDENT_FORUM")
                .status("PENDING_APPROVAL") // BRULE-POST-002: Bài thảo luận SV bắt buộc qua hàng đợi duyệt
                .author(author)
                .department(author != null ? author.getDepartment() : null)
                .build();

        Post savedPost = postRepository.save(post);

        if (image != null && !image.isEmpty()) {
            IStorageService.StorageResult uploadResult = storageService.uploadFile(image, "images");
            if (uploadResult.isSuccess()) {
                PostAttachment imageAtt = PostAttachment.builder()
                        .post(savedPost)
                        .fileName(uploadResult.originalFileName())
                        .fileUrl(uploadResult.publicUrl())
                        .fileType(uploadResult.fileType())
                        .fileSize(uploadResult.fileSizeBytes())
                        .sourceType("DIRECT_UPLOAD")
                        .build();
                attachmentRepository.save(imageAtt);
                savedPost.addAttachment(imageAtt);
            }
        }

        log.info("Sinh viên {} đã gửi bài thảo luận ID: {} (Chờ kiểm duyệt)", author.getUsername(), savedPost.getId());
        return savedPost;
    }

    /**
     * Phê duyệt bài viết (Cán bộ / Admin)
     */
    @Transactional
    public void approvePost(Long postId, User approver) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        post.setStatus("APPROVED");
        post.setApprovedBy(approver);
        post.setApprovedAt(LocalDateTime.now());
        postRepository.save(post);
        log.info("Cán bộ {} đã phê duyệt bài viết ID: {}", approver.getUsername(), postId);
    }

    /**
     * Từ chối bài viết (Cán bộ / Admin)
     */
    @Transactional
    public void rejectPost(Long postId, String reason, User approver) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        post.setStatus("REJECTED");
        post.setRejectionReason(reason);
        post.setApprovedBy(approver);
        post.setApprovedAt(LocalDateTime.now());
        postRepository.save(post);
        log.info("Cán bộ {} đã từ chối bài viết ID: {} với lý do: {}", approver.getUsername(), postId, reason);
    }

    /**
     * Thả tim (Like) bài viết
     */
    @Transactional
    public int likePost(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        int currentLikes = (post.getLikeCount() != null) ? post.getLikeCount() : 0;
        post.setLikeCount(currentLikes + 1);
        postRepository.save(post);
        return post.getLikeCount();
    }

    /**
     * Thêm bình luận vào bài viết
     */
    @Transactional
    public Comment addComment(Long postId, String content, User author) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        Comment comment = Comment.builder()
                .post(post)
                .author(author)
                .content(content.trim())
                .likeCount(0)
                .build();

        return commentRepository.save(comment);
    }

    /**
     * Lấy danh sách bình luận kèm tác giả
     */
    @Transactional(readOnly = true)
    public List<Comment> getCommentsByPostId(Long postId) {
        return commentRepository.findByPostIdWithAuthor(postId);
    }

    /**
     * Báo cáo bài viết vi phạm
     */
    @Transactional
    public PostReport reportPost(Long postId, String reason, String details, User reporter) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        PostReport report = PostReport.builder()
                .post(post)
                .reporter(reporter)
                .reason(reason)
                .details(details)
                .status("PENDING")
                .build();

        return postReportRepository.save(report);
    }

    /**
     * Xử lý báo cáo vi phạm
     */
    @Transactional
    public void resolveReport(Long reportId, String action, User staff) {
        PostReport report = postReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy báo cáo ID: " + reportId));

        Post post = report.getPost();
        if ("HIDE_POST".equalsIgnoreCase(action)) {
            post.setStatus("HIDDEN");
            postRepository.save(post);
        } else if ("LOCK_USER".equalsIgnoreCase(action)) {
            post.setStatus("HIDDEN");
            postRepository.save(post);
            User author = post.getAuthor();
            if (author != null) {
                author.setStatus("LOCKED");
                userRepository.save(author);
                log.warn("Tài khoản {} đã bị khóa do vi phạm nội dung nghiêm trọng", author.getUsername());
            }
        }

        report.setStatus("RESOLVED");
        report.setActionTaken(action);
        report.setResolvedBy(staff);
        report.setResolvedAt(LocalDateTime.now());
        postReportRepository.save(report);
    }

    /**
     * Tiếp nhận Webhook Callback từ Microservice Node.js sau khi render video xong
     */
    @Transactional
    public void handleVideoWebhookCallback(VideoWebhookPayload payload) {
        log.info("Xử lý Webhook từ Node.js cho Post #{}, status: {}", payload.getPostId(), payload.getStatus());

        Post post = postRepository.findById(payload.getPostId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + payload.getPostId()));

        if ("COMPLETED".equalsIgnoreCase(payload.getStatus())) {
            post.setVideoUrl(payload.getVideoUrl());
            post.setVideoStatus("COMPLETED");

            PostAttachment videoAttachment = PostAttachment.builder()
                    .post(post)
                    .fileName("video_summary_40s.mp4")
                    .fileUrl(payload.getVideoUrl())
                    .fileType("MP4")
                    .fileSize(0L)
                    .sourceType("NODEJS_WEBHOOK")
                    .build();
            attachmentRepository.save(videoAttachment);
            post.addAttachment(videoAttachment);

            log.info("Đã gắn thành công Video 40s vào Post #{}", post.getId());
        } else {
            post.setVideoStatus("FAILED");
            log.warn("Node.js render video thất bại cho Post #{}, lý do: {}", post.getId(), payload.getErrorMessage());
        }

        postRepository.save(post);
    }

    /**
     * Lấy chi tiết bài viết kèm danh sách tệp đính kèm và video
     */
    @Transactional(readOnly = true)
    public PostResponseDto getPostById(Long id) {
        Post post = postRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + id));

        List<PostAttachment> attachments = attachmentRepository.findByPostIdAndIsDeletedFalse(id);
        post.setAttachments(attachments);

        return mapToDto(post);
    }

    /**
     * Lấy chi tiết Post Entity trực tiếp
     */
    @Transactional(readOnly = true)
    public Post getPostEntity(Long id) {
        Post post = postRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + id));
        List<PostAttachment> attachments = attachmentRepository.findByPostIdAndIsDeletedFalse(id);
        post.setAttachments(attachments);
        return post;
    }

    /**
     * Lấy danh sách thông báo chính thức đã phê duyệt (không lọc)
     */
    @Transactional(readOnly = true)
    public Page<PostResponseDto> getApprovedOfficialPosts(Pageable pageable) {
        return getApprovedOfficialPosts(null, null, pageable);
    }

    /**
     * Lấy danh sách thông báo chính thức đã phê duyệt (hỗ trợ lọc theo Khoa/Phòng và tìm kiếm)
     */
    @Transactional(readOnly = true)
    public Page<PostResponseDto> getApprovedOfficialPosts(Long departmentId, String query, Pageable pageable) {
        Page<Post> posts;
        if (query != null && !query.trim().isEmpty()) {
            posts = postRepository.searchApprovedOfficialPosts(query.trim(), pageable);
        } else if (departmentId != null) {
            posts = postRepository.findApprovedOfficialPostsByDepartment(departmentId, pageable);
        } else {
            posts = postRepository.findApprovedPostsByType("OFFICIAL_ANNOUNCEMENT", pageable);
        }
        return posts.map(this::mapToDto);
    }

    /**
     * Lấy danh sách bài viết trên Diễn đàn sinh viên đã phê duyệt
     */
    @Transactional(readOnly = true)
    public Page<Post> getApprovedForumPosts(Pageable pageable) {
        Page<Post> posts = postRepository.findApprovedPostsByType("STUDENT_FORUM", pageable);
        for (Post p : posts) {
            List<PostAttachment> attachments = attachmentRepository.findByPostIdAndIsDeletedFalse(p.getId());
            p.setAttachments(attachments);
        }
        return posts;
    }

    /**
     * Lấy danh sách bài viết đang chờ kiểm duyệt
     */
    @Transactional(readOnly = true)
    public Page<Post> getPendingPosts(Pageable pageable) {
        return postRepository.findPendingModerationPosts(pageable);
    }

    /**
     * Lấy danh sách báo cáo vi phạm
     */
    @Transactional(readOnly = true)
    public Page<PostReport> getReports(String status, Pageable pageable) {
        return postReportRepository.findReportsByStatus(status, pageable);
    }

    public long countPendingPosts() {
        return postRepository.countByStatusAndIsDeletedFalse("PENDING_APPROVAL");
    }

    public long countPendingReports() {
        return postReportRepository.countByStatus("PENDING");
    }

    private PostResponseDto mapToDto(Post post) {
        List<PostAttachmentDto> attachmentDtos = null;
        if (post.getAttachments() != null) {
            attachmentDtos = post.getAttachments().stream()
                    .map(att -> PostAttachmentDto.builder()
                            .id(att.getId())
                            .fileName(att.getFileName())
                            .fileUrl(att.getFileUrl())
                            .fileType(att.getFileType())
                            .fileSize(att.getFileSize())
                            .sourceType(att.getSourceType())
                            .createdAt(att.getCreatedAt())
                            .build())
                    .collect(Collectors.toList());
        }

        return PostResponseDto.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .postType(post.getPostType())
                .status(post.getStatus())
                .videoStatus(post.getVideoStatus())
                .videoUrl(post.getVideoUrl())
                .isPinned(post.getIsPinned())
                .viewCount(post.getViewCount())
                .departmentId(post.getDepartment() != null ? post.getDepartment().getId() : null)
                .departmentName(post.getDepartment() != null ? post.getDepartment().getName() : null)
                .authorId(post.getAuthor() != null ? post.getAuthor().getId() : null)
                .authorName(post.getAuthor() != null ? post.getAuthor().getFullName() : null)
                .attachments(attachmentDtos)
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
