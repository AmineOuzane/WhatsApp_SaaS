package org.sid.serviceapprobationwhatsapp.service;

public interface SessionService {

    String createSession(String phoneNumber);
    String getPhoneNumberForSession(String phoneNumber);
    void invalidateSession(String phoneNumber);
    String generateSessionId();
}
