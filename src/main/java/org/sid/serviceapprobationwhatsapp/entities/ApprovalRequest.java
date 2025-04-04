package org.sid.serviceapprobationwhatsapp.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.sid.serviceapprobationwhatsapp.enums.statut;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "approval_requests")
@Data
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ApprovalRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "demande_type", nullable = false)
    private String objectType;

    @Column(name = "demande_id", nullable = false)
    private String objectId;

    @Lob @Column(columnDefinition = "Text")
    private String data;

    private String origin;

    @ElementCollection
    private List<String> approvers;

    @Column(name = "demandeur", nullable = false)
    private String demandeur;

    private String commentaire;

    private String callbackUrl;

    @Lob @Column(columnDefinition = "Text")
    private String metadata;

    @Enumerated(EnumType.STRING)
    private statut decision;

    private LocalDateTime requestTimeStamp;

    private LocalDateTime lastReminderSentAt;

    private int reminderCount;

    @OneToMany(mappedBy = "approvalRequest")
    private List<ApprovalOTP> approvalOTPs;

    @OneToMany(mappedBy = "approvalRequest")
    private List<WebhookNotification> webhookNotifications;

    @Version
    private Integer version;
}
