package com.school.counseling.module.feed.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostReportResponseDto {
    private Long id;
    private PostSummary post;
    private ReporterSummary reporter;
    private String reason;
    private String details;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public record PostSummary(Long id, String title, String content, AuthorSummary author) {}
    public record AuthorSummary(Long id, String fullName, String email) {}
    public record ReporterSummary(Long id, String fullName, String email) {}
}
