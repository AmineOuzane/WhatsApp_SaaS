package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sid.serviceapprobationwhatsapp.entities.ApprovalRequest;
import org.sid.serviceapprobationwhatsapp.entities.OtpResendMapping;
import org.sid.serviceapprobationwhatsapp.service.MessageIdMappingService;
import org.sid.serviceapprobationwhatsapp.service.OtpResendMappingService;
import org.sid.serviceapprobationwhatsapp.service.PayloadCreatorService;
import org.sid.serviceapprobationwhatsapp.service.OtpMessage;
import org.sid.serviceapprobationwhatsapp.web.WhatsAppWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Service
public class OtpMessageImpl implements OtpMessage {

    //  WhatsApp API URL and Token Credentials
    @Value("${whatsapp.api.url}")
    private String whatsappApiUrl;

    @Value("${whatsapp.api.token}")
    private String whatsappApiToken;

    private final RestTemplate restTemplate;

    private final OtpResendMappingService otpResendMappingService;
    private final MessageIdMappingService messageIdMappingService;
    private final PayloadCreatorService payloadCreatorService;

    public OtpMessageImpl(PayloadCreatorService payloadCreatorService, RestTemplate restTemplate, MessageIdMappingService messageIdMappingService, OtpResendMappingService otpResendMappingService) {
        this.payloadCreatorService = payloadCreatorService;
        this.restTemplate = restTemplate;
        this.messageIdMappingService = messageIdMappingService;
        this.otpResendMappingService = otpResendMappingService;
    }

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookHandler.class);


    // Method to create a new HttpHeaders object with the WhatsApp API token to send template messages
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", "Bearer " + whatsappApiToken);
        return headers;
    }

    // Method to inform the user that the OTP has been sent
    @Override
    public ResponseEntity<String> sendOtpMessage(String recipientNumber) {

        System.out.println("Retrieved recipient phone number info: " + recipientNumber);

        // Use PayloadCreatorService
        JSONObject requestBody = payloadCreatorService.createBaseRequestBody(recipientNumber);
        JSONObject template = payloadCreatorService.createTemplateObject("envoieotp");

        JSONArray components = new JSONArray();

        // Title Component
        JSONObject titleComponent = new JSONObject();
        titleComponent.put("type", "header");
        components.put(titleComponent);

        // Body Component
        JSONObject bodyComponent = new JSONObject();
        bodyComponent.put("type", "body");
        components.put(bodyComponent);

        template.put("components", components);
        requestBody.put("template", template);

        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), createHeaders());
        return restTemplate.postForEntity(whatsappApiUrl, request, String.class);

    }

    // Method to resend the OTP message via a button click
    @Override
    public ResponseEntity<String> resendOtpMessage(String recipientNumber, Optional<OtpResendMapping> mapping, ApprovalRequest approvalRequest) {

        // Extract the approvalId from the ApprovalRequest entity
        String approvalId = approvalRequest.getId();

        // Use PayloadCreatorService
        JSONObject requestBody = payloadCreatorService.createBaseRequestBody(recipientNumber);
        JSONObject template = payloadCreatorService.createTemplateObject("resendit");

        JSONArray components = new JSONArray();

        // Title Component
        JSONObject titleComponent = new JSONObject(); // Create component objects
        titleComponent.put("type", "header");
        components.put(titleComponent);

        // Body Component
        JSONObject bodyComponent = new JSONObject(); // Create component objects
        bodyComponent.put("type", "body");
        components.put(bodyComponent);

        // Retrieves the existing OTP resend mapping for the given phone number
        // to associate a new OTP with an existing approval request and phone number
        OtpResendMapping resendMapping = otpResendMappingService.createResendMapping(approvalId, recipientNumber);
        otpResendMappingService.getResendMapping(resendMapping.getMappingId());
        logger.info("Resend Mapping ID: " + resendMapping.getMappingId());

        // "Resend OTP" button component
        JSONObject resendButtonComponent = new JSONObject();
        resendButtonComponent.put("type", "button");
        resendButtonComponent.put("sub_type", "quick_reply");
        resendButtonComponent.put("index", "0");

        JSONArray resendParameters = new JSONArray();
        JSONObject resendPayload = new JSONObject();
        resendPayload.put("type", "payload");
        resendPayload.put("payload", "RESEND_" + resendMapping.getMappingId());  // ✅ Ensure payload is correctly set
        resendParameters.put(resendPayload);
        resendButtonComponent.put("parameters", resendParameters);

        components.put(resendButtonComponent);
        template.put("components", components);
        requestBody.put("template", template);

        // Send the request
        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), createHeaders());
        ResponseEntity<String> response = restTemplate.postForEntity(whatsappApiUrl, request, String.class);

        // Mapping the message ID to the approval ID to link the resend button to the approval that needs an OTP
        String messageId = extractContextIdFromResponse(response.getBody());
        System.out.println("Extracted Message ID: " + messageId);
        if (messageId != null) {
            System.out.println("Storing mapping: Message ID = " + messageId + ", Approval ID = " + approvalId);
            messageIdMappingService.storeMapping(messageId, approvalId);
            System.out.println("Map size after storing: " + messageIdMappingService.getMapSize());
            System.out.println("Approval " + approvalId + " is pending !");
        }
        return response;
    }

    // Method to send a "Try Again" message to the user if attempt is invalid
    @Override
    public ResponseEntity<String> sendTryAgain(String recipientNumber) {
        System.out.println("Retrieved recipient phone number info: " + recipientNumber);

        // Use PayloadCreatorService
        JSONObject requestBody = payloadCreatorService.createBaseRequestBody(recipientNumber);
        JSONObject template = payloadCreatorService.createTemplateObject("retry");

        JSONArray components = new JSONArray();

        // Title Component
        JSONObject titleComponent = new JSONObject();
        titleComponent.put("type", "header");
        components.put(titleComponent);

        // Body Component
        JSONObject bodyComponent = new JSONObject();
        bodyComponent.put("type", "body");
        components.put(bodyComponent);

        template.put("components", components);
        requestBody.put("template", template);

        HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), createHeaders());
        return restTemplate.postForEntity(whatsappApiUrl, request, String.class);
    }


    @Override
    public String extractContextIdFromResponse(String jsonResponse) {
        System.out.println("Received JSON response: " + jsonResponse);
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            if (rootNode.has("messages") && rootNode.get("messages").isArray() && !rootNode.get("messages").isEmpty()) {
                JsonNode messagesNode = rootNode.get("messages").get(0);
                System.out.println("Messages node found: " + messagesNode.toString());

                // Extract the message ID
                String messageId = messagesNode.path("id").asText();
                if (!messageId.isEmpty()) {
                    System.out.println("Extracted message ID: " + messageId);
                    return messageId;
                } else {
                    System.out.println("Message ID is empty in the JSON response.");
                    return null;
                }
            } else {
                System.out.println("No messages found in the JSON response or messages array is empty.");
                return null;
            }
        } catch (JsonProcessingException e) {
            System.out.println("Error parsing JSON response: " + e.getMessage());
            return null;
        }
    }
}


