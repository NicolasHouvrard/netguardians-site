package com.logicielpourtous.site.features.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailTemplateRepository extends JpaRepository<MailTemplate, Long> {
    Optional<MailTemplate> findByType(MailTemplateType type);
    List<MailTemplate> findAllByOrderByTypeAsc();
}
