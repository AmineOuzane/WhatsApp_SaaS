package org.sid.serviceapprobationwhatsapp.repositories;

import org.sid.serviceapprobationwhatsapp.entities.WebhookNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookNotificationRepository extends JpaRepository<WebhookNotification, Long> {

}
