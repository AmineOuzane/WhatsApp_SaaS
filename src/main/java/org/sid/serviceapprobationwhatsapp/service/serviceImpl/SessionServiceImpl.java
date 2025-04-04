package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import org.sid.serviceapprobationwhatsapp.entities.UserSession;
import org.sid.serviceapprobationwhatsapp.enums.sessionStatut;
import org.sid.serviceapprobationwhatsapp.repositories.UserSessionRepository;
import org.sid.serviceapprobationwhatsapp.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionServiceImpl.class);
    private final UserSessionRepository userSessionRepository;


    @Value("${session.expiration.hours}")
    private int sessionExpirationHours;

    public SessionServiceImpl(UserSessionRepository userSessionRepository) {
        this.userSessionRepository = userSessionRepository;
    }

    // Create Session
    @Override
    public String createSession(String phoneNumber) {

        String sessionId = generateSessionId();

        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime expiresAt = createdAt.plusHours(sessionExpirationHours);

        UserSession userSession = UserSession.builder()
                .sessionId(sessionId)
                .phoneNumber(phoneNumber)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .status(sessionStatut.EnCours)
                .build();

        userSessionRepository.save(userSession);
        logger.info("Created session {} for phone number {}", sessionId, phoneNumber);
        return sessionId;
    }

    // Retrieve Session by Phone Number
    @Override
    public String getPhoneNumberForSession(String phoneNumber) {
        try {
            // Retrieve the UserSession based on phoneNumber
            Optional<UserSession> session = userSessionRepository.findByPhoneNumber(phoneNumber);

            if (session.isPresent()) {
                UserSession userSession = session.get();

                // Check if the session has expired
                boolean isExpired = userSession.getExpiresAt().isBefore(LocalDateTime.now());
                if (!isExpired) {
                    // Session found and not expired
                    logger.info("Retrieved valid session for phone number {}", phoneNumber);
                    return userSession.getSessionId();
                } else {
                    logger.warn("Session expired for phone number {}", phoneNumber);
                    userSession.setStatus(sessionStatut.Expire);
                    return null;
                }
            } else {
                logger.warn("No session found for phone number {}", phoneNumber);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error retrieving session for phone number {}: {}", phoneNumber, e.getMessage());
            return null;
        }
    }


    // Invalidate Session
    @Override
    public void invalidateSession(String phoneNumber) {
        Optional<UserSession> session = userSessionRepository.findByPhoneNumber(phoneNumber);
        session.ifPresent(s -> {
            userSessionRepository.deleteByPhoneNumber(phoneNumber);
            logger.info("Invalidated session for phone number {}", phoneNumber);
        });
    }

    // Generate a new session ID
    @Override
    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }
}
