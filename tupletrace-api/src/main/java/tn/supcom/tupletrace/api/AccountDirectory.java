package tn.supcom.tupletrace.api;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class AccountDirectory {
    record Membership(UUID user, UUID organization, String organizationName, boolean privileged,
                      UUID factor, boolean confirmed, Set<String> groups) {
        String principal() {
            return "account:" + user + ":" + organization;
        }
    }

    @Resource(lookup = "java:/jdbc/TupleTraceDS")
    private DataSource database;

    Membership active(UUID user) throws SQLException {
        try (var con = database.getConnection(); var query = con.prepareStatement("""
                SELECT o.id, o.name, roles.privileged, f.factor_id, f.active, roles.groups
                FROM tt.app_user u JOIN tt.organization o ON o.id=u.organization_id
                LEFT JOIN tt.totp_status f ON f.user_id=u.id
                CROSS JOIN LATERAL (
                    SELECT bool_or(tt.privileged_role(r.role_name)) AS privileged,
                           array_prepend('AUTHENTICATED_ACCOUNT', array_agg(r.role_name)) AS groups
                    FROM tt.role_assignment r WHERE r.user_id=u.id AND r.organization_id=o.id
                ) roles
                WHERE u.id=? AND u.status='ACTIVE' AND o.status='ACTIVE'
                """)) {
            query.setObject(1, user);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Membership(user, rows.getObject(1, UUID.class), rows.getString(2),
                        rows.getBoolean(3), rows.getObject(4, UUID.class), rows.getBoolean(5),
                        Set.of((String[]) rows.getArray(6).getArray())) : null;
            }
        }
    }
}
