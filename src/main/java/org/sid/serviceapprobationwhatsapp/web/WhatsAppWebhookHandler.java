package org.sid.serviceapprobationwhatsapp.web;


import jakarta.persistence.EntityNotFoundException;
import org.sid.serviceapprobationwhatsapp.entities.ApprovalOTP;
import org.sid.serviceapprobationwhatsapp.entities.ApprovalRequest;
import org.sid.serviceapprobationwhatsapp.entities.OtpResendMapping;
import org.sid.serviceapprobationwhatsapp.enums.otpStatut;
import org.sid.serviceapprobationwhatsapp.enums.statut;
import org.sid.serviceapprobationwhatsapp.repositories.ApprovalOtpRepository;
import org.sid.serviceapprobationwhatsapp.repositories.ApprovalRequestRepository;
import org.sid.serviceapprobationwhatsapp.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@RestController
public class WhatsAppWebhookHandler {

    private final WebhookNotificationService webhookNotificationService;
    private final WhatsAppService whatsAppService;
    private final ApprovalOtpRepository approvalOtpRepository;
    private final OtpResendMappingService otpResendMappingService;
    private final OtpVerification otpVerification;
    private final ApprovalService approvalService;
    private final MessageIdMappingService messageIdMappingService;

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookHandler.class);
    private final ApprovalRequestRepository approvalRequestRepository;

    private final ReentrantLock lock = new ReentrantLock();
    Map<String, String> userStates = new HashMap<>();

    public WhatsAppWebhookHandler(  ApprovalService approvalService,
                                    MessageIdMappingService messageIdMappingService,
                                    OtpVerification otpVerification,
                                    OtpResendMappingService otpResendMappingService,
                                    ApprovalOtpRepository approvalOtpRepository,
                                    ApprovalRequestRepository approvalRequestRepository,
                                    WhatsAppService whatsAppService,
                                    WebhookNotificationService webhookNotificationService) {

        this.approvalService = approvalService;
        this.messageIdMappingService = messageIdMappingService;
        this.otpVerification = otpVerification;
        this.otpResendMappingService = otpResendMappingService;
        this.approvalOtpRepository = approvalOtpRepository;
        this.approvalRequestRepository = approvalRequestRepository;
        this.whatsAppService = whatsAppService;
        this.webhookNotificationService = webhookNotificationService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(@RequestBody Map<String, Object> payload) {
        logger.info("Webhook received!");
        logger.debug("Full payload: {}", payload);

        // Use lock to ensure to prevent repetitive requests from ngrok
        // The lock prevents repetitive requests from occurring because it ensures that only one request is being processed at a time
        lock.lock();

        try {
            // Traitement et extraction des données du payload
            Object entryObj = payload.get("entry");
            if (entryObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> entries = (List<Map<String, Object>>) entryObj;

                for (Map<String, Object> entry : entries) {
                    Object changesObj = entry.get("changes");

                    if (changesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> changes = (List<Map<String, Object>>) changesObj;

                        for (Map<String, Object> change : changes) {
                            Object valueObj = change.get("value");

                            if (valueObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> value = (Map<String, Object>) valueObj;
                                Object messagesObj = value.get("messages");

                                if (messagesObj instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> messages = (List<Map<String, Object>>) messagesObj;

                                    for (Map<String, Object> message : messages) {

                                        // Extract the sender's phone number directly from the "from" field
                                        String phoneNumber = (String) message.get("from");

                                        // message type if button or text
                                        String messageType = (String) message.get("type");
                                        logger.debug("Message type: {}", messageType);

                                        // Handle Button Messages
                                        if ("button".equals(messageType)) {
                                            logger.info("Processing button message");

                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> button = (Map<String, Object>) message.get("button");
                                            if (button == null) {
                                                logger.warn("Button object is null");

                                            } else {
                                                // extract button payload and text
                                                String buttonPayload = (String) button.get("payload");
                                                String buttonText = (String) button.get("text");
                                                logger.info("Button clicked: {}, Payload: {}", buttonText, buttonPayload); // Logging user action

                                                // Extracting the context field that contain the id of the message that he is responding to
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> context = (Map<String, Object>) message.get("context");
                                                String originalMessageId = context != null ? (String) context.get("id") : null;
                                                logger.debug("Original Message ID: {}", originalMessageId);

                                                // Mapping the context messageId to approvalId => lier la decision a la demande
                                                String approvalId = messageIdMappingService.getApprovalId(originalMessageId);
                                                logger.debug("Approval ID retrieved: {}", approvalId);
                                                messageIdMappingService.logAllMappings();

                                                logger.debug("********* Button Clicked **********");

                                                if (approvalId == null) {
                                                    logger.warn("No request found for original message ID: {}", originalMessageId);

                                                } else {
                                                    Optional<ApprovalRequest> ar = approvalRequestRepository.findById(approvalId);

                                                    logger.info("Processing button click for phone number: {}", phoneNumber);
                                                    logger.info("Approval ID: {}", approvalId);

                                                    // Process approval button click
                                                    if (buttonPayload.startsWith("APPROVE_")) {
                                                        approvalService.updateStatus(approvalId, statut.Approuver);
                                                        logger.info("La Demande {} a été approuvée !", approvalId);
                                                        // Envoyer la notification webhook au system exterior
                                                        webhookNotificationService.sendWebhookNotification(ar.orElseThrow(() -> new EntityNotFoundException("ApprovalRequest not found after approval click")).getCallbackUrl(), ar.orElseThrow());

                                                        // Process reject button click
                                                    } else if (buttonPayload.startsWith("REJECT_")) {
                                                        approvalService.updateStatus(approvalId, statut.Rejeter);
                                                        logger.info("La Demande {} a été rejetée !", approvalId);
                                                        // Envoyer un template message WhatsApp pour demander un commentaire pour une tel demande
                                                        whatsAppService.sendCommentaire(approvalId, phoneNumber);
                                                        userStates.put(phoneNumber, "awaiting_rejection_comment");
                                                        logger.info("User state set {} for phone number: {}", userStates, phoneNumber);
                                                        // Notification webhook pour le system exterior apres la saisie du commentaire

                                                        // Process pending button click
                                                    } else if (buttonPayload.startsWith("ATTENTE_")) {
                                                        approvalService.updateStatus(approvalId, statut.En_Attente);
                                                        logger.info("La Demande {} a été mise en attente !", approvalId);
                                                        // Envoyer un template message WhatsApp pour demander un commentaire pour une tel demande
                                                        whatsAppService.sendCommentaire(approvalId, phoneNumber);
                                                        userStates.put(phoneNumber, "awaiting_attente_comment");
                                                        logger.info("User state set {} for phone number: {}", userStates, phoneNumber);
                                                        // Notification webhook pour le system exterior apres la saisie du commentaire

                                                        // Process resend button click
                                                    } else if (buttonPayload.startsWith("RESEND_")) {
                                                        // Creation de object OtpResendMapping "new otp" pour l'approval id et le phone number
                                                        // Retrieves the existing OTP resend mapping for the given phone number
                                                        // to associate a new OTP with an existing approval request and phone number
                                                        Optional<OtpResendMapping> resendMapping = Optional.of(otpResendMappingService.createResendMapping(approvalId, phoneNumber));
                                                        otpResendMappingService.getResendMapping(phoneNumber);
                                                        resendMapping.ifPresent(otpResendMapping -> logger.info("Created Resend Mapping: {}", otpResendMapping.getMappingId()));

                                                        try {
                                                            // Fetch the expired OTP
                                                            Optional<ApprovalOTP> optionalApprovalOTP = approvalOtpRepository.findByApprovalRequestId(approvalId);

                                                            if (optionalApprovalOTP.isPresent()) {
                                                                ApprovalOTP approvalOTP = optionalApprovalOTP.get();
                                                                ApprovalRequest approvalRequest = approvalOTP.getApprovalRequest();
                                                                // Set status of OTP to Expired
                                                                approvalOTP.setStatus(otpStatut.EXPIRED);
                                                                approvalOtpRepository.save(approvalOTP);
                                                                logger.info("OTP successfully set to EXPIRED with phone number: {}", phoneNumber);
                                                                // Send the new OTP to the user
                                                                approvalService.sendOtpAndCreateApprovalOTP(approvalRequest, phoneNumber);
                                                                logger.info("Successfully resent OTP to: {}", phoneNumber);

                                                            } else {
                                                                logger.warn("ApprovalOTP not found for approvalId: {}", approvalId);
                                                                return ResponseEntity.badRequest().body(Map.of("error", "Approval OTP not found"));
                                                            }
                                                        } catch (Exception e) {
                                                            logger.error("Failed to resend OTP: {}", e.getMessage(), e);
                                                            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to resend OTP"));
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        // Handle text type messages
                                        else
                                        if ("text".equals(messageType)) {
                                            logger.info("Processing text message");

                                            Object textObj = message.get("text");
                                            if (!(textObj instanceof Map)) {
                                                return ResponseEntity.badRequest().body(Map.of("error", "Invalid text message format"));
                                            }
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> text = (Map<String, Object>) textObj;
                                            String messageBody = (String) text.get("body");
                                            // User input validation
                                            if (messageBody == null || messageBody.trim().isEmpty()) {
                                                return ResponseEntity.badRequest().body(Map.of("error", "Input is empty"));
                                            }
                                            messageBody = messageBody.trim();
                                            logger.debug("Received input: {}", messageBody);

                                            // Extraction du champ "context" (peut être null)
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> context = (Map<String, Object>) message.get("context");

                                            // Récupération et normalisation du numéro de téléphone depuis phoneNumbers
                                            if (!phoneNumber.startsWith("+")) {
                                                phoneNumber = "+" + phoneNumber;
                                            }
                                            // Si phoneNumbers ne fournit pas de numéro, essayer de récupérer depuis message.get("from")
                                            if (phoneNumber.trim().isEmpty() && message.get("from") != null) {
                                                phoneNumber = (String) message.get("from");
                                                if (!phoneNumber.startsWith("+")) {
                                                    phoneNumber = "+" + phoneNumber;
                                                }
                                            }
                                            if (phoneNumber.trim().isEmpty()) {
                                                logger.warn("Unable to retrieve sender phone number");
                                                return ResponseEntity.badRequest().body(Map.of("error", "Unable to identify sender"));
                                            }
                                            logger.info("Sender phone number: {}", phoneNumber);

                                            // Détermination si le message doit être traité comme une response (context) a un message "donc commentaire"
                                            String approvalId = null;
                                            if (context != null) {

                                                String originalMessageId = (String) context.get("id");
                                                logger.debug("Response message detected. Original Message ID: {}", originalMessageId);

                                                // Récupérer l'approvalId à partir du messageIdMappingService
                                                approvalId = messageIdMappingService.getApprovalId(originalMessageId);
                                                logger.debug("Approval ID retrieved from context: {}", approvalId);

                                                // Verify if an approval demand is associated to the message context
                                                if (approvalId == null) {
                                                    logger.warn("No approval request mapping found for original message id: {}", originalMessageId);
                                                    return ResponseEntity.badRequest().body(Map.of("error", "Approval request mapping not found"));
                                                }
                                            }

                                            // Vérifier l'état utilisateur pour voir si un commentaire était attendu
                                            String userState = userStates.get(phoneNumber.replaceFirst("\\+", ""));
                                            boolean isAwaitingComment = ("awaiting_rejection_comment".equals(userState) || "awaiting_attente_comment".equals(userState));

                                            // Si on a un context et que l'état utilisateur indique un commentaire, traiter le commentaire
                                            if (context != null || isAwaitingComment) {
                                                try {
                                                    Optional<ApprovalRequest> optionalApprovalRequest;
                                                    if (approvalId != null) {
                                                        // Recherche par approver ET approvalId (cas du context)
                                                        optionalApprovalRequest = approvalRequestRepository.findByApproverAndId(approvalId, phoneNumber);
                                                        logger.info("Approval request found for approvalId: {} and phone number: {}", approvalId, phoneNumber);
                                                    } else {
                                                        // Recherche par approver seulement
                                                        optionalApprovalRequest = approvalRequestRepository.findByApprover(phoneNumber);
                                                        logger.info("Approval request found for phone number only and no approvalId: {}", phoneNumber);
                                                    }

                                                    // Si demande est trouvee, enregistrer le commentaire et envoyer la notification webhook
                                                    if (optionalApprovalRequest.isPresent()) {
                                                        ApprovalRequest approvalRequest = optionalApprovalRequest.get();
                                                        logger.info("Approval request found for phone number and ready for comment: {}", phoneNumber);
                                                        // Enregistrement du commentaire et la demande
                                                        approvalRequest.setCommentaire(messageBody);
                                                        approvalRequestRepository.save(approvalRequest);
                                                        logger.info("Approval request comment saved: {}", approvalRequest.getCommentaire());
                                                        // Envoi de la notification webhook
                                                        webhookNotificationService.sendWebhookNotification(approvalRequest.getCallbackUrl(), approvalRequest);
                                                        logger.info("Webhook notification sent for approvalId: {}", approvalRequest.getId());

                                                        // Nettoyage de l'état utilisateur
                                                        userStates.remove(phoneNumber.replaceFirst("\\+", ""));
                                                        logger.info("Removed state for user: {}", phoneNumber);
                                                        return ResponseEntity.ok(Map.of("message", "Comment saved"));
                                                    } else {
                                                        logger.warn("No approval request found for phone number: {}", phoneNumber);
                                                        return ResponseEntity.badRequest().body(Map.of("error", "Approval request not found"));
                                                    }
                                                } catch (Exception e) {
                                                    logger.error("Error saving comment for phone number {}: {}", phoneNumber, e.getMessage(), e);
                                                    return ResponseEntity.internalServerError().body(Map.of("error", "Failed to save comment"));
                                                }
                                            }

                                            // Aucun commentaire attendu : traiter le message comme un OTP directemment
                                            return otpVerification.processOtpVerification(phoneNumber, messageBody);
                                        }
                                    }
                                }
                            }
                            return ResponseEntity.ok(Collections.singletonMap("message", "Processed"));
                        }
                        return ResponseEntity.badRequest().body(Map.of("error", "Invalid payload format: Missing changes"));
                    }
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid payload format: Missing changes"));
                }
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid payload format: Missing entry"));
            }
        } finally {
            lock.unlock();
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request received"));
    }
}

