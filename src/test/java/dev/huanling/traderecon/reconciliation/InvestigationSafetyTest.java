package dev.huanling.traderecon.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.ai.*;
import dev.huanling.traderecon.web.CsvExportController;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvestigationSafetyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"=1+1", "+CMD", "-1+2", "@SUM(A1)", " =1+1", "\t=1+1", "\r=1+1", "\n=1+1"})
  void csvFormulaPrefixesAreNeutralized(String value) {
    assertTrue(CsvExportController.cell(value).startsWith("\"'"));
  }

  @Test
  void csvQuotesAndNewlinesAreEscaped() {
    assertEquals("\"a\"\"b\nc\"", CsvExportController.cell("a\"b\nc"));
  }

  @Test
  void evidenceLimitsAreExplicitNotSilent() {
    var json = new ObjectMapper();
    var tree = json.createObjectNode();
    tree.put("rulesVersion", "recon-v1");
    tree.put("status", "DATA_BLOCKED");
    tree.putArray("reasonCodes").add("INTERNAL_VERSION_GAP");
    var versions = tree.putArray("INTERNAL_versions");
    for (int i = 0; i < 100; i++) versions.add("v" + i + "x".repeat(500));
    var evidence =
        new EvidenceBuilder(json).build(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), tree);
    assertTrue(evidence.contextIncomplete());
    assertTrue(evidence.facts().size() <= 40);
    assertTrue(json.valueToTree(evidence).toString().length() <= 8000);
    assertTrue(evidence.totalFacts() > evidence.facts().size());
    assertTrue(new TemplateInvestigator().investigate(evidence).contextIncomplete());
  }
}
