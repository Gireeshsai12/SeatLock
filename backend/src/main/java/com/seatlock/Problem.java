package com.seatlock;

import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  public Problem(int s, String c) {
    super(c.replace('_', ' ').toLowerCase());
    status = s;
    code = c;
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> known(Problem p) {
    return ResponseEntity.status(p.status).body(Map.of("code", p.code, "message", p.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_REQUEST", "message", "Check the supplied fields."));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(DataAccessException e) {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "code",
                "RETRY_TRANSACTION",
                "message",
                "Transaction unavailable; retry with the same checkout key."));
  }
}
