package com.school.counseling.module.feed.controller;

import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.dto.CreatePostRequest;
import com.school.counseling.module.feed.dto.PostResponseDto;
import com.school.counseling.module.feed.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/feed/official")
@RequiredArgsConstructor
public class OfficialFeedController {

    private final PostService postService;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    /**
     * Bảng tin thông báo chính thức công khai (Guest, Sinh viên, Cán bộ đều xem được)
     */
    @GetMapping
    public String listOfficialAnnouncements(
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {

        Pageable pageable = PageRequest.of(Math.max(0, page), 9);
        Page<PostResponseDto> posts = postService.getApprovedOfficialPosts(departmentId, query, pageable);
        List<Department> departments = departmentRepository.findByIsActiveTrue();

        model.addAttribute("posts", posts);
        model.addAttribute("departments", departments);
        model.addAttribute("selectedDept", departmentId);
        model.addAttribute("query", query);

        return "feed/official-list";
    }

    /**
     * Xem chi tiết thông báo chính thức kèm tài liệu đính kèm và video
     */
    @GetMapping("/{id}")
    public String viewOfficialPost(@PathVariable("id") Long id, Model model) {
        PostResponseDto post = postService.getPostById(id);
        model.addAttribute("post", post);
        return "feed/official-detail";
    }

    /**
     * Giao diện đăng bài thông báo mới dành cho Cán bộ & Quản trị viên
     */
    @GetMapping("/create")
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public String showCreateForm(Model model) {
        List<Department> departments = departmentRepository.findByIsActiveTrue();
        Long staffDeptId = SecurityUtils.getCurrentDepartmentId().orElse(null);

        CreatePostRequest request = new CreatePostRequest();
        if (staffDeptId != null) {
            request.setDepartmentId(staffDeptId);
        }

        model.addAttribute("postRequest", request);
        model.addAttribute("departments", departments);
        model.addAttribute("staffDeptId", staffDeptId);

        return "feed/create-official-post";
    }

    /**
     * Tiếp nhận và xử lý đăng thông báo chính thức
     */
    @PostMapping("/create")
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public String handleCreatePost(
            @Valid @ModelAttribute("postRequest") CreatePostRequest request,
            BindingResult bindingResult,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("departments", departmentRepository.findByIsActiveTrue());
            return "feed/create-official-post";
        }

        Long currentUserId = SecurityUtils.getCurrentUserId().orElseThrow();
        User author = userRepository.findById(currentUserId).orElseThrow();

        PostResponseDto created = postService.createOfficialPostWithUpload(request, attachment, author);

        redirectAttributes.addFlashAttribute("successMessage", "Đã đăng thông báo chính thức thành công!");
        return "redirect:/feed/official/" + created.getId();
    }
}
