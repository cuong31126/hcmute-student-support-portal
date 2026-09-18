package com.school.counseling.module.ticket.dto;

/**
 * Interface Projection gọn nhẹ cho Security Authorization Evaluator.
 * Chỉ select các trường ID cần thiết, tránh load toàn bộ Entity Ticket nặng
 * và loại trừ triệt để nguy cơ LazyInitializationException.
 */
public interface TicketAccessAuthInfo {

    Long getId();

    Long getCreatorId();

    Long getDepartmentId();
}
