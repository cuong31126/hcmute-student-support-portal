package com.school.counseling.module.feed.controller;

import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/feed/forum")
@RequiredArgsConstructor
public class ForumController {

    private final PostService postService;
    private final UserRepository userRepository;

    /**
     * Diễn đàn thảo luận sinh viên (hiển thị các bài viết đã duyệt)
     */
    @GetMapping
    public String viewStudentForum(
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {

        Pageable pageable = PageRequest.of(Math.max(0, page), 10);
        Page<Post> posts = postService.getApprovedForumPosts(pageable);

        model.addAttribute("posts", posts);
        model.addAttribute("currentPage", page);
        return "feed/forum";
    }

    /**
     * Sinh viên đăng bài thảo luận mới (Trạng thái PENDING_APPROVAL)
     */
    @PostMapping("/create")
    @PreAuthorize("isAuthenticated()")
    public String handleCreateForumPost(
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam(value = "image", required = false) MultipartFile image,
            RedirectAttributes redirectAttributes) {

        if (title.isBlank() || content.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Tiêu đề và nội dung bài viết không được để trống.");
            return "redirect:/feed/forum";
        }

        Long currentUserId = SecurityUtils.getCurrentUserId().orElseThrow();
        User author = userRepository.findById(currentUserId).orElseThrow();

        postService.createForumPost(title, content, image, author);

        redirectAttributes.addFlashAttribute("successMessage",
                "Bài thảo luận đã được gửi thành công! Theo quy định, bài viết sẽ hiển thị công khai sau khi được Cán bộ phê duyệt.");
        return "redirect:/feed/forum";
    }

    /**
     * Thả tim (Like) bài viết
     */
    @PostMapping("/{id}/like")
    public String handleLikePost(@PathVariable("id") Long id) {
        postService.likePost(id);
        return "redirect:/feed/forum#post_" + id;
    }

    /**
     * Bình luận vào bài viết
     */
    @PostMapping("/{id}/comment")
    @PreAuthorize("isAuthenticated()")
    public String handleAddComment(
            @PathVariable("id") Long id,
            @RequestParam("content") String content,
            RedirectAttributes redirectAttributes) {

        if (content == null || content.trim().isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Nội dung bình luận không được để trống.");
            return "redirect:/feed/forum#post_" + id;
        }

        Long currentUserId = SecurityUtils.getCurrentUserId().orElseThrow();
        User author = userRepository.findById(currentUserId).orElseThrow();

        postService.addComment(id, content, author);

        return "redirect:/feed/forum#post_" + id;
    }

    /**
     * Báo cáo bài viết vi phạm
     */
    @PostMapping("/{id}/report")
    @PreAuthorize("isAuthenticated()")
    public String handleReportPost(
            @PathVariable("id") Long id,
            @RequestParam("reason") String reason,
            @RequestParam(value = "details", required = false) String details,
            RedirectAttributes redirectAttributes) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElseThrow();
        User reporter = userRepository.findById(currentUserId).orElseThrow();

        postService.reportPost(id, reason, details, reporter);

        redirectAttributes.addFlashAttribute("successMessage",
                "Cảm ơn bạn đã gửi báo cáo vi phạm. Ban Quản Trị sẽ xem xét và xử lý trong thời gian sớm nhất.");
        return "redirect:/feed/forum";
    }
}
