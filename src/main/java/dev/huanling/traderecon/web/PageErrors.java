package dev.huanling.traderecon.web;

import dev.huanling.traderecon.ingestion.ImportFault;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@ControllerAdvice(
    assignableTypes = {ImportPageController.class, ReconciliationPageController.class})
public class PageErrors {
  @ExceptionHandler(ImportFault.class)
  public String fault(ImportFault error, Model model, HttpServletResponse response) {
    response.setStatus(error.status);
    model.addAttribute("code", error.code);
    model.addAttribute("message", error.getMessage());
    model.addAttribute("errors", error.errors);
    model.addAttribute("trace", UUID.randomUUID());
    model.addAttribute(
        "help",
        error.status == 409
            ? "Return to the latest case or run and reload it. Read the new evidence before deciding; do not blindly repeat a stale decision."
            : error.status == 404
                ? "The requested item was not found. Return to the list and choose an existing item."
                : "Check the form fields and try again. No successful decision is implied by this error.");
    return "problem";
  }

  @ExceptionHandler({
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  public String invalid(Exception e, Model model, HttpServletResponse response) {
    return fault(
        ImportFault.of(400, "INVALID_REQUEST", 0, "request", "Missing or invalid form field"),
        model,
        response);
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public String large(Exception e, Model model, HttpServletResponse response) {
    return fault(
        ImportFault.of(413, "FILE_TOO_LARGE", 0, "file", "Maximum CSV size is 10 MiB"),
        model,
        response);
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public String unavailable(Exception e, Model model, HttpServletResponse response) {
    String view =
        fault(
            ImportFault.of(
                503,
                "DATABASE_UNAVAILABLE",
                0,
                "database",
                "The database is unavailable or an operation timed out"),
            model,
            response);
    model.addAttribute(
        "help",
        "Wait for database recovery. For a run request, keep its original request key; do not create repeated new requests.");
    return view;
  }
}
