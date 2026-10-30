package com.claimpilot.staff.adapter;

import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.security.Role;
import com.claimpilot.staff.application.StaffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** The first manager has to come from somewhere; configuration names them, and only if they have no role yet. */
@Component
class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    private final StaffRepository staff;
    private final ClaimPilotProperties props;

    StaffBootstrap(StaffRepository staff, ClaimPilotProperties props) {
        this.staff = staff;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String subject : props.security().bootstrapManagers()) {
            if (!subject.isBlank() && staff.insertIfAbsent(subject.strip(), Role.MANAGER)) {
                log.info("bootstrapped manager {}", subject.strip());
            }
        }
    }
}
