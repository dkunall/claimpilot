package com.claimpilot.staff.adapter;

import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import com.claimpilot.staff.application.StaffService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class StaffController {

    private final StaffService staff;

    StaffController(StaffService staff) {
        this.staff = staff;
    }

    record Assignment(@NotNull Role role) {
    }

    @GetMapping("/v1/me")
    Map<String, String> me(Caller caller) {
        return Map.of("subject", caller.subject(), "role", caller.role().name());
    }

    @GetMapping("/v1/staff")
    Map<String, Role> list(Caller caller) {
        return staff.list(caller);
    }

    @PutMapping("/v1/staff/{subject}")
    ResponseEntity<Void> assign(Caller caller, @PathVariable String subject,
                                @RequestBody @jakarta.validation.Valid Assignment body) {
        staff.assign(caller, subject, body.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v1/staff/{subject}")
    ResponseEntity<Void> remove(Caller caller, @PathVariable String subject) {
        staff.remove(caller, subject);
        return ResponseEntity.noContent().build();
    }
}
