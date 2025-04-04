package org.sid.serviceapprobationwhatsapp.service.serviceImpl;

import org.json.JSONObject;
import org.sid.serviceapprobationwhatsapp.service.PayloadCreatorService;
import org.springframework.stereotype.Service;

@Service

// Service to create common payload objects for WhatsApp Template API
public class PayloadCreatorServiceImpl implements PayloadCreatorService {

    // Method to create the base request body that contain the phone number and messaging product
    @Override
    public JSONObject createBaseRequestBody(String recipientNumber) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("messaging_product", "whatsapp");
        requestBody.put("to", recipientNumber);
        requestBody.put("type", "template");
        return requestBody;
    }

    // Method to create the template object with the template name and language code
    @Override
    public JSONObject createTemplateObject(String templateName) {
        JSONObject template = new JSONObject();
        template.put("name", templateName);
        template.put("language", new JSONObject().put("code", "en"));
        return template;
    }

    // Method to create the text parameter for the template message
    @Override
    public JSONObject createTextParameter(String text) {
        return new JSONObject().put("type", "text").put("text", text);
    }

    // Method to create the button parameter for the template message
    @Override
    public JSONObject createButton(String type, String id, String title) {
        JSONObject button = new JSONObject();
        button.put("type", type);

        JSONObject buttonDetails = new JSONObject();

        if ("button".equals(type)) {
            buttonDetails.put("title", title);
            buttonDetails.put("payload", id); // payload for regular buttons
        }
        else if ("button_reply".equals(type)) {
            buttonDetails.put("id", id);
            buttonDetails.put("title", title); // id and title for reply buttons
        } else {
            throw new IllegalArgumentException("Unsupported button type: " + type);
        }

        button.put(type, buttonDetails); // Corrected: Use the type as the key
        return button;
    }
}
