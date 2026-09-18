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

    private List<PostAttachmentDto> attachments;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
