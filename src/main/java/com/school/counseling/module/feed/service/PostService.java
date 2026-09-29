package com.school.counseling.module.feed.service;

import com.school.counseling.common.exception.ResourceNotFoundException;
import com.school.counseling.common.storage.IStorageService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.dto.CommentDto;
import com.school.counseling.module.feed.dto.CreatePostRequest;
import com.school.counseling.module.feed.dto.PostAttachmentDto;
import com.school.counseling.module.feed.dto.PostResponseDto;
import com.school.counseling.module.feed.entity.Comment;
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.entity.PostAttachment;
import com.school.counseling.module.feed.entity.PostReport;
import com.school.counseling.module.feed.repository.LikeRepository;
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
import java.util.*;
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
    private final LikeRepository likeRepository;
    private final IStorageService storageService;
    private final VideoIntegrationService videoIntegrationService;

    /**
     * Cán bộ đăng thông báo chính thức (qua REST API JSON)
     */
    @Transactional
    public PostResponseDto createOfficialPost(CreatePostRequest request, User author) {
        return createOfficialPostWithUpload(request, null, java.util.List.of(), author);
    }

    /**
     * Cán bộ đăng thông báo chính thức kèm upload tệp đơn (tương thích ngược)
     */
    @Transactional
    public PostResponseDto createOfficialPostWithUpload(CreatePostRequest request, MultipartFile file, User author) {
        List<MultipartFile> docs = (file != null && !file.isEmpty()) ? List.of(file) : List.of();
        return createOfficialPostWithUpload(request, null, docs, author);
    }

    /**
     * Cán bộ đăng thông báo chính thức hỗ trợ Video Hybrid (Tải file hoặc Nhúng YouTube/Drive)
     * và đính kèm tối đa 5 tài liệu văn phòng theo BRULE-POST-001
     */
    @Transactional
    public PostResponseDto createOfficialPostWithUpload(
            CreatePostRequest request,
            MultipartFile videoFile,
            List<MultipartFile> documentFiles,
            User author) {

        // 1. Xử lý Video thông báo
        if (videoFile != null && !videoFile.isEmpty()) {
            IStorageService.StorageResult videoResult = storageService.uploadFile(videoFile, "videos");
            if (videoResult.isSuccess()) {
                request.setVideoUrl(videoResult.publicUrl());
            } else {
                log.warn("Tải video lên thất bại: {}", videoResult.errorMessage());
            }
        } else if (request.getVideoEmbedUrl() != null && !request.getVideoEmbedUrl().isBlank()) {
            request.setVideoUrl(request.getVideoEmbedUrl().trim());
        }

        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId()).orElse(null);
        } else if (author != null && author.getDepartment() != null) {
            department = author.getDepartment();
        }

        Post post = Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .postType("OFFICIAL_ANNOUNCEMENT")
                .status("APPROVED") // BRULE-POST-001: Thông báo chính thức của Staff được duyệt ngay
                .videoStatus(request.getVideoUrl() != null ? "COMPLETED" : "NONE")
                .videoUrl(request.getVideoUrl())
                .author(author)
                .department(department)
                .isPinned(request.isPinned())
                .build();

        Post savedPost = postRepository.save(post);

        // 2. Xử lý Đa tệp Văn bản Đính kèm (Tối đa 5 tệp theo BRULE-POST-001)
        if (documentFiles != null && !documentFiles.isEmpty()) {
            int count = 0;
            for (MultipartFile docFile : documentFiles) {
                if (docFile != null && !docFile.isEmpty() && count < 5) {
                    IStorageService.StorageResult uploadResult = storageService.uploadFile(docFile, "documents");
                    if (uploadResult.isSuccess()) {
                        PostAttachment docAttachment = PostAttachment.builder()
                                .post(savedPost)
                                .fileName(uploadResult.originalFileName())
                                .fileUrl(uploadResult.publicUrl())
                                .fileType(uploadResult.fileType() != null ? uploadResult.fileType().toUpperCase() : "DOCX")
                                .fileSize(uploadResult.fileSizeBytes())
                                .sourceType("DIRECT_UPLOAD")
                                .build();
                        attachmentRepository.save(docAttachment);
                        savedPost.addAttachment(docAttachment);
                        count++;
                    }
                }
            }
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
     * Sinh viên đăng bài thảo luận trả về DTO cho AJAX Fetch API
     */
    @Transactional
    public PostResponseDto createForumPostDto(String title, String content, MultipartFile image, User author) {
        Post savedPost = createForumPost(title, content, image, author);
        return mapToDto(savedPost);
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
     * Thêm bình luận vào bài viết (Hỗ trợ trả lời lồng nhau - Nested Reply)
     */
    @Transactional
    public Comment addComment(Long postId, String content, User author) {
        return addComment(postId, content, null, author);
    }

    @Transactional
    public Comment addComment(Long postId, String content, Long parentId, User author) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        Comment parent = null;
        if (parentId != null) {
            parent = commentRepository.findById(parentId).orElse(null);
        }

        Comment comment = Comment.builder()
                .post(post)
                .author(author)
                .parent(parent)
                .content(content.trim())
                .likeCount(0)
                .build();

        return commentRepository.save(comment);
    }

    @Transactional
    public CommentDto addCommentDto(Long postId, String content, Long parentId, User author) {
        Comment comment = addComment(postId, content, parentId, author);
        return mapCommentToDto(comment);
    }

    /**
     * Lấy danh sách bình luận kèm tác giả
     */
    @Transactional(readOnly = true)
    public List<Comment> getCommentsByPostId(Long postId) {
        return commentRepository.findByPostIdWithAuthor(postId);
    }

    /**
     * Lấy cây bình luận phân cấp (Nested Comments) cho bài viết
     */
    @Transactional(readOnly = true)
    public List<CommentDto> getPostCommentsDto(Long postId) {
        List<Comment> allComments = commentRepository.findByPostIdWithAuthor(postId);
        Map<Long, CommentDto> map = new LinkedHashMap<>();
        List<CommentDto> roots = new ArrayList<>();

        for (Comment cmt : allComments) {
            CommentDto dto = mapCommentToDto(cmt);
            map.put(cmt.getId(), dto);
        }

        for (Comment cmt : allComments) {
            CommentDto dto = map.get(cmt.getId());
            if (cmt.getParent() != null && map.containsKey(cmt.getParent().getId())) {
                map.get(cmt.getParent().getId()).getReplies().add(dto);
            } else {
                roots.add(dto);
            }
        }

        return roots;
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
     * Lấy chi tiết bài viết kèm danh sách tệp đính kèm và video (Tự động tăng viewCount)
     */
    @Transactional
    public PostResponseDto getPostById(Long id) {
        Post post = postRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + id));

        // Tự động tăng lượt xem bài viết
        int currentViews = (post.getViewCount() == null) ? 0 : post.getViewCount();
        post.setViewCount(currentViews + 1);
        postRepository.save(post);

        List<PostAttachment> attachments = attachmentRepository.findByPostIdAndIsDeletedFalse(id);
        return mapToDto(post, attachments);
    }

    /**
     * Lấy chi tiết Post Entity trực tiếp
     */
    @Transactional(readOnly = true)
    public Post getPostEntity(Long id) {
        return postRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + id));
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
        return searchApprovedForumPosts(null, null, pageable);
    }

    /**
     * Tìm kiếm và lọc bài viết trên Diễn đàn sinh viên theo từ khóa và Khoa (DTO chuẩn hóa OSIV=false)
     */
    @Transactional(readOnly = true)
    public Page<PostResponseDto> searchApprovedForumPostsDto(String query, Long departmentId, Long currentUserId, Pageable pageable) {
        Page<Post> posts = postRepository.searchApprovedForumPosts(
                (query != null && !query.trim().isEmpty()) ? query.trim() : null,
                departmentId,
                pageable);

        Set<Long> userLikedPostIds = Collections.emptySet();
        if (currentUserId != null && posts.hasContent()) {
            List<Long> postIds = posts.getContent().stream().map(Post::getId).collect(Collectors.toList());
            userLikedPostIds = likeRepository.findByUserIdAndTargetTypeAndTargetIdIn(currentUserId, "POST", postIds)
                    .stream().map(com.school.counseling.module.feed.entity.Like::getTargetId).collect(Collectors.toSet());
        }

        final Set<Long> likedIds = userLikedPostIds;
        return posts.map(p -> {
            List<PostAttachment> attachments = attachmentRepository.findByPostIdAndIsDeletedFalse(p.getId());
            PostResponseDto dto = mapToDto(p, attachments);
            dto.setUserLiked(likedIds.contains(p.getId()));
            return dto;
        });
    }

    /**
     * Lấy danh sách bài viết của chính tác giả (DTO chuẩn hóa OSIV=false)
     */
    @Transactional(readOnly = true)
    public Page<PostResponseDto> getMyPostsDto(Long authorId, Pageable pageable) {
        Page<Post> posts = postRepository.findPostsByAuthorId(authorId, pageable);

        Set<Long> userLikedPostIds = Collections.emptySet();
        if (authorId != null && posts.hasContent()) {
            List<Long> postIds = posts.getContent().stream().map(Post::getId).collect(Collectors.toList());
            userLikedPostIds = likeRepository.findByUserIdAndTargetTypeAndTargetIdIn(authorId, "POST", postIds)
                    .stream().map(com.school.counseling.module.feed.entity.Like::getTargetId).collect(Collectors.toSet());
        }

        final Set<Long> likedIds = userLikedPostIds;
        return posts.map(p -> {
            List<PostAttachment> attachments = attachmentRepository.findByPostIdAndIsDeletedFalse(p.getId());
            PostResponseDto dto = mapToDto(p, attachments);
            dto.setUserLiked(likedIds.contains(p.getId()));
            return dto;
        });
    }

    @Transactional(readOnly = true)
    public Page<Post> searchApprovedForumPosts(String query, Long departmentId, Pageable pageable) {
        return postRepository.searchApprovedForumPosts(
                (query != null && !query.trim().isEmpty()) ? query.trim() : null,
                departmentId,
                pageable);
    }

    @Transactional(readOnly = true)
    public Page<Post> getMyPosts(Long authorId, Pageable pageable) {
        return postRepository.findPostsByAuthorId(authorId, pageable);
    }

    /**
     * Hủy / Xóa bài viết của tác giả (hoặc Staff/Admin)
     */
    @Transactional
    public void deletePost(Long postId, Long currentUserId, boolean isStaffOrAdmin) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài viết ID: " + postId));

        if (!isStaffOrAdmin && (post.getAuthor() == null || !post.getAuthor().getId().equals(currentUserId))) {
            throw new SecurityException("Bạn không có quyền xóa bài viết này.");
        }

        post.setIsDeleted(true);
        postRepository.save(post);
        log.info("Người dùng ID {} đã xóa bài viết ID {}", currentUserId, postId);
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
        return mapToDto(post, null);
    }

    private PostResponseDto mapToDto(Post post, List<PostAttachment> explicitAttachments) {
        List<PostAttachmentDto> attachmentDtos = null;
        List<PostAttachment> attachmentsToMap = (explicitAttachments != null) ? explicitAttachments : post.getAttachments();
        if (attachmentsToMap != null) {
            attachmentDtos = attachmentsToMap.stream()
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

        String authorRole = "STUDENT";
        String authorAvatar = null;
        String authorUsername = null;
        if (post.getAuthor() != null) {
            authorUsername = post.getAuthor().getUsername();
            authorAvatar = post.getAuthor().getAvatarUrl();
            if (post.getAuthor().getRole() != null) {
                authorRole = post.getAuthor().getRole().getName().replace("ROLE_", "");
            }
        }

        long commentCount = commentRepository.countByPostIdAndIsDeletedFalse(post.getId());

        return PostResponseDto.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .postType(post.getPostType())
                .status(post.getStatus())
                .videoStatus(post.getVideoStatus())
                .videoUrl(post.getVideoUrl())
                .isPinned(post.getIsPinned())
                .viewCount(post.getViewCount() != null ? post.getViewCount() : 0)
                .likeCount(post.getLikeCount() != null ? post.getLikeCount() : 0)
                .commentCount((int) commentCount)
                .rejectionReason(post.getRejectionReason())
                .departmentId(post.getDepartment() != null ? post.getDepartment().getId() : null)
                .departmentName(post.getDepartment() != null ? post.getDepartment().getName() : null)
                .authorId(post.getAuthor() != null ? post.getAuthor().getId() : null)
                .authorName(post.getAuthor() != null ? post.getAuthor().getFullName() : null)
                .authorUsername(authorUsername)
                .authorRole(authorRole)
                .authorAvatar(authorAvatar)
                .attachments(attachmentDtos)
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }

    public CommentDto mapCommentToDto(Comment cmt) {
        String roleName = "STUDENT";
        String deptName = null;
        String avatar = null;
        String username = null;
        String fullName = "Thành viên";
        Long authorId = null;

        if (cmt.getAuthor() != null) {
            authorId = cmt.getAuthor().getId();
            fullName = cmt.getAuthor().getFullName();
            username = cmt.getAuthor().getUsername();
            avatar = cmt.getAuthor().getAvatarUrl();
            if (cmt.getAuthor().getRole() != null) {
                roleName = cmt.getAuthor().getRole().getName().replace("ROLE_", "");
            }
            if (cmt.getAuthor().getDepartment() != null) {
                deptName = cmt.getAuthor().getDepartment().getName();
            }
        }

        return CommentDto.builder()
                .id(cmt.getId())
                .postId(cmt.getPost() != null ? cmt.getPost().getId() : null)
                .authorId(authorId)
                .authorName(fullName)
                .authorUsername(username)
                .authorRole(roleName)
                .authorDepartment(deptName)
                .authorAvatar(avatar)
                .content(cmt.getContent())
                .parentId(cmt.getParent() != null ? cmt.getParent().getId() : null)
                .likeCount(cmt.getLikeCount() != null ? cmt.getLikeCount() : 0)
                .createdAt(cmt.getCreatedAt())
                .replies(new ArrayList<>())
                .build();
    }
}
