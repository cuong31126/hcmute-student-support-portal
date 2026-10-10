package com.school.counseling.module.ticket.dto;
 
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
 
import java.util.List;
 
/**
 * DTO chứa kết quả phân tích tự động Ticket bằng AI (Khoa/Phòng phụ trách, Mức độ ưu tiên, Cảm xúc).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketTriageResponseDto {
    private Long suggestedDepartmentId;
    private String departmentCode;
    private String departmentName;
    private String priority;
    private String sentiment;
    private Double confidenceScore;
    private String summary;
    private String suggestedAction;
    private List<String> keyIndicators;
    private String classifiedBy;
}
