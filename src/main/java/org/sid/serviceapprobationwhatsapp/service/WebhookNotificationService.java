package org.sid.serviceapprobationwhatsapp.service;

import org.sid.serviceapprobationwhatsapp.entities.ApprovalRequest;
import org.springframework.http.ResponseEntity;

public interface WebhookNotificationService {

    ResponseEntity<String> sendWebhookNotification(String callbackURL,
                                                   ApprovalRequest approvalRequest
                                );
}
