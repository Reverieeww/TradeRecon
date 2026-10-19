package dev.huanling.traderecon.ingestion;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.persistence.ImportRepository;
import dev.huanling.traderecon.persistence.ImportRepository.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ImportService {
  public record Outcome(Batch batch, boolean repeatedFile) {}

  private final ImportRepository repo;
  private final CsvParser parser;
  private final Clock clock;
  private final TransactionTemplate tx;

  public ImportService(
      ImportRepository repo, CsvParser parser, Clock clock, PlatformTransactionManager manager) {
    this.repo = repo;
    this.parser = parser;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Outcome ingest(UUID dataset, Source source, LocalDate date, boolean complete, byte[] raw) {
    return ingest(dataset, source, date, complete, raw, true);
  }

  /** batched=false is a controlled benchmark baseline; it is not exposed over HTTP. */
  public Outcome ingest(
      UUID dataset, Source source, LocalDate date, boolean complete, byte[] raw, boolean batched) {
    Objects.requireNonNull(dataset);
    Objects.requireNonNull(source);
    Objects.requireNonNull(date);
    if (!repo.datasetExists(dataset))
      throw ImportFault.of(404, "NOT_FOUND", 0, "dataset", "Dataset not found");
    if (raw.length > CsvParser.MAX_BYTES)
      throw ImportFault.of(
          413, "FILE_TOO_LARGE", 0, "file", "Maximum 10 MiB; oversized bytes are not retained");
    String sha = EventFingerprint.sha(raw);
    CsvParser.Parsed parsed = null;
    try {
      parsed = parser.parse(raw, dataset, source, date);
      if (!parsed.errors().isEmpty())
        throw new ImportFault(422, "VALIDATION_ERROR", parsed.errors());
      var valid = parsed;
      // The database lock serialises a source's batches. Constraints remain the final defence.
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          return tx.execute(s -> accept(dataset, source, date, complete, raw, sha, valid, batched));
        } catch (DuplicateKeyException race) {
          if (attempt == 2) throw race;
        }
      }
      throw new IllegalStateException("Unreachable");
    } catch (ImportFault fault) {
      if (fault.code.equals("BATCH_METADATA_CONFLICT")) throw fault;
      var rows = parsed == null ? List.<CsvParser.Row>of() : parsed.rows();
      Batch b =
          tx.execute(
              s -> {
                repo.lock(dataset, source);
                var old = repo.byHash(dataset, source, sha);
                if (old.isPresent()) {
                  checkMetadata(old.get(), date, complete);
                  return old.get();
                }
                UUID id = UUID.randomUUID();
                repo.insertBatch(
                    id,
                    dataset,
                    source,
                    date,
                    complete,
                    sha,
                    raw,
                    "REJECTED",
                    null,
                    Map.of(
                        "total",
                        rows.size(),
                        "inserted",
                        0,
                        "duplicates",
                        0,
                        "rejected",
                        rows.size()),
                    Map.of(
                        "code",
                        fault.code,
                        "httpStatus",
                        fault.status,
                        "fieldErrors",
                        fault.errors));
                repo.insertRows(
                    id, rows.stream().map(r -> new RowOutcome(r, null, "REJECTED")).toList(), true);
                return repo.get(id);
              });
      fault.batchId = b.id();
      throw fault;
    }
  }

  private void checkMetadata(Batch b, LocalDate date, boolean complete) {
    if (!b.businessDate().equals(date) || b.feedComplete() != complete)
      throw ImportFault.of(
          409,
          "BATCH_METADATA_CONFLICT",
          0,
          "batch",
          "Identical file hash was already submitted with different date/feed_complete");
  }

  private Outcome accept(
      UUID dataset,
      Source source,
      LocalDate date,
      boolean complete,
      byte[] raw,
      String sha,
      CsvParser.Parsed parsed,
      boolean batched) {
    repo.lock(dataset, source);
    var existing = repo.byHash(dataset, source, sha);
    if (existing.isPresent()) {
      var b = existing.get();
      checkMetadata(b, date, complete);
      if (b.status().equals("REJECTED")) {
        var f =
            ImportFault.of(
                b.error().path("httpStatus").asInt(422),
                b.error().path("code").asText(),
                0,
                "file",
                "Previously rejected; see batch details");
        f.batchId = b.id();
        throw f;
      }
      return new Outcome(b, true);
    }
    Set<String> accounts = repo.accounts(), instruments = repo.instruments();
    List<ImportFault.FieldError> errors = new ArrayList<>();
    for (var row : parsed.rows()) {
      if (!accounts.contains(row.event().accountId()))
        errors.add(new ImportFault.FieldError(row.number(), "account_id", "Unknown account"));
      if (!instruments.contains(row.event().instrumentId()))
        errors.add(new ImportFault.FieldError(row.number(), "instrument_id", "Unknown instrument"));
    }
    if (!errors.isEmpty()) throw new ImportFault(422, "VALIDATION_ERROR", errors);
    var executions =
        parsed.rows().stream().map(r -> r.event().key().executionId()).distinct().toList();
    var eventIds = parsed.rows().stream().map(r -> r.event().eventId()).distinct().toList();
    var identities = new HashMap<>(repo.identities(dataset, source, eventIds));
    Map<String, Identity> addedIds = new LinkedHashMap<>();
    Map<String, Stored> versions = new HashMap<>();
    Map<String, LocalDate> dates = new HashMap<>();
    for (var s : repo.events(dataset, source, executions)) {
      versions.put(s.event().key().executionId() + ":" + s.event().revision(), s);
      dates.put(s.event().key().executionId(), s.event().key().tradeDate());
    }
    Instant now = clock.instant();
    List<Stored> additions = new ArrayList<>();
    List<RowOutcome> outcomes = new ArrayList<>();
    for (var row : parsed.rows()) {
      var e = row.event();
      String execution = e.key().executionId(), hash = EventFingerprint.payload(e);
      var knownDate = dates.putIfAbsent(execution, date);
      if (knownDate != null && !knownDate.equals(date))
        throw conflict(row, "trade_date", "A correction cannot change business date");
      var identity = identities.get(e.eventId());
      if (identity != null) {
        if (!identity.hash().equals(hash))
          throw conflict(row, "event_id", "Same message ID has different payload");
        outcomes.add(new RowOutcome(row, identity.eventRef(), "DUPLICATE"));
        continue;
      }
      String key = execution + ":" + e.revision();
      var same = versions.get(key);
      UUID ref;
      String outcome;
      if (same != null) {
        if (!EventFingerprint.sameRevision(same.event(), e))
          throw conflict(row, "revision", "Same execution revision has different business fields");
        ref = same.id();
        outcome = "DUPLICATE";
      } else {
        ref = UUID.randomUUID();
        outcome = "NEW";
        var stored = new Stored(ref, e, now);
        versions.put(key, stored);
        additions.add(stored);
      }
      var added = new Identity(ref, hash);
      identities.put(e.eventId(), added);
      addedIds.put(e.eventId(), added);
      outcomes.add(new RowOutcome(row, ref, outcome));
    }
    UUID id = UUID.randomUUID();
    repo.insertBatch(
        id,
        dataset,
        source,
        date,
        complete,
        sha,
        raw,
        "ACCEPTED",
        now,
        Map.of(
            "total",
            outcomes.size(),
            "inserted",
            additions.size(),
            "duplicates",
            outcomes.size() - additions.size(),
            "rejected",
            0),
        null);
    repo.insertEvents(additions, id, batched);
    repo.insertIdentities(dataset, source, addedIds, batched);
    repo.insertRows(id, outcomes, batched);
    return new Outcome(repo.get(id), false);
  }

  private ImportFault conflict(CsvParser.Row r, String field, String message) {
    return ImportFault.of(409, "IDENTITY_CONFLICT", r.number(), field, message);
  }
}
