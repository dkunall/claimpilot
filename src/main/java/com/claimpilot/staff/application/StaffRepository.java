package com.claimpilot.staff.application;

import com.claimpilot.shared.security.Role;

import java.util.Map;
import java.util.Optional;

public interface StaffRepository {

    Optional<Role> find(String subject);

    void upsert(String subject, Role role);

    /** Insert only if absent, so a bootstrap never overrides a role a manager has since changed. */
    boolean insertIfAbsent(String subject, Role role);

    boolean remove(String subject);

    Map<String, Role> all();
}
