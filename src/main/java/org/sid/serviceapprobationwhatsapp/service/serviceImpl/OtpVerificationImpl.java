package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import org.sid.serviceapprobationwhatsapp.entities.ApprovalOTP;
import org.sid.serviceapprobationwhatsapp.entities.ApprovalRequest;
import org.sid.serviceapprobationwhatsapp.entities.OtpResendMapping;
import org.sid.serviceapprobationwhatsapp.enums.otpStatut;
import org.sid.serviceapprobationwhatsapp.repositories.ApprovalOtpRepository;
import org.sid.serviceapprobationwhatsapp.service.*;
import org.sid.serviceapprobationwhatsapp.web.WhatsAppWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class OtpVerificationImpl implements OtpVerification {

    private final SessionService sessionService;
    private final WhatsAppService whatsAppService;
    private final TwilioService twilioService;
    private final ApprovalOtpRepository approvalOtpRepository;
    private final OtpMessage otpMessage;
    private final OtpResendMappingService otpResendMappingService;

    public OtpVerificationImpl(OtpMessage otpMessage, ApprovalOtpRepository approvalOtpRepository, TwilioService twilioService, WhatsAppService whatsAppService, OtpResendMappingService otpResendMappingService, SessionService sessionService) {
        this.otpMessage = otpMessage;
        this.approvalOtpRepository = approvalOtpRepository;
        this.twilioService = twilioService;
        this.whatsAppService = whatsAppService;
        this.otpResendMappingService = otpResendMappingService;
        this.sessionService = sessionService;
    }

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookHandler.class);

    // Method to process the OTP verification logic in our system
    @Override
    public ResponseEntity<?> processOtpVerification(String phoneNumber, String messageBody) {

        // Check if the manager already has a valid session
        String existingSession = sessionService.getPhoneNumberForSession(phoneNumber);
        if (existingSession != null) {
            logger.info("Manager {} already has a valid session. Skipping OTP verification.", phoneNumber);

            // Directly allow the access to the approval request
            return ResponseEntity.ok(Map.of("message", "Already verified. You can proceed with approvals."));
        }

        // Check for a pending OTP attempt
            Optional<ApprovalOTP> optionalOtpAttempt = approvalOtpRepository
                .findTopByRecipientNumberAndStatusOrderByCreatedAtDesc(phoneNumber, otpStatut.PENDING);

        // not processing empty otp
        if (optionalOtpAttempt.isEmpty()) {
            logger.warn("No pending OTP attempt found for phone: {}", phoneNumber);
            return ResponseEntity.badRequest().body(Map.of("error", "OTP introuvable"));
        }

        ApprovalOTP otpAttempt = optionalOtpAttempt.get();

        // Idempotency check: if the OTP attempt status is not PENDING, then it has already been processed.
        if (otpAttempt.getStatus() != otpStatut.PENDING) {
            logger.info("OTP attempt {} already processed with status {}", otpAttempt.getApprovalRequest().getId(), otpAttempt.getStatus());
            return ResponseEntity.ok(Map.of("message", "OTP already processed"));
        }

        // Check if an approval request is associated with the OTP attempt
        ApprovalRequest approvalRequest = otpAttempt.getApprovalRequest();
        if (approvalRequest == null) {
            logger.warn("Approval Request is missing for OTP attempt for phone: {}", phoneNumber);
            return ResponseEntity.badRequest().body(Map.of("error", "Approval Request is missing for OTP"));
        }

        String approvalId = approvalRequest.getId();
        logger.info("Processing approval request ID: {}", approvalId);

        // Check if the phone number is a valid approver for this approval request
        if (!approvalRequest.getApprovers().contains(phoneNumber)) {
            logger.warn("Invalid phone number {} for approval request {}", phoneNumber, approvalId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid phone number for this approval request."));
        }

        // Check expiration BEFORE verifying and set status to EXPIRED if expired
        if (LocalDateTime.now().isAfter(otpAttempt.getExpiration())) {
            otpAttempt.setStatus(otpStatut.EXPIRED);
            approvalOtpRepository.save(otpAttempt);

            // Retrieves the existing OTP resend mapping for the given phone number
            // to associate a new OTP with an existing approval request and phone number
            Optional<OtpResendMapping> resendMapping = otpResendMappingService.getResendMapping(phoneNumber);
            otpMessage.resendOtpMessage(phoneNumber,resendMapping, approvalRequest);

            logger.info("OTP expired for approval ID {}", approvalRequest.getId());

            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "OTP has expired"));
        }

        // Check OTP validation via Twilio Verify API and update the status
        boolean isValid = twilioService.checkVerificationCode(phoneNumber, messageBody, otpAttempt.getVerificationSid());

        if (isValid) {
            // Create a new session for the user after successful OTP verification and set status to APPROVED
            sessionService.createSession(phoneNumber);
            otpAttempt.setStatus(otpStatut.APPROVED);
            approvalOtpRepository.save(otpAttempt);
            logger.info("OTP verified successfully for approval ID {}", approvalId);
            // Send the approval request to the approver after validating the OTP
            whatsAppService.sendMessageWithInteractiveButtons(approvalRequest);

            return ResponseEntity.ok(Map.of("message", "The Manager has now verified, I'm gonna call the system back to process the approval. "));

        }
        // If the OTP is invalid
        else {
            // Increment the invalid attempts and check if the maximum attempts have been exceeded
            otpAttempt.setInvalidattempts(otpAttempt.getInvalidattempts() + 1);
            approvalOtpRepository.save(otpAttempt);

            logger.warn("Invalid OTP attempt {} for approval ID {}", otpAttempt.getInvalidattempts(), approvalId);

            // Maximum attempts exceeded, set status to DENIED and send a message to resend OTP
            if (otpAttempt.getInvalidattempts() >= 3 ) {
                otpAttempt.setStatus(otpStatut.DENIED);
                approvalOtpRepository.save(otpAttempt);
                logger.error("Exceeded maximum OTP attempts for approval ID {}", approvalId);

                // Retrieves the existing OTP resend mapping for the given phone number
                // to associate a new OTP with an existing approval request and phone number
                Optional<OtpResendMapping> resendMapping = otpResendMappingService.getResendMapping(phoneNumber);
                otpMessage.resendOtpMessage(phoneNumber,resendMapping, approvalRequest);

                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You have exceeded the maximum OTP attempts"));
            }
            approvalOtpRepository.save(otpAttempt);

            // Send a message to try again if the OTP is invalid before exceeding the maximum attempts
            otpMessage.sendTryAgain(phoneNumber);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid OTP. Please try again."));

        }
    }
}
