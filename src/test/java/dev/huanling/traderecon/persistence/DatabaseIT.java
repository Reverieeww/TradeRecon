package dev.huanling.traderecon.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class DatabaseIT {
  static PostgreSQLContainer<?> postgres;
  static final String URL;
  static final String USER;
  static final String PASSWORD;

  static {
    String external = System.getenv("TEST_DB_URL");
    if (external == null) {
      postgres =
          new PostgreSQLContainer<>("postgres:17.6")
              .withDatabaseName("traderecon_test")
              .withInitScript("db/test-init.sql");
      postgres.start(); // Missing Docker must fail, never silently skip database verification.
      URL = postgres.getJdbcUrl();
      USER = postgres.getUsername();
      PASSWORD = postgres.getPassword();
    } else {
      if (!external.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/traderecon_test"))
        throw new IllegalArgumentException(
            "Only the dedicated local traderecon_test database is allowed");
      URL = external;
      USER = System.getenv("TEST_DB_USER");
      PASSWORD = System.getenv("TEST_DB_PASSWORD");
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("app.worker.enabled", () -> "false");
    r.add("app.ai.worker-enabled", () -> "false");
    r.add("app.ai-mode", () -> "OFF");
    r.add("spring.datasource.url", () -> URL);
    r.add("spring.datasource.username", () -> "traderecon_app");
    r.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("TEST_APP_PASSWORD", "integration-only-password"));
    r.add("spring.flyway.user", () -> USER);
    r.add("spring.flyway.password", () -> PASSWORD);
    r.add("app.demo-password", () -> "Test-only-password-123");
  }

  @Autowired MockMvc mvc;

  @Test
  void healthReportsRealDatabaseUp() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void migrationCreatesSchemaAndReconnectionKeepsCommittedData() throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(URL, USER, PASSWORD)) {
      try (var q = c.createStatement();
          var rs = q.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
        rs.next();
        assertEquals(6, rs.getInt(1));
      }
      try (var p = c.prepareStatement("INSERT INTO dataset(id,name,kind) VALUES (?,?,'DEMO')")) {
        p.setObject(1, id);
        p.setString(2, "persist-" + id);
        assertEquals(1, p.executeUpdate());
      }
    }
    try (var c = DriverManager.getConnection(URL, USER, PASSWORD);
        var p = c.prepareStatement("SELECT kind FROM dataset WHERE id=?")) {
      p.setObject(1, id);
      try (var rs = p.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("DEMO", rs.getString(1));
      }
    }
  }

  @Test
  void databaseRejectsDuplicateAndInvalidKind() throws Exception {
    try (var c = DriverManager.getConnection(URL, USER, PASSWORD)) {
      var id = UUID.randomUUID();
      try (var p = c.prepareStatement("INSERT INTO dataset(id,name,kind) VALUES (?,?,'DEMO')")) {
        p.setObject(1, id);
        p.setString(2, "unique-" + id);
        p.executeUpdate();
        assertEquals("23505", assertThrows(SQLException.class, p::executeUpdate).getSQLState());
      }
      try (var p = c.prepareStatement("INSERT INTO dataset(id,name,kind) VALUES (?,?,'INVALID')")) {
        p.setObject(1, UUID.randomUUID());
        p.setString(2, "invalid");
        assertEquals("23514", assertThrows(SQLException.class, p::executeUpdate).getSQLState());
      }
    }
  }

  @Test
  void runtimeCannotDeleteEventsOrModifyAudit() throws Exception {
    try (var c =
            DriverManager.getConnection(
                URL,
                "traderecon_app",
                System.getenv().getOrDefault("TEST_APP_PASSWORD", "integration-only-password"));
        var s = c.createStatement()) {
      assertEquals(
          "42501",
          assertThrows(SQLException.class, () -> s.executeUpdate("DELETE FROM trade_event"))
              .getSQLState());
      assertEquals(
          "42501",
          assertThrows(
                  SQLException.class,
                  () -> s.executeUpdate("UPDATE audit_event SET reason='changed'"))
              .getSQLState());
    }
  }
}
