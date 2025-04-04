package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import org.sid.serviceapprobationwhatsapp.entities.OtpResendMapping;
import org.sid.serviceapprobationwhatsapp.repositories.OtpResendMappingRepository;
import org.sid.serviceapprobationwhatsapp.service.OtpResendMappingService;
import org.sid.serviceapprobationwhatsapp.web.WhatsAppWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class OtpResendMappingImpl implements OtpResendMappingService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookHandler.class);

    private final OtpResendMappingRepository otpResendMappingRepository;

    public OtpResendMappingImpl(OtpResendMappingRepository otpResendMappingRepository) {
        this.otpResendMappingRepository = otpResendMappingRepository;
    }

    // Method to associate the new OTP with the existing approval request, update the OTP for an existing approval request
    @Override
    public OtpResendMapping createResendMapping(String approvalId, String phoneNumber) {
        String mappingId = UUID.randomUUID().toString();
        OtpResendMapping mapping = OtpResendMapping.builder()
                .mappingId(mappingId)
                .approvalId(approvalId)
                .recipientNumber(phoneNumber)
                .expiration(LocalDateTime.now().plusMinutes(5)) // Expires in 5 minutes
                .build();
        logger.info("Created new mapping with ID: {}", mapping.getMappingId());
        return otpResendMappingRepository.save(mapping);
    }

    // Method to find the mapping between message and approval by the mapping ID
    @Override
    public Optional<OtpResendMapping> getResendMapping(String mappingId) {
        Optional<OtpResendMapping> optionalMapping = otpResendMappingRepository.findByMappingId(mappingId);

        if (optionalMapping.isPresent()) {
            OtpResendMapping mapping = optionalMapping.get();
            // Check for expiration
            if (LocalDateTime.now().isAfter(mapping.getExpiration())) {
                otpResendMappingRepository.delete(mapping); // Clean up expired mapping
                return Optional.empty(); // Return empty to indicate it's expired
            }
            return optionalMapping; // Valid and within expiration
        }
        return Optional.empty(); // Not found
    }

    @Override
    public void deleteResendMapping(String mappingId) {
        otpResendMappingRepository.deleteById(mappingId);
    }
}
