package com.claimpilot.staff.adapter;

import com.claimpilot.shared.security.Role;
import com.claimpilot.shared.security.RoleLookup;
import com.claimpilot.staff.application.StaffRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
class JdbcStaffRepository implements StaffRepository, RoleLookup {

    private final JdbcClient db;

    JdbcStaffRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Optional<Role> staffRole(String subject) {
        return find(subject);
    }

    @Override
    public Optional<Role> find(String subject) {
        return db.sql("select role from staff where subject = ?").param(subject)
                .query((rs, i) -> Role.valueOf(rs.getString(1))).optional();
    }

    @Override
    public void upsert(String subject, Role role) {
        db.sql("insert into staff (subject, role) values (?, ?) on conflict (subject) do update set role = excluded.role")
                .params(subject, role.name()).update();
    }

    @Override
    public boolean insertIfAbsent(String subject, Role role) {
        return db.sql("insert into staff (subject, role) values (?, ?) on conflict do nothing")
                .params(subject, role.name()).update() == 1;
    }

    @Override
    public boolean remove(String subject) {
        return db.sql("delete from staff where subject = ?").param(subject).update() == 1;
    }

    @Override
    public Map<String, Role> all() {
        Map<String, Role> out = new LinkedHashMap<>();
        db.sql("select subject, role from staff order by subject")
                .query(rs -> {
                    out.put(rs.getString(1), Role.valueOf(rs.getString(2)));
                });
        return out;
    }
}
