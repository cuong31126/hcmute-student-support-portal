package com.school.counseling.module.ticket.service;

import com.school.counseling.common.exception.AccessDeniedBusinessException;
import com.school.counseling.common.exception.ResourceNotFoundException;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final AttachmentRepository attachmentRepository;
    private final SlaCalculatorService slaCalculatorService;
    private final IStorageService storageService;

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

        // Lưu các file đính kèm nếu có
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
                            .build();
                    attachmentRepository.save(attachment);
                }
            }
        }

        // Ghi nhật ký khởi tạo
        saveHistory(ticket, creator, creator != null ? creator.getFullName() : (request.getGuestName() != null ? request.getGuestName() : "Khách vãng lai"),
                null, "OPEN", "Khởi tạo yêu cầu tư vấn mới");

        log.info("Tạo mới Ticket thành công: Code={}, DueDate={}", ticket.getTicketCode(), ticket.getDueDate());
        return mapToDto(ticket);
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

        // Ghép nội dung tóm tắt từ các tin nhắn nếu description không nhập
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

        // Kế thừa toàn bộ file đính kèm từ phiên chat sang Ticket
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

        // Cập nhật trạng thái cuộc hội thoại
        conversation.setStatus("CONVERTED_TO_TICKET");
        conversationRepository.save(conversation);

        // Ghi lịch sử
        saveHistory(ticket, creator, creator != null ? creator.getFullName() : "Hệ thống / Sinh viên",
                null, "OPEN", "Chuyển đổi từ cuộc hội thoại Chat ID: " + conversationId);

        log.info("Chuyển đổi Conversation ID={} thành Ticket Code={}", conversationId, ticket.getTicketCode());
        return mapToDto(ticket);
    }

    @Transactional
    public TicketResponseDto claimTicket(Long ticketId, Long staffId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));

        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Cán bộ", "id", staffId));

        // Kiểm tra phân quyền Khoa/Phòng
        if (!"ROLE_ADMIN".equalsIgnoreCase(staff.getRole().getName()) &&
                !Objects.equals(staff.getDepartment().getId(), ticket.getDepartment().getId())) {
            throw new AccessDeniedBusinessException("Cán bộ chỉ được tiếp nhận Ticket thuộc đơn vị của mình");
        }

        String fromStatus = ticket.getStatus();
        ticket.setAssignedTo(staff);
        ticket.setStatus("IN_PROGRESS");
        ticket = ticketRepository.save(ticket);

        saveHistory(ticket, staff, staff.getFullName(), fromStatus, "IN_PROGRESS", "Cán bộ tiếp nhận xử lý yêu cầu");
        log.info("Cán bộ ID={} đã tiếp nhận Ticket Code={}", staffId, ticket.getTicketCode());

        return mapToDto(ticket);
    }

    @Transactional
    public TicketResponseDto replyTicket(Long ticketId, TicketReplyRequest request, Long responderId, List<MultipartFile> files) {
        Ticket ticket = ticketRepository.findById(ticketId)
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

        // Lưu file đính kèm mới nếu có
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
                            .build();
                    attachmentRepository.save(attachment);
                }
            }
        }

        saveHistory(ticket, responder, responderName, fromStatus, toStatus, request.getContent());
        log.info("Phản hồi Ticket Code={}: Status={}", ticket.getTicketCode(), toStatus);

        return mapToDto(ticket);
    }

    @Transactional
    public TicketResponseDto resolveTicket(Long ticketId, Long staffId, String resolutionNote) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));

        User staff = userRepository.findById(staffId).orElse(null);
        String fromStatus = ticket.getStatus();

        ticket.setStatus("RESOLVED");
        ticket.setResolvedAt(LocalDateTime.now());
        ticket = ticketRepository.save(ticket);

        saveHistory(ticket, staff, staff != null ? staff.getFullName() : "Cán bộ",
                fromStatus, "RESOLVED", resolutionNote != null ? resolutionNote : "Đã hoàn tất giải đáp yêu cầu");

        return mapToDto(ticket);
    }

    @Transactional
    public TicketResponseDto closeTicket(Long ticketId, Long userId, Integer rating) {
        Ticket ticket = ticketRepository.findById(ticketId)
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

        return mapToDto(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponseDto getTicketById(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "id", ticketId));
        return mapToDto(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponseDto getTicketByCode(String ticketCode) {
        Ticket ticket = ticketRepository.findByTicketCode(ticketCode)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "code", ticketCode));
        return mapToDto(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponseDto getTicketByGuestToken(String token) {
        Ticket ticket = ticketRepository.findByGuestToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "token", token));
        return mapToDto(ticket);
    }

    @Transactional(readOnly = true)
    public List<TicketResponseDto> getTicketsByDepartment(Long departmentId, String status) {
        List<Ticket> list;
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            list = ticketRepository.findByDepartmentIdAndStatusOrderByCreatedAtDesc(departmentId, status);
        } else {
            list = ticketRepository.findByDepartmentIdOrderByCreatedAtDesc(departmentId);
        }
        return list.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TicketResponseDto> getMyTickets(Long userId) {
        return ticketRepository.findByCreatorIdOrderByCreatedAtDesc(userId)
                .stream().map(this::mapToDto).collect(Collectors.toList());
    }

    private void saveHistory(Ticket ticket, User actor, String actorName, String fromStatus, String toStatus, String note) {
        TicketHistory history = TicketHistory.builder()
                .ticket(ticket)
                .actor(actor)
                .actorName(actorName)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actionNote(note)
                .build();
        ticketHistoryRepository.save(history);
    }

    private String generateTicketCode() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randomSuffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return "TK-" + dateStr + "-" + randomSuffix;
    }

    private TicketResponseDto mapToDto(Ticket t) {
        List<AttachmentDto> attachments = attachmentRepository.findByTicketId(t.getId()).stream()
                .map(a -> AttachmentDto.builder()
                        .id(a.getId())
                        .fileName(a.getFileName())
                        .fileUrl(a.getFileUrl())
                        .fileType(a.getFileType())
                        .fileSize(a.getFileSize())
                        .build())
                .collect(Collectors.toList());

        List<TicketResponseDto.HistoryDto> histories = ticketHistoryRepository.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                .map(h -> TicketResponseDto.HistoryDto.builder()
                        .id(h.getId())
                        .actorName(h.getActorName())
                        .fromStatus(h.getFromStatus())
                        .toStatus(h.getToStatus())
                        .actionNote(h.getActionNote())
                        .createdAt(h.getCreatedAt())
                        .build())
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
                .histories(histories)
                .build();
    }
}
