package dev.huanling.traderecon.persistence;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import dev.huanling.traderecon.ingestion.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ImportRepository {
  public record Stored(UUID id, TradeEvent event, Instant receivedAt) {}

  public record Identity(UUID eventRef, String hash) {}

  public record Batch(
      UUID id,
      UUID datasetId,
      String source,
      LocalDate businessDate,
      boolean feedComplete,
      String status,
      JsonNode counts,
      JsonNode error,
      Instant acceptedAt) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper json;

  public ImportRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.json = json;
  }

  public String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode node(String s) {
    try {
      return s == null ? json.nullNode() : json.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void lock(UUID dataset, Source source) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, dataset + ":" + source);
  }

  public boolean datasetExists(UUID dataset) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM dataset WHERE id=?)", Boolean.class, dataset));
  }

  public Set<String> accounts() {
    return new HashSet<>(jdbc.queryForList("SELECT id FROM account", String.class));
  }

  public Set<String> instruments() {
    return new HashSet<>(jdbc.queryForList("SELECT id FROM instrument", String.class));
  }

  private Batch batch(ResultSet r, int n) throws SQLException {
    var at = r.getTimestamp("accepted_at");
    return new Batch(
        r.getObject("id", UUID.class),
        r.getObject("dataset_id", UUID.class),
        r.getString("source"),
        r.getDate("business_date").toLocalDate(),
        r.getBoolean("feed_complete"),
        r.getString("status"),
        node(r.getString("row_counts")),
        node(r.getString("error_summary")),
        at == null ? null : at.toInstant());
  }

  public Optional<Batch> byHash(UUID dataset, Source source, String sha) {
    return jdbc
        .query(
            "SELECT * FROM import_batch WHERE dataset_id=? AND source=? AND sha256=?",
            this::batch,
            dataset,
            source.name(),
            sha)
        .stream()
        .findFirst();
  }

  public Batch get(UUID id) {
    return jdbc.query("SELECT * FROM import_batch WHERE id=?", this::batch, id).stream()
        .findFirst()
        .orElseThrow(() -> ImportFault.of(404, "NOT_FOUND", 0, "id", "Batch not found"));
  }

  public List<Batch> list(UUID dataset, int page, int size) {
    return jdbc.query(
        "SELECT * FROM import_batch WHERE dataset_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
        this::batch,
        dataset,
        size,
        (long) page * size);
  }

  public List<Map<String, Object>> rows(UUID id, int page, int size) {
    get(id);
    return jdbc.query(
        "SELECT row_no,event_ref,outcome,raw_payload FROM import_row WHERE batch_id=? ORDER BY row_no LIMIT ? OFFSET ?",
        (r, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("row", r.getInt(1));
          m.put("eventRef", r.getObject(2));
          m.put("outcome", r.getString(3));
          m.put("rawPayload", node(r.getString(4)));
          return m;
        },
        id,
        size,
        (long) page * size);
  }

  public Map<String, Identity> identities(UUID dataset, Source source, List<String> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<String, Identity> result = new HashMap<>();
    named.query(
        "SELECT event_id,event_ref,payload_hash FROM event_identity WHERE dataset_id=:d AND source=:s AND event_id IN (:ids)",
        Map.of("d", dataset, "s", source.name(), "ids", ids),
        (org.springframework.jdbc.core.RowCallbackHandler)
            r ->
                result.put(
                    r.getString(1), new Identity(r.getObject(2, UUID.class), r.getString(3))));
    return result;
  }

  public List<Stored> events(UUID dataset, Source source, List<String> executions) {
    if (executions.isEmpty()) return List.of();
    return named.query(
        "SELECT * FROM trade_event WHERE dataset_id=:d AND source=:s AND execution_id IN (:ids) ORDER BY execution_id,revision",
        Map.of("d", dataset, "s", source.name(), "ids", executions),
        (r, n) -> {
          TradeEvent e =
              new TradeEvent(
                  new ExecutionKey(
                      dataset, r.getDate("trade_date").toLocalDate(), r.getString("execution_id")),
                  source,
                  r.getString("event_id"),
                  r.getInt("revision"),
                  EventType.valueOf(r.getString("event_type")),
                  r.getString("account_id"),
                  r.getString("instrument_id"),
                  Side.valueOf(r.getString("side")),
                  new MoneyFields(
                      r.getBigDecimal("quantity"),
                      r.getBigDecimal("price"),
                      Currency.valueOf(r.getString("currency"))),
                  r.getTimestamp("occurred_at").toInstant(),
                  r.getString("note"));
          return new Stored(
              r.getObject("id", UUID.class), e, r.getTimestamp("received_at").toInstant());
        });
  }

  public void insertBatch(
      UUID id,
      UUID dataset,
      Source source,
      LocalDate date,
      boolean complete,
      String hash,
      byte[] raw,
      String status,
      Instant at,
      Object counts,
      Object error) {
    jdbc.update(
        "INSERT INTO import_batch(id,dataset_id,source,business_date,sha256,status,feed_complete,accepted_at,row_counts,error_summary,raw_csv) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
        id,
        dataset,
        source.name(),
        date,
        hash,
        status,
        complete,
        at == null ? null : Timestamp.from(at),
        json(counts),
        error == null ? null : json(error),
        raw);
  }

  private void writes(String sql, List<Object[]> args, boolean batch) {
    if (args.isEmpty()) return;
    if (batch)
      jdbc.batchUpdate(
          sql,
          args,
          500,
          (p, a) -> {
            for (int i = 0; i < a.length; i++) p.setObject(i + 1, a[i]);
          });
    else for (var a : args) jdbc.update(sql, a);
  }

  public void insertEvents(List<Stored> events, UUID batch, boolean batched) {
    List<Object[]> args = new ArrayList<>();
    for (var s : events) {
      var e = s.event();
      args.add(
          new Object[] {
            s.id(),
            e.key().datasetId(),
            e.source().name(),
            e.eventId(),
            e.key().executionId(),
            e.revision(),
            e.eventType().name(),
            e.accountId(),
            e.instrumentId(),
            e.side().name(),
            e.money().currency().name(),
            e.money().quantity(),
            e.money().price(),
            e.key().tradeDate(),
            Timestamp.from(e.occurredAt()),
            e.note(),
            EventFingerprint.payload(e),
            batch,
            Timestamp.from(s.receivedAt())
          });
    }
    writes(
        "INSERT INTO trade_event(id,dataset_id,source,event_id,execution_id,revision,event_type,account_id,instrument_id,side,currency,quantity,price,trade_date,occurred_at,note,payload_hash,first_batch_id,received_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        args,
        batched);
  }

  public void insertIdentities(
      UUID dataset, Source source, Map<String, Identity> ids, boolean batched) {
    List<Object[]> args = new ArrayList<>();
    ids.forEach(
        (k, v) -> args.add(new Object[] {dataset, source.name(), k, v.eventRef(), v.hash()}));
    writes(
        "INSERT INTO event_identity(dataset_id,source,event_id,event_ref,payload_hash) VALUES (?,?,?,?,?)",
        args,
        batched);
  }

  public record RowOutcome(CsvParser.Row row, UUID eventRef, String outcome) {}

  public void insertRows(UUID batch, List<RowOutcome> rows, boolean batched) {
    List<Object[]> args = new ArrayList<>();
    for (var r : rows)
      args.add(
          new Object[] {batch, r.row().number(), r.eventRef(), r.outcome(), json(r.row().raw())});
    writes(
        "INSERT INTO import_row(batch_id,row_no,event_ref,outcome,raw_payload) VALUES (?,?,?,?,?::jsonb)",
        args,
        batched);
  }
}
