package dev.huanling.traderecon.security;

import dev.huanling.traderecon.ingestion.ImportFault;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/** Persist the existing environment-configured demo accounts for assignment and audit FKs. */
@Component
public class DemoActors implements ApplicationRunner {
  public record Actor(UUID id, String name, String role) {}

  private final JdbcTemplate jdbc;
  private final UserDetailsService users;

  public DemoActors(JdbcTemplate jdbc, UserDetailsService users) {
    this.jdbc = jdbc;
    this.users = users;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (String name : List.of("viewer", "analyst", "supervisor")) {
      var user = users.loadUserByUsername(name);
      String role = user.getAuthorities().iterator().next().getAuthority().substring(5);
      jdbc.update(
          "INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (?,?,?,?,true) ON CONFLICT(username) DO UPDATE SET password_hash=excluded.password_hash,role=excluded.role,enabled=true",
          UUID.randomUUID(),
          name,
          user.getPassword(),
          role);
    }
  }

  public Actor writer(String name) {
    var rows =
        jdbc.query(
            "SELECT id,username,role FROM app_user WHERE username=? AND enabled",
            (r, n) -> new Actor(r.getObject(1, UUID.class), r.getString(2), r.getString(3)),
            name);
    if (rows.isEmpty() || rows.getFirst().role().equals("VIEWER"))
      throw ImportFault.of(403, "FORBIDDEN", 0, "actor", "Analyst or supervisor required");
    return rows.getFirst();
  }
}
