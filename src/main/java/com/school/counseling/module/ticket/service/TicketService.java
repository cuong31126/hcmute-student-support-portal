package com.school.counseling.module.ticket.service;

import com.school.counseling.common.exception.AccessDeniedBusinessException;
import com.school.counseling.common.exception.ResourceNotFoundException;
import com.school.counseling.common.exception.TicketAlreadyClaimedException;
import com.school.counseling.common.storage.IStorageService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.chat.dto.ChatMessageDto.AttachmentDto;
import com.school.counseling.module.chat.entity.Attachment;
import com.school.counseling.module.chat.entity.Conversation;
import com.school.counseling.module.chat.entity.Message;
import com.school.counseling.module.chat.repository.AttachmentRepository;
import com.school.counseling.module.chat.repository.ConversationRepository;
import com.school.counseling.module.ticket.dto.CreateTicketRequest;
import com.school.counseling.module.ticket.dto.TicketReplyRequest;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.dto.TicketSummaryDto;
import com.school.counseling.module.ticket.entity.Ticket;
import com.school.counseling.module.ticket.entity.TicketHistory;
import com.school.counseling.module.ticket.repository.TicketHistoryRepository;
import com.school.counseling.module.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * TicketService - Service xử lý toàn bộ vòng đời Ticket.
 *
 * Các cải tiến quan trọng trong phiên bản này:
 * 1. claimTicket: Dùng Atomic Update Query (claimTicketAtomic) thay thế findById+save để
 *    loại bỏ hoàn toàn Race Condition khi nhiều Cán bộ cùng nhận vé.
 * 2. getTicketsByDepartment: Trả về TicketSummaryDto thay vì TicketResponseDto để
 *    tránh N+1 Query (chỉ 1 SQL thay vì N*3 SQL).
 * 3. getTicketById: Dùng findByIdWithRelations (JOIN FETCH) để tương thích OSIV=false.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // Mặc định readOnly=true; các phương thức ghi override lại
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final AttachmentRepository attachmentRepository;
    private final SlaCalculatorService slaCalculatorService;
    private final IStorageService storageService;

    // ─── NHÓM TẠO / CHUYỂN ĐỔI TICKET ───────────────────────────────────────────────────

    @Transactional
    public TicketResponseDto createTicket(CreateTicketRequest request, Long creatorId, List<MultipartFile> files) {
        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Đơn vị", "id", request.getDepartmentId()));

        User creator = null;
        String guestToken = null;
        if (creatorId != null) {
            creator = userRepository.findById(creatorId).orElse(null);
        } else {
            guestToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        String priority = request.getPriority() != null ? request.getPriority().toUpperCase() : "MEDIUM";
        LocalDateTime dueDate = slaCalculatorService.calculateDueDate(priority);
        String ticketCode = generateTicketCode();

        Ticket ticket = Ticket.builder()
                .ticketCode(ticketCode)
                .title(request.getTitle())
                .description(request.getDescription())
                .department(department)
                .creator(creator)
                .guestName(request.getGuestName())
                .guestEmail(request.getGuestEmail())
                .guestToken(guestToken)
                .priority(priority)
                .status("OPEN")
                .dueDate(dueDate)
                .build();

        ticket = ticketRepository.save(ticket);

        TicketHistory initialHistory = saveHistory(ticket, creator,
                creator != null ? creator.getFullName() : (request.getGuestName() != null ? request.getGuestName() : "Khách vãng lai"),
                null, "OPEN", "Khởi tạo yêu cầu tư vấn mới: " + ticket.getDescription());

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    IStorageService.StorageResult res = storageService.uploadFile(file, "ticket_attachments");
                    Attachment attachment = Attachment.builder()
                            .fileName(res.originalFileName())
                            .fileUrl(res.publicUrl())
                            .fileType(res.fileType())
                            .fileSize(res.fileSizeBytes())
                            .ticket(ticket)
                            .ticketHistory(initialHistory)
                            .build();
                    attachmentRepository.save(attachment);
                }
            }
        }

        log.info("Tạo mới Ticket thành công: Code={}, DueDate={}", ticket.getTicketCode(), ticket.getDueDate());
        return mapToDetailDto(ticket);
    }

    @Transactional
    public TicketResponseDto convertConversationToTicket(Long conversationId, CreateTicketRequest request, Long currentUserId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cuộc hội thoại", "id", conversationId));

        Department department = conversation.getDepartment();
        User creator = conversation.getCreator();
        if (creator == null && currentUserId != null) {
            creator = userRepository.findById(currentUserId).orElse(null);
        }

        String guestToken = (creator == null) ? UUID.randomUUID().toString().replace("-", "").substring(0, 16) : null;
        String priority = request.getPriority() != null ? request.getPriority().toUpperCase() : "MEDIUM";
        LocalDateTime dueDate = slaCalculatorService.calculateDueDate(priority);
        String ticketCode = generateTicketCode();

        String desc = request.getDescription();
        if (desc == null || desc.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Message m : conversation.getMessages()) {
                sb.append("[").append(m.getSenderType()).append("]: ").append(m.getContent()).append("\n");
            }
            desc = sb.toString();
        }

        Ticket ticket = Ticket.builder()
                .ticketCode(ticketCode)
                .title(request.getTitle() != null ? request.getTitle() : conversation.getTitle())
                .description(desc)
                .conversation(conversation)
                .department(department)
                .creator(creator)
                .guestEmail(conversation.getGuestEmail())
                .guestToken(guestToken)
                .priority(priority)
                .status("OPEN")
                .dueDate(dueDate)
                .build();

        ticket = ticketRepository.save(ticket);

        for (Message msg : conversation.getMessages()) {
            for (Attachment att : msg.getAttachments()) {
                Attachment ticketAtt = Attachment.builder()
                        .fileName(att.getFileName())
                        .fileUrl(att.getFileUrl())
                        .fileType(att.getFileType())
                        .fileSize(att.getFileSize())
                        .ticket(ticket)
                        .build();
                attachmentRepository.save(ticketAtt);
            }
        }

        conversation.setStatus("CONVERTED_TO_TICKET");
        conversationRepository.save(conversation);

        saveHistory(ticket, creator, creator != null ? creator.getFullName() : "Hệ thống / Sinh viên",
                null, "OPEN", "Chuyển đổi từ cuộc hội thoại Chat ID: " + conversationId);

        log.info("Chuyển đổi Conversation ID={} thành Ticket Code={}", conversationId, ticket.getTicketCode());
        return mapToDetailDto(ticket);
    }

    // ─── NHÓM THAO TÁC TRẠNG THÁI TICKET ────────────────────────────────────────────────

    /**
     * Nhận xử lý Ticket (Claim Ticket) bằng Atomic Update Query.
     *
     * Cơ chế chống Race Condition:
     * - MySQL InnoDB thực thi Exclusive Row-Level Lock (X-Lock) trên dòng ticket được UPDATE.
     * - Điều kiện WHERE chặt chẽ: status='OPEN' AND assignedTo IS NULL
     * - Nếu Cán bộ A nhận trước → Cán bộ B nhận sau sẽ thấy status='IN_PROGRESS' → WHERE không khớp → trả về 0
     * - Tuyệt đối KHÔNG dùng findById + save (non-atomic) cho thao tác này.
     */
    @Transactional
    public TicketResponseDto claimTicket(Long ticketId, Long staffId) {
        // Bước 1: Tải thông tin Staff (ngoài transaction tranh chấp)
        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Cán bộ", "id", staffId));

        // Bước 2: Kiểm tra phân quyền Khoa/Phòng trước khi cố nhận vé
        TicketAccessAuthCheck(ticketId, staff);

        // Bước 3: Atomic Update — kết quả = 1: thành công, = 0: đã có người nhận trước
        int updatedRows = ticketRepository.claimTicketAtomic(ticketId, staff);
        if (updatedRows == 0) {
            throw new TicketAlreadyClaimedException(
                    "Yêu cầu hỗ trợ này đã được Cán bộ khác tiếp nhận xử lý rồi. Vui lòng làm mới trang Dashboard.");
        }

        // Bước 4: Tải lại entity với JOIN FETCH sau khi atomic update thành công
        Ticket ticket = ticketRepository.findByIdWithRelations(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));

        saveHistory(ticket, staff, staff.getFullName(), "OPEN", "IN_PROGRESS", "Cán bộ tiếp nhận xử lý yêu cầu");
        log.info("Cán bộ ID={} đã tiếp nhận thành công Ticket Code={}", staffId, ticket.getTicketCode());

        return mapToDetailDto(ticket);
    }

    @Transactional
    public TicketResponseDto replyTicket(Long ticketId, TicketReplyRequest request, Long responderId, List<MultipartFile> files) {
        Ticket ticket = ticketRepository.findByIdWithRelations(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));

        User responder = responderId != null ? userRepository.findById(responderId).orElse(null) : null;
        String responderName = responder != null ? responder.getFullName() : "Cán bộ tư vấn";

        String fromStatus = ticket.getStatus();
        String toStatus = fromStatus;

        if ("RESOLVED".equalsIgnoreCase(request.getNewStatus())) {
            toStatus = "RESOLVED";
            ticket.setStatus("RESOLVED");
            ticket.setResolvedAt(LocalDateTime.now());
        } else if ("WAITING_STUDENT".equalsIgnoreCase(request.getNewStatus())) {
            toStatus = "WAITING_STUDENT";
            ticket.setStatus("WAITING_STUDENT");
        }

        ticket = ticketRepository.save(ticket);

        TicketHistory replyHistory = saveHistory(ticket, responder, responderName, fromStatus, toStatus, request.getContent());

        if (files != null && !files.isEmpty()) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    IStorageService.StorageResult res = storageService.uploadFile(file, "ticket_replies");
                    Attachment attachment = Attachment.builder()
                            .fileName(res.originalFileName())
                            .fileUrl(res.publicUrl())
                            .fileType(res.fileType())
                            .fileSize(res.fileSizeBytes())
                            .ticket(ticket)
                            .ticketHistory(replyHistory)
                            .build();
                    attachmentRepository.save(attachment);
                }
            }
        }

        log.info("Phản hồi Ticket Code={}: Status={}", ticket.getTicketCode(), toStatus);

        return mapToDetailDto(ticket);
    }

    @Transactional
    public TicketResponseDto resolveTicket(Long ticketId, Long staffId, String resolutionNote) {
        Ticket ticket = ticketRepository.findByIdWithRelations(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));

        User staff = userRepository.findById(staffId).orElse(null);
        String fromStatus = ticket.getStatus();

        ticket.setStatus("RESOLVED");
        ticket.setResolvedAt(LocalDateTime.now());
        ticket = ticketRepository.save(ticket);

        saveHistory(ticket, staff, staff != null ? staff.getFullName() : "Cán bộ",
                fromStatus, "RESOLVED", resolutionNote != null ? resolutionNote : "Đã hoàn tất giải đáp yêu cầu");

        return mapToDetailDto(ticket);
    }

    @Transactional
    public TicketResponseDto closeTicket(Long ticketId, Long userId, Integer rating) {
        Ticket ticket = ticketRepository.findByIdWithRelations(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));

        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        String fromStatus = ticket.getStatus();

        ticket.setStatus("CLOSED");
        ticket.setClosedAt(LocalDateTime.now());
        if (rating != null && rating >= 1 && rating <= 5) {
            ticket.setRating(rating);
        }
        ticket = ticketRepository.save(ticket);

        saveHistory(ticket, user, user != null ? user.getFullName() : "Sinh viên",
                fromStatus, "CLOSED", "Đóng yêu cầu (Đánh giá: " + (rating != null ? rating + " sao" : "Không") + ")");

        return mapToDetailDto(ticket);
    }

    // ─── NHÓM TRUY VẤN / ĐỌC DỮ LIỆU ───────────────────────────────────────────────────

    /**
     * Trả về TicketResponseDto đầy đủ (bao gồm history + attachments) cho trang chi tiết.
     * Dùng findByIdWithRelations (JOIN FETCH) để tương thích OSIV=false.
     */
    public TicketResponseDto getTicketById(Long ticketId) {
        Ticket ticket = ticketRepository.findByIdWithRelations(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));
        return mapToDetailDto(ticket);
    }

    public TicketResponseDto getTicketByCode(String ticketCode) {
        Ticket ticket = ticketRepository.findByTicketCodeWithRelations(ticketCode)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "code", ticketCode));
        return mapToDetailDto(ticket);
    }

    public TicketResponseDto getTicketByGuestToken(String token) {
        Ticket ticket = ticketRepository.findByGuestTokenWithRelations(token)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "token", token));
        return mapToDetailDto(ticket);
    }

    /**
     * Trả về TicketSummaryDto (nhẹ) cho Dashboard Cán bộ.
     * 1 SQL duy nhất thay vì N*3 SQL nhờ JPQL Constructor Expression.
     */
    public List<TicketSummaryDto> getTicketSummaryByDepartment(Long departmentId, String status) {
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            return ticketRepository.findSummaryByDepartmentIdAndStatus(departmentId, status);
        }
        return ticketRepository.findSummaryByDepartmentId(departmentId);
    }

    public List<TicketSummaryDto> getAllTicketSummaries(String status) {
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            return ticketRepository.findAllSummariesByStatus(status);
        }
        return ticketRepository.findAllSummaries();
    }

    public List<TicketSummaryDto> getMyTicketSummaries(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return ticketRepository.findSummaryByCreatorId(userId);
    }

    // ─── PRIVATE HELPERS ─────────────────────────────────────────────────────────────────

    /**
     * Kiểm tra phân quyền Khoa/Phòng: Cán bộ chỉ được thao tác trên Ticket thuộc đơn vị của mình.
     * Tải thông tin sở hữu tối thiểu (chỉ 3 trường) để tránh load toàn bộ Entity.
     */
    private void TicketAccessAuthCheck(Long ticketId, User staff) {
        if ("ROLE_ADMIN".equalsIgnoreCase(staff.getRole().getName())) return; // Admin bỏ qua
        ticketRepository.findAccessAuthInfoById(ticketId).ifPresent(authInfo -> {
            if (!Objects.equals(authInfo.getDepartmentId(), staff.getDepartment().getId())) {
                throw new AccessDeniedBusinessException(
                        "Cán bộ chỉ được tiếp nhận Ticket thuộc đơn vị của mình!");
            }
        });
    }

    private TicketHistory saveHistory(Ticket ticket, User actor, String actorName,
                             String fromStatus, String toStatus, String note) {
        TicketHistory history = TicketHistory.builder()
                .ticket(ticket)
                .actor(actor)
                .actorName(actorName)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actionNote(note)
                .build();
        return ticketHistoryRepository.save(history);
    }

    private String generateTicketCode() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randomSuffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return "TK-" + dateStr + "-" + randomSuffix;
    }

    /**
     * mapToDetailDto: Chỉ dùng cho trang Chi tiết Ticket.
     * Yêu cầu: Ticket đã được load đầy đủ dept/creator/assignedTo qua JOIN FETCH.
     */
    private TicketResponseDto mapToDetailDto(Ticket t) {
        List<Attachment> allAttachments = attachmentRepository.findByTicketId(t.getId());

        List<AttachmentDto> attachments = allAttachments.stream()
                .map(a -> AttachmentDto.builder()
                        .id(a.getId())
                        .fileName(a.getFileName())
                        .fileUrl(a.getFileUrl())
                        .fileType(a.getFileType())
                        .fileSize(a.getFileSize())
                        .build())
                .collect(Collectors.toList());

        List<TicketHistory> historyEntities = ticketHistoryRepository
                .findByTicketIdOrderByCreatedAtAsc(t.getId());

        // Phân loại: Lịch sử phản hồi (replies) và sự kiện hệ thống
        List<TicketResponseDto.HistoryDto> histories = historyEntities.stream()
                .filter(h -> {
                    // Loại bỏ bản ghi khởi tạo ban đầu để không bị trùng với Tin nhắn mở đầu (Message #1)
                    if (h.getFromStatus() == null && h.getActionNote() != null 
                            && (h.getActionNote().startsWith("Khởi tạo yêu cầu") || h.getActionNote().startsWith("Chuyển đổi từ cuộc hội thoại"))) {
                        return false;
                    }
                    return true;
                })
                .map(h -> {
                    List<AttachmentDto> matchedAtts = allAttachments.stream()
                            .filter(a -> {
                                if (a.getTicketHistory() != null) {
                                    return a.getTicketHistory().getId().equals(h.getId());
                                }
                                if (a.getCreatedAt() != null && h.getCreatedAt() != null) {
                                    long diffSeconds = Math.abs(java.time.Duration.between(a.getCreatedAt(), h.getCreatedAt()).getSeconds());
                                    return diffSeconds <= 15;
                                }
                                return false;
                            })
                            .map(a -> AttachmentDto.builder()
                                    .id(a.getId())
                                    .fileName(a.getFileName())
                                    .fileUrl(a.getFileUrl())
                                    .fileType(a.getFileType())
                                    .fileSize(a.getFileSize())
                                    .build())
                            .collect(Collectors.toList());

                    return TicketResponseDto.HistoryDto.builder()
                            .id(h.getId())
                            .actorId(h.getActor() != null ? h.getActor().getId() : null)
                            .actorRole(h.getActor() != null && h.getActor().getRole() != null ? h.getActor().getRole().getName() : null)
                            .actorName(h.getActorName())
                            .fromStatus(h.getFromStatus())
                            .toStatus(h.getToStatus())
                            .actionNote(h.getActionNote())
                            .createdAt(h.getCreatedAt())
                            .attachments(matchedAtts)
                            .build();
                })
                .collect(Collectors.toList());

        // Tệp đính kèm ban đầu của ticket: Tất cả các file không thuộc về lượt phản hồi nào
        java.util.Set<Long> replyAttachmentIds = histories.stream()
                .flatMap(h -> h.getAttachments().stream())
                .map(AttachmentDto::getId)
                .collect(Collectors.toSet());

        List<AttachmentDto> initialAttachments = attachments.stream()
                .filter(a -> !replyAttachmentIds.contains(a.getId()))
                .collect(Collectors.toList());

        boolean isOverdue = slaCalculatorService.isOverdue(t.getDueDate(), t.getStatus());
        boolean isDueSoon = slaCalculatorService.isDueSoon(t.getDueDate(), t.getStatus());

        return TicketResponseDto.builder()
                .id(t.getId())
                .ticketCode(t.getTicketCode())
                .title(t.getTitle())
                .description(t.getDescription())
                .departmentId(t.getDepartment().getId())
                .departmentName(t.getDepartment().getName())
                .creatorId(t.getCreator() != null ? t.getCreator().getId() : null)
                .creatorName(t.getCreator() != null ? t.getCreator().getFullName() : null)
                .guestName(t.getGuestName())
                .guestEmail(t.getGuestEmail())
                .guestToken(t.getGuestToken())
                .assignedStaffId(t.getAssignedTo() != null ? t.getAssignedTo().getId() : null)
                .assignedStaffName(t.getAssignedTo() != null ? t.getAssignedTo().getFullName() : null)
                .assignedStaffEmail(t.getAssignedTo() != null ? t.getAssignedTo().getEmail() : null)
                .assignedStaffPhone(t.getAssignedTo() != null ? t.getAssignedTo().getPhone() : null)
                .assignedStaffAvatarUrl(t.getAssignedTo() != null ? t.getAssignedTo().getAvatarUrl() : null)
                .assignedStaffRole(t.getAssignedTo() != null && t.getAssignedTo().getRole() != null ? t.getAssignedTo().getRole().getName() : null)
                .priority(t.getPriority())
                .status(t.getStatus())
                .dueDate(t.getDueDate())
                .isOverdue(isOverdue)
                .isDueSoon(isDueSoon)
                .createdAt(t.getCreatedAt())
                .resolvedAt(t.getResolvedAt())
                .closedAt(t.getClosedAt())
                .rating(t.getRating())
                .attachments(attachments)
                .initialAttachments(initialAttachments)
                .histories(histories)
                .build();
    }
}
