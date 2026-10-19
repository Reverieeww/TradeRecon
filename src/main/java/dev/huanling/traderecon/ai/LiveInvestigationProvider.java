package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** One fixed HTTPS provider. No redirects, browsing, tools or caller-supplied endpoint. */
public class LiveInvestigationProvider implements InvestigationProvider {
  private final ObjectMapper json;
  private final String key, model, prompt;
  private final URI endpoint;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public LiveInvestigationProvider(ObjectMapper j, String key, String model) {
    this(j, key, model, URI.create("https://api.openai.com/v1/chat/completions"));
  }

  // Package-private loopback constructor is for local HTTP contract tests, not application config.
  LiveInvestigationProvider(ObjectMapper j, String key, String model, URI endpoint) {
    json = j;
    this.key = key;
    this.model = model;
    this.endpoint = endpoint;
    if (!endpoint.toString().equals("https://api.openai.com/v1/chat/completions")
        && !(endpoint.getScheme().equals("http") && endpoint.getHost().equals("127.0.0.1")))
      throw new IllegalArgumentException("Invalid endpoint");
    try (var in = getClass().getResourceAsStream("/ai/prompt-v1.txt")) {
      prompt = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Prompt missing");
    }
  }

  public JsonNode request(JsonNode evidence) {
    return json.valueToTree(
        Map.of(
            "model",
            model,
            "store",
            false,
            "max_completion_tokens",
            2048,
            "messages",
            List.of(
                Map.of("role", "system", "content", prompt),
                Map.of("role", "user", "content", evidence.toString())),
            "response_format",
            Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of("name", "investigation", "strict", true, "schema", schema()))));
  }

  public static Map<String, Object> schema() {
    var str = Map.<String, Object>of("type", "string");
    var refs = array(str);
    return obj(
        Map.of(
            "summary",
            str,
            "fact_refs",
            refs,
            "hypotheses",
            array(obj(Map.of("statement", str, "supporting_fact_ids", refs, "uncertainty", str))),
            "next_checks",
            array(obj(Map.of("instruction", str, "fact_refs", refs, "runbook_id", str))),
            "missing_evidence",
            refs,
            "abstain",
            Map.of("type", "boolean")));
  }

  private static Map<String, Object> array(Map<String, Object> items) {
    return Map.of("type", "array", "items", items);
  }

  private static Map<String, Object> obj(Map<String, Object> props) {
    return Map.of(
        "type",
        "object",
        "properties",
        props,
        "required",
        new ArrayList<>(props.keySet()),
        "additionalProperties",
        false);
  }

  public Reply analyse(JsonNode input, Duration timeout) throws Exception {
    if (key.isBlank() || model.isBlank()) throw new IllegalStateException("MISSING_CONFIGURATION");
    long deadline = System.nanoTime() + timeout.toNanos();
    for (int attempt = 1; attempt <= 2; attempt++) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new TimeoutException("AI_TIMEOUT");
      var req =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofNanos(remaining))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(request(input).toString()))
              .build();
      var future = client.sendAsync(req, info -> new LimitedBody());
      HttpResponse<byte[]> response;
      try {
        response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (Exception e) {
        future.cancel(true);
        throw e;
      }
      int status = response.statusCode();
      if ((status == 429 || status >= 500 && status <= 599) && attempt == 1) {
        if (deadline - System.nanoTime() < TimeUnit.MILLISECONDS.toNanos(300))
          throw new TimeoutException("AI_TIMEOUT");
        Thread.sleep(250);
        continue;
      }
      if (status != 200) throw new IllegalStateException("PROVIDER_HTTP_" + status);
      JsonNode n = json.readTree(response.body());
      var choice = n.path("choices").path(0);
      if (!choice.path("finish_reason").asText().equals("stop")
          || !choice.path("message").path("refusal").isMissingNode()
              && !choice.path("message").path("refusal").isNull())
        throw new IllegalArgumentException("PROVIDER_REFUSAL_OR_INCOMPLETE");
      String content = choice.path("message").path("content").asText();
      return new Reply(
          content,
          response.headers().firstValue("x-request-id").orElse(n.path("id").asText()),
          n.path("model").asText(model),
          n.path("usage"),
          attempt);
    }
    throw new IllegalStateException("PROVIDER_FAILED");
  }

  /** Bound the entire envelope before buffering; cancellation also releases the connection. */
  private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int bytes;

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      delegate.onSubscribe(s);
    }

    public void onNext(List<ByteBuffer> chunks) {
      for (var c : chunks) bytes += c.remaining();
      if (bytes > 65536) {
        subscription.cancel();
        delegate.onError(new IllegalArgumentException("PROVIDER_BODY_TOO_LARGE"));
      } else delegate.onNext(chunks);
    }

    public void onError(Throwable t) {
      delegate.onError(t);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }
}
