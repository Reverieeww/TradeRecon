package dev.huanling.traderecon.web;

import dev.huanling.traderecon.ingestion.ImportFault;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
  public static void write(HttpServletResponse res, int status, String code) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter()
        .write(
            "{\"code\":\""
                + code
                + "\",\"message\":\""
                + code
                + "\",\"fieldErrors\":[],\"traceId\":\""
                + UUID.randomUUID()
                + "\"}");
  }

  @ExceptionHandler(ImportFault.class)
  ResponseEntity<?> fault(ImportFault e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", e.code);
    body.put("message", e.getMessage());
    body.put("fieldErrors", e.errors);
    body.put("traceId", UUID.randomUUID().toString());
    body.put("batchId", e.batchId);
    return ResponseEntity.status(e.status).body(body);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> large() {
    return fault(ImportFault.of(413, "FILE_TOO_LARGE", 0, "file", "Maximum 10 MiB"));
  }

  @ExceptionHandler({
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return fault(
        ImportFault.of(
            400, "INVALID_REQUEST", 0, "request", "Missing or invalid request parameter"));
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<?> unavailable(Exception e) {
    return fault(
        ImportFault.of(
            503,
            "DATABASE_UNAVAILABLE",
            0,
            "database",
            "Database operation unavailable or timed out; retry with the same idempotency key"));
  }
}
