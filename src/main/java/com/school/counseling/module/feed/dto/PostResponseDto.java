package com.school.counseling.module.feed.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostResponseDto {

    private Long id;
    private String title;
    private String content;
    private String postType;
    private String status;
    private String videoStatus;
    private String videoUrl;
    private Boolean isPinned;
    private Integer viewCount;

    private Long departmentId;
    private String departmentName;

    private Long authorId;
    private String authorName;
    private String authorEmail;
    private String authorUsername;
    private String authorRole;
    private String authorAvatar;

    public record AuthorSummary(Long id, String fullName, String email, String username) {}

    public AuthorSummary getAuthor() {
        if (authorId == null && authorName == null) {
            return null;
        }
        return new AuthorSummary(authorId, authorName != null ? authorName : "Sinh viên", authorEmail, authorUsername);
    }

    public String getAuthorFullName() {
        return authorName != null ? authorName : "Sinh viên";
    }

    private Integer likeCount;
    private Integer commentCount;
    private boolean userLiked;
    private String rejectionReason;

    private List<PostAttachmentDto> attachments;
    private List<CommentDto> comments;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isYoutubeVideo() {
        if (videoUrl == null) return false;
        String lower = videoUrl.toLowerCase().trim();
        return lower.contains("youtube.com") || lower.contains("youtu.be");
    }

    public boolean getYoutubeVideo() {
        return isYoutubeVideo();
    }

    public String getYoutubeEmbedUrl() {
        if (!isYoutubeVideo()) return videoUrl;
        try {
            String url = videoUrl.trim();
            if (url.contains("youtu.be/")) {
                String id = url.substring(url.indexOf("youtu.be/") + 9);
                if (id.contains("?")) id = id.substring(0, id.indexOf("?"));
                return "https://www.youtube.com/embed/" + id;
            }
            if (url.contains("watch?v=")) {
                String id = url.substring(url.indexOf("watch?v=") + 8);
                if (id.contains("&")) id = id.substring(0, id.indexOf("&"));
                return "https://www.youtube.com/embed/" + id;
            }
            if (url.contains("/embed/")) {
                return url;
            }
        } catch (Exception ignored) {}
        return videoUrl;
    }

    public boolean isDriveVideo() {
        if (videoUrl == null) return false;
        return videoUrl.toLowerCase().contains("drive.google.com");
    }

    public boolean getDriveVideo() {
        return isDriveVideo();
    }

    public String getDriveEmbedUrl() {
        if (!isDriveVideo()) return videoUrl;
        if (videoUrl.contains("/view")) {
            return videoUrl.replace("/view", "/preview");
        }
        return videoUrl;
    }

    public boolean isDirectVideo() {
        if (videoUrl == null || videoUrl.isBlank()) return false;
        return !isYoutubeVideo() && !isDriveVideo();
    }

    public boolean getDirectVideo() {
        return isDirectVideo();
    }
}
