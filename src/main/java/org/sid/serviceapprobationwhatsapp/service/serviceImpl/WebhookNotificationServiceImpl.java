package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.sid.serviceapprobationwhatsapp.entities.ApprovalRequest;
import org.sid.serviceapprobationwhatsapp.entities.WebhookNotification;
import org.sid.serviceapprobationwhatsapp.enums.statut;
import org.sid.serviceapprobationwhatsapp.repositories.WebhookNotificationRepository;
import org.sid.serviceapprobationwhatsapp.service.ApiKeyGenerator;
import org.sid.serviceapprobationwhatsapp.service.WebhookNotificationService;
import org.sid.serviceapprobationwhatsapp.web.WhatsAppWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Date;

@Service
public class WebhookNotificationServiceImpl implements WebhookNotificationService {

    private final ApiKeyGenerator apiKeyGenerator;
    private final RestTemplate restTemplate;
    private final WebhookNotificationRepository webhookNotificationRepository;

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookHandler.class);

    public WebhookNotificationServiceImpl(WebhookNotificationRepository webhookNotificationRepository,
                                          RestTemplate restTemplate, ApiKeyGenerator apiKeyGenerator) {
        this.webhookNotificationRepository = webhookNotificationRepository;
        this.restTemplate = restTemplate;
        this.apiKeyGenerator = apiKeyGenerator;
    }

    // Method to create a new HttpHeaders object to send the notification webhook to the callbackURL using the api key as token
    private org.springframework.http.@NotNull HttpHeaders createHeaders() {
        // Create a new HttpHeaders object
        org.springframework.http.HttpHeaders headers = new HttpHeaders();

        // Set the Content-Type header to application/json
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Use the same API Key that is used by the exterior system to identify themselves when making requests to our API
        // This API Key is used to authenticate the webhook notification and ensure it is sent to the correct system
        String apiKey = apiKeyGenerator.generateApiKey();

        // Add the API Key to the Authorization header
        headers.add("Authorization", "Bearer " + apiKey);

        // Return the HttpHeaders object with API Key authentication
        return headers;
    }

    @Override
    public ResponseEntity<String> sendWebhookNotification(String callbackURL, ApprovalRequest approvalRequest) {
        try {
            // Creation de object WebhookNotification
            WebhookNotification webhookNotification = WebhookNotification.builder()
                    .approvalRequest(approvalRequest)
                    .build();

            // Creation de object JSONObject pour envoyer la notification webhook au callbackURL
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("approvalRequestId", approvalRequest.getId());
            jsonObject.put("status", approvalRequest.getDecision());

            if (!approvalRequest.getDecision().equals(statut.Approuver)) {
                jsonObject.put("commentaire", approvalRequest.getCommentaire());
            }

            webhookNotification.setCreatedAt(new Date());
            jsonObject.put("createdAt", webhookNotification.getCreatedAt());

            webhookNotificationRepository.save(webhookNotification);

            // Envoi de la notification webhook
            HttpEntity<String> request = new HttpEntity<>(jsonObject.toString(), createHeaders());
            return restTemplate.postForEntity(approvalRequest.getCallbackUrl(), request, String.class);

        } catch (Exception e) {
            // Log the error and return an appropriate response
            log.error("Erreur lors de l'envoi de la notification", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erreur lors de l'envoi de la notification");
        }
    }
}
