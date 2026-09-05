package com.school.counseling.module.chat.service;

import com.school.counseling.common.exception.ResourceNotFoundException;
import com.school.counseling.common.storage.IStorageService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.chat.dto.ChatMessageDto;
import com.school.counseling.module.chat.dto.ConversationDto;
import com.school.counseling.module.chat.entity.Attachment;
import com.school.counseling.module.chat.entity.Conversation;
import com.school.counseling.module.chat.entity.Message;
import com.school.counseling.module.chat.repository.AttachmentRepository;
import com.school.counseling.module.chat.repository.ConversationRepository;
import com.school.counseling.module.chat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final IStorageService storageService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ConversationDto createConversation(ConversationDto dto, Long currentUserId) {
        Department department = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Đơn vị", "id", dto.getDepartmentId()));

        User creator = null;
        if (currentUserId != null) {
            creator = userRepository.findById(currentUserId).orElse(null);
        }

        Conversation conversation = Conversation.builder()
                .title(dto.getTitle())
                .department(department)
                .creator(creator)
                .guestEmail(dto.getGuestEmail())
                .status("ACTIVE")
                .build();

        conversation = conversationRepository.save(conversation);
        log.info("Khởi tạo cuộc hội thoại mới: ID={}, Title={}", conversation.getId(), conversation.getTitle());

        return mapToConversationDto(conversation);
    }

    @Transactional
    public ChatMessageDto sendMessage(Long conversationId, String content, String senderType, Long senderId, MultipartFile file) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cuộc hội thoại", "id", conversationId));

        User sender = null;
        if (senderId != null) {
            sender = userRepository.findById(senderId).orElse(null);
        }

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .senderType(senderType != null ? senderType : "STUDENT")
                .content(content)
                .build();

        message = messageRepository.save(message);

        List<ChatMessageDto.AttachmentDto> attachmentDtos = new ArrayList<>();
        if (file != null && !file.isEmpty()) {
            IStorageService.StorageResult uploadResult = storageService.uploadFile(file, "chat_attachments");
            Attachment attachment = Attachment.builder()
                    .fileName(uploadResult.originalFileName())
                    .fileUrl(uploadResult.publicUrl())
                    .fileType(uploadResult.fileType())
                    .fileSize(uploadResult.fileSizeBytes())
                    .message(message)
                    .build();

            attachment = attachmentRepository.save(attachment);
            attachmentDtos.add(ChatMessageDto.AttachmentDto.builder()
                    .id(attachment.getId())
                    .fileName(attachment.getFileName())
                    .fileUrl(attachment.getFileUrl())
                    .fileType(attachment.getFileType())
                    .fileSize(attachment.getFileSize())
                    .build());
        }

        ChatMessageDto responseDto = ChatMessageDto.builder()
                .id(message.getId())
                .conversationId(conversation.getId())
                .senderId(sender != null ? sender.getId() : null)
                .senderName(sender != null ? sender.getFullName() : (conversation.getGuestEmail() != null ? conversation.getGuestEmail() : "Khách vãng lai"))
                .senderType(message.getSenderType())
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .attachments(attachmentDtos)
                .build();

        // Broadcast tin nhắn qua WebSocket STOMP tới tất cả Client đang lắng nghe phòng chat này
        String destination = "/topic/conversation/" + conversationId;
        messagingTemplate.convertAndSend(destination, responseDto);
        log.info("Broadcast tin nhắn WebSocket tới: {}", destination);

        return responseDto;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> getConversationHistory(Long conversationId) {
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return messages.stream().map(this::mapToMessageDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getConversationsByUser(Long userId) {
        return conversationRepository.findByCreatorIdOrderByCreatedAtDesc(userId)
                .stream().map(this::mapToConversationDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getConversationsByDepartment(Long departmentId) {
        return conversationRepository.findByDepartmentIdAndStatusOrderByCreatedAtDesc(departmentId, "ACTIVE")
                .stream().map(this::mapToConversationDto).collect(Collectors.toList());
    }

    private ConversationDto mapToConversationDto(Conversation c) {
        return ConversationDto.builder()
                .id(c.getId())
                .title(c.getTitle())
                .creatorId(c.getCreator() != null ? c.getCreator().getId() : null)
                .creatorName(c.getCreator() != null ? c.getCreator().getFullName() : null)
                .guestEmail(c.getGuestEmail())
                .departmentId(c.getDepartment().getId())
                .departmentName(c.getDepartment().getName())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .totalMessages(c.getMessages() != null ? c.getMessages().size() : 0)
                .build();
    }

    private ChatMessageDto mapToMessageDto(Message m) {
        List<ChatMessageDto.AttachmentDto> atts = m.getAttachments().stream()
                .map(a -> ChatMessageDto.AttachmentDto.builder()
                        .id(a.getId())
                        .fileName(a.getFileName())
                        .fileUrl(a.getFileUrl())
                        .fileType(a.getFileType())
                        .fileSize(a.getFileSize())
                        .build())
                .collect(Collectors.toList());

        return ChatMessageDto.builder()
                .id(m.getId())
                .conversationId(m.getConversation().getId())
                .senderId(m.getSender() != null ? m.getSender().getId() : null)
                .senderName(m.getSender() != null ? m.getSender().getFullName() : "Khách vãng lai")
                .senderType(m.getSenderType())
                .content(m.getContent())
                .createdAt(m.getCreatedAt())
                .attachments(atts)
                .build();
    }
}
