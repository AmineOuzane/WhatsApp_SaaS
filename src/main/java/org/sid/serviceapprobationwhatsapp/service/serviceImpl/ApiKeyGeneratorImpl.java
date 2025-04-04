package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import org.sid.serviceapprobationwhatsapp.service.ApiKeyGenerator;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyGeneratorImpl implements ApiKeyGenerator {

    @Override
    public String generateApiKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
