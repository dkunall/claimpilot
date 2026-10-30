package com.claimpilot.staff.application;

import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class StaffService {

    private final StaffRepository staff;

    public StaffService(StaffRepository staff) {
        this.staff = staff;
    }

    public void assign(Caller caller, String subject, Role role) {
        caller.require(Role.MANAGER);
        if (role == Role.CUSTOMER) {
            throw ApiException.invalid("customers are everyone without a staff role; remove the role instead");
        }
        if (subject == null || subject.isBlank() || subject.length() > 200) {
            throw ApiException.invalid("subject must be 1-200 characters");
        }
        staff.upsert(subject, role);
    }

    public void remove(Caller caller, String subject) {
        caller.require(Role.MANAGER);
        if (subject.equals(caller.subject())) {
            throw ApiException.conflict("self-demotion", "a manager cannot remove their own role");
        }
        if (!staff.remove(subject)) {
            throw ApiException.notFound("staff member");
        }
    }

    public Map<String, Role> list(Caller caller) {
        caller.require(Role.MANAGER);
        return staff.all();
    }
}
