package org.sid.serviceapprobationwhatsapp.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.sid.serviceapprobationwhatsapp.dto.ApprovalRequestDTO;
import org.sid.serviceapprobationwhatsapp.entities.ApprovalRequest;
import org.sid.serviceapprobationwhatsapp.enums.statut;
import org.sid.serviceapprobationwhatsapp.repositories.ApprovalRequestRepository;
import org.sid.serviceapprobationwhatsapp.service.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.context.request.RequestContextHolder;

@Slf4j
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final WhatsAppService whatsAppService;
    private final SessionService sessionService;
    private final ApprovalService approvalService;
    private final OtpMessage otpMessage;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ObjectMapper objectMapper;

    public ApprovalController(ApprovalRequestRepository approvalRequestRepository,
                              ObjectMapper objectMapper,
                              OtpMessage otpMessage,
                              ApprovalService approvalService,
                              SessionService sessionService,
                              WhatsAppService whatsAppService ) {

        this.approvalRequestRepository = approvalRequestRepository;
        this.objectMapper = objectMapper;
        this.otpMessage = otpMessage;
        this.approvalService = approvalService;
        this.sessionService = sessionService;
        this.whatsAppService = whatsAppService;
    }

    // Principal Endpoint that receive the Request from the external system
    @Async
    @PostMapping("/register")
    public CompletableFuture<ResponseEntity<?>> registerApprovalRequest(@Valid @RequestBody ApprovalRequestDTO approvalRequestDTO,
                                                                        @RequestHeader("Authorization") String authorizationHeader) {

        log.info("Received request to register a new approval: {}", approvalRequestDTO);

        // Extract the token from the authorization header
        String token = authorizationHeader.replace("Bearer ", "");
        // Store the token in the request context
        RequestContextHolder.getRequestAttributes().setAttribute("token", token, 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Serialization des données et des métadonnées en JSON
                String dataJson = objectMapper.writeValueAsString(approvalRequestDTO.getApprovalData());
                String metadataJson = objectMapper.writeValueAsString(approvalRequestDTO.getMetadata());

                // Instanciation de object ApprovalRequest
                ApprovalRequest approvalRequest = ApprovalRequest.builder()
                        .objectType(approvalRequestDTO.getObjectType())
                        .objectId(approvalRequestDTO.getObjectId())
                        .data(dataJson)
                        .origin(approvalRequestDTO.getOrigin())
                        .approvers(approvalRequestDTO.getApprovers())
                        .demandeur(approvalRequestDTO.getDemandeur())
                        .commentaire("")
                        .callbackUrl(approvalRequestDTO.getCallbackUrl())
                        .metadata(metadataJson)
                        .decision(statut.Pending)
                        .requestTimeStamp(LocalDateTime.now())
                        .build();

                ApprovalRequest savedApprovalRequest = approvalRequestRepository.save(approvalRequest);

                // Envoi de l'OTP aux numeros a la liste des approbateurs
                for (String approverPhoneNumber : approvalRequestDTO.getApprovers()) {
                    // Check for an existing valid session
                    String existingSession = sessionService.getPhoneNumberForSession(approverPhoneNumber);
                    if (existingSession != null) {
                        log.info("Manager {} already has a valid session. Skipping OTP sending.", approverPhoneNumber);
                        // Envoi de la demande approbation si le manager a déjà une session valide
                        whatsAppService.sendMessageWithInteractiveButtons(approvalRequest);
                    } else {
                        // If no session exists, send OTP and create an OTP record.
                        otpMessage.sendOtpMessage(approverPhoneNumber);
                        approvalService.sendOtpAndCreateApprovalOTP(savedApprovalRequest, approverPhoneNumber);
                    }
                }

                return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("approvalId", savedApprovalRequest.getId(), "message", "Approval request registered. Verification codes sent."));

            } catch (JsonProcessingException e) {
                log.error("Error serializing data or metadata to JSON", e);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid data format."));
            } catch (OptimisticLockingFailureException e) {
                log.error("Optimistic locking failure", e);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The approval request was modified by another user. Please try again."));
            } catch (Exception e) {
                log.error("An unexpected error occurred", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "An unexpected error occurred."));
            }
        });
    }
}