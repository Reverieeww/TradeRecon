package dev.huanling.traderecon.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class AiContractTest {
  final ObjectMapper json = new ObjectMapper();

  JsonNode input() throws Exception {
    return json.readTree(
        "{\"facts\":[{\"id\":\"F1\",\"field\":\"reason\",\"value\":\"PRICE_MISMATCH\"}],\"runbooks\":[{\"id\":\"RB-FIELDS\",\"instruction\":\"Check source records\"}],\"insufficientEvidence\":false}");
  }

  String output() throws Exception {
    return new MockInvestigationProvider(json).analyse(input(), Duration.ofSeconds(1)).raw();
  }

  @Test
  void mockContractAndValidReferences() throws Exception {
    var out = new AiOutputValidator().validate(output(), input());
    assertFalse(out.path("abstain").asBoolean());
    assertTrue(out.path("summary").asText().startsWith("MOCK"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "extra",
        "badRef",
        "badBook",
        "missing",
        "wrongType",
        "trailing",
        "duplicate",
        "tooLong"
      })
  void rejectsMalformedContracts(String variant) throws Exception {
    var n = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(output());
    switch (variant) {
      case "extra" -> n.put("close_case", true);
      case "badRef" -> n.putArray("fact_refs").add("INVENTED");
      case "badBook" ->
          ((com.fasterxml.jackson.databind.node.ObjectNode) n.path("next_checks").get(0))
              .put("runbook_id", "http://evil");
      case "missing" -> n.remove("hypotheses");
      case "wrongType" -> n.put("abstain", "false");
      case "tooLong" -> n.put("summary", "x".repeat(1201));
      default -> {}
    }
    String raw = n.toString() + (variant.equals("trailing") ? "{}" : "");
    if (variant.equals("duplicate"))
      raw = raw.replace("\"summary\":", "\"summary\":\"duplicate\",\"summary\":");
    final String value = raw;
    assertThrows(
        IllegalArgumentException.class, () -> new AiOutputValidator().validate(value, input()));
  }

  @Test
  void insufficientEvidenceRequiresAbstention() throws Exception {
    var n = (com.fasterxml.jackson.databind.node.ObjectNode) input();
    n.put("insufficientEvidence", true);
    assertThrows(
        IllegalArgumentException.class, () -> new AiOutputValidator().validate(output(), n));
    assertTrue(
        new AiOutputValidator()
            .validate(
                new MockInvestigationProvider(json).analyse(n, Duration.ofSeconds(1)).raw(), n)
            .path("abstain")
            .asBoolean());
  }

  @Test
  void explicitApprovalKeyModelAndBudgetAreRequired() {
    var env = new MockEnvironment();
    var s = new AiSettings(env);
    assertEquals("AI_OFF", s.unavailable());
    env.setProperty("app.ai-mode", "LIVE");
    assertEquals("MISSING_API_KEY", s.unavailable());
    env.setProperty("app.ai.key", "not-a-real-key");
    assertEquals("MISSING_MODEL", s.unavailable());
    env.setProperty("app.ai.model", "test-model");
    assertEquals("LIVE_NOT_APPROVED", s.unavailable());
    env.setProperty("app.ai.live-approved", "true");
    assertEquals("BUDGET_NOT_CONFIGURED", s.unavailable());
    env.setProperty("app.ai.budget-usd", "1");
    env.setProperty("app.ai.input-usd-per-million", "1");
    env.setProperty("app.ai.output-usd-per-million", "2");
    env.setProperty("app.ai.max-requests", "1");
    assertNull(s.unavailable());
    assertTrue(s.reservation(1000).signum() > 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 503})
  void retriesTransientStatusOnlyOnce(int status) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        x -> {
          int count = calls.incrementAndGet();
          try {
            String request = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(request.contains("\"tools\""));
            assertTrue(request.contains("\"json_schema\""));
            String body =
                count == 1
                    ? "{}"
                    : json.writeValueAsString(
                        Map.of(
                            "model",
                            "test-snapshot",
                            "choices",
                            List.of(
                                Map.of(
                                    "finish_reason",
                                    "stop",
                                    "message",
                                    Map.of("content", output()))),
                            "usage",
                            Map.of("prompt_tokens", 10, "completion_tokens", 20)));
            x.getResponseHeaders().add("x-request-id", "req-local");
            x.sendResponseHeaders(
                count == 1 ? status : 200, body.getBytes(StandardCharsets.UTF_8).length);
            x.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            x.close();
          }
        });
    server.start();
    try {
      var p =
          new LiveInvestigationProvider(
              json,
              "fake-test-key",
              "test",
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
      var reply = p.analyse(input(), Duration.ofSeconds(3));
      assertEquals(2, reply.attempts());
      assertEquals(2, calls.get());
      assertEquals("test-snapshot", reply.model());
      assertEquals("req-local", reply.requestId());
      new AiOutputValidator().validate(reply.raw(), input());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void deadlineIncludesResponseBodyAndNoInfiniteRetry() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        x -> {
          try {
            Thread.sleep(250);
            x.sendResponseHeaders(429, 0);
          } catch (Exception ignored) {
          } finally {
            x.close();
          }
        });
    server.start();
    try {
      var p =
          new LiveInvestigationProvider(
              json,
              "fake",
              "test",
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
      long start = System.nanoTime();
      assertThrows(Exception.class, () -> p.analyse(input(), Duration.ofMillis(50)));
      assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refusesMissingCredentialsBeforeNetwork() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            new LiveInvestigationProvider(json, "", "test")
                .analyse(input(), Duration.ofSeconds(20)));
  }
}
