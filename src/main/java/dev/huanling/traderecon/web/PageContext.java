package dev.huanling.traderecon.web;

import dev.huanling.traderecon.persistence.InvestigationQueries;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice(
    assignableTypes = {
      HomeController.class,
      ImportPageController.class,
      ReconciliationPageController.class
    })
public class PageContext {
  private final InvestigationQueries queries;

  public PageContext(InvestigationQueries queries) {
    this.queries = queries;
  }

  @ModelAttribute
  public void common(Model model, Authentication auth) {
    model.addAttribute("datasets", queries.datasets());
    model.addAttribute(
        "canWrite",
        auth != null
            && auth.getAuthorities().stream()
                .anyMatch(
                    a ->
                        a.getAuthority().equals("ROLE_ANALYST")
                            || a.getAuthority().equals("ROLE_SUPERVISOR")));
    model.addAttribute(
        "isSupervisor",
        auth != null
            && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPERVISOR")));
    model.addAttribute("username", auth == null ? "" : auth.getName());
  }
}
