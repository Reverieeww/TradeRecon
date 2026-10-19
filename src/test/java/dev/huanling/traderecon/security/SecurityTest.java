package dev.huanling.traderecon.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.huanling.traderecon.web.HomeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = HomeController.class, properties = "app.demo-password=Test-only-password-123")
@Import(SecurityConfig.class)
class SecurityTest {
  @Autowired MockMvc mvc;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  dev.huanling.traderecon.persistence.InvestigationQueries investigationQueries;

  @Test
  void loginPageIsAvailableWithoutRedirectLoop() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"username\"")));
  }

  @Test
  void wrongPasswordFails() throws Exception {
    mvc.perform(formLogin().user("viewer").password("wrong"))
        .andExpect(unauthenticated())
        .andExpect(redirectedUrl("/login?error"));
  }

  @Test
  void correctPasswordAuthenticates() throws Exception {
    mvc.perform(formLogin().user("viewer").password("Test-only-password-123"))
        .andExpect(authenticated().withUsername("viewer"));
  }

  @Test
  void anonymousHomeRequiresLogin() throws Exception {
    mvc.perform(get("/")).andExpect(status().is3xxRedirection());
  }

  @Test
  void missingCsrfRejectsPost() throws Exception {
    mvc.perform(post("/logout")).andExpect(status().isForbidden());
  }
}
