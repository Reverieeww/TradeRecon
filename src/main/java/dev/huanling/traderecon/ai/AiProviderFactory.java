package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class AiProviderFactory {
  private final AiSettings settings;
  private final ObjectMapper json;

  public AiProviderFactory(AiSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  public InvestigationProvider create() {
    return settings.mode().equals("MOCK")
        ? new MockInvestigationProvider(json)
        : new LiveInvestigationProvider(json, settings.key(), settings.model());
  }
}
