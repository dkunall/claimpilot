package com.claimpilot.shared.security;

import java.util.Optional;

/** Port to wherever staff roles live; customers are simply absent. */
public interface RoleLookup {

    Optional<Role> staffRole(String subject);
}
