package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.ProcessingControl;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingControlRepository extends JpaRepository<ProcessingControl, Long> {
}
