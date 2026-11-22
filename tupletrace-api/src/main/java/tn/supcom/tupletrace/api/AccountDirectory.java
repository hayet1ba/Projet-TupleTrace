package tn.supcom.tupletrace.api;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

@ApplicationScoped
public class AccountDirectory {
    record Membership(UUID user, UUID organization, String organizationName) {
        String principal() {
            return "account:" + user + ":" + organization;
        }
    }

    @Resource(lookup = "java:/jdbc/TupleTraceDS")
    private DataSource database;

    Membership active(UUID user) throws SQLException {
        try (var con = database.getConnection(); var query = con.prepareStatement("""
                SELECT o.id,o.name FROM tt.app_user u JOIN tt.organization o ON o.id=u.organization_id
                WHERE u.id=? AND u.status='ACTIVE' AND o.status='ACTIVE'
                """)) {
            query.setObject(1, user);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Membership(user, rows.getObject(1, UUID.class), rows.getString(2)) : null;
            }
        }
    }
}
