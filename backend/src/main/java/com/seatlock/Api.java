package com.seatlock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
class Api {
  final Reservations r;
  final JdbcTemplate db;
  final BCryptPasswordEncoder enc;
  final StringRedisTemplate redis;
  final Lab lab;

  Api(
      Reservations r,
      JdbcTemplate db,
      BCryptPasswordEncoder e,
      StringRedisTemplate redis,
      Lab lab) {
    this.r = r;
    this.db = db;
    enc = e;
    this.redis = redis;
    this.lab = lab;
  }

  UUID user(Authentication a) {
    return UUID.fromString(a.getName());
  }

  record Login(
      @NotBlank @Size(max = 200) String email, @NotBlank @Size(max = 100) String password) {}

  record HoldRequest(
      @NotNull UUID eventId,
      @NotEmpty @Size(max = 6) List<@Pattern(regexp = "[A-Z][1-9][0-9]?") String> seats) {}

  record CheckoutRequest(@NotNull UUID holdId, @NotBlank String key, @NotBlank String mode) {}

  void limit(String subject, int max) {
    try {
      String k = "rate:" + subject + ":" + (Instant.now().getEpochSecond() / 60);
      Long n = redis.opsForValue().increment(k);
      if (n != null && n == 1) redis.expire(k, Duration.ofSeconds(65));
      if (n != null && n > max) throw new Problem(429, "RATE_LIMIT");
    } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
      throw new Problem(503, "RATE_LIMIT_UNAVAILABLE");
    }
  }

  @PostMapping("/auth/login")
  Object login(@Valid @RequestBody Login q, HttpServletRequest req) {
    limit("login:" + req.getRemoteAddr(), 30);
    var users =
        db.queryForList("select * from app_user where email=? and synthetic=false", q.email());
    if (users.isEmpty() || !enc.matches(q.password(), users.getFirst().get("password").toString()))
      throw new Problem(401, "INVALID_CREDENTIALS");
    var u = users.getFirst();
    String token = UUID.randomUUID() + "." + UUID.randomUUID();
    db.update(
        "insert into auth_token values(?,?,clock_timestamp()+interval '8 hours')",
        Security.hash(token),
        u.get("id"));
    return Map.of("token", token, "role", u.get("role"), "email", u.get("email"));
  }

  @PostMapping("/auth/logout")
  void logout(@RequestHeader("Authorization") String auth) {
    db.update("delete from auth_token where token=?", Security.hash(auth.substring(7)));
  }

  @GetMapping("/events")
  Object events() {
    return db.queryForList("select * from event where lab=false order by starts_at,title");
  }

  @GetMapping("/events/{id}")
  Object snapshot(@PathVariable UUID id) {
    return r.snapshot(id);
  }

  @GetMapping("/holds")
  Object holds(Authentication a) {
    return r.active(user(a));
  }

  @PostMapping("/holds")
  Object hold(Authentication a, @Valid @RequestBody HoldRequest q) {
    limit(user(a).toString(), 15);
    try {
      return r.hold(user(a), q.eventId(), q.seats());
    } catch (Problem p) {
      r.metrics.counter("seatlock.conflicts", "code", p.code).increment();
      throw p;
    }
  }

  @DeleteMapping("/holds/{id}")
  void release(Authentication a, @PathVariable UUID id) {
    r.release(user(a), id);
  }

  @PostMapping("/checkout")
  Object checkout(Authentication a, @Valid @RequestBody CheckoutRequest q) {
    limit("checkout:" + user(a), 30);
    return r.checkout(user(a), q.holdId(), q.key(), q.mode());
  }

  @GetMapping("/checkout/{id}")
  Object checkoutStatus(Authentication a, @PathVariable UUID id) {
    return r.one("select * from checkout where id=? and user_id=?", id, user(a));
  }

  @GetMapping("/bookings")
  Object bookings(Authentication a) {
    return r.bookings(user(a));
  }

  record EventRequest(
      @NotBlank @Size(max = 100) String title,
      @NotBlank @Size(max = 500) String description,
      @Min(1) @Max(26) int rows,
      @Min(1) @Max(30) int cols,
      @Min(0) @Max(100000) int price) {}

  @PostMapping("/ops/events")
  Object create(Authentication a, @Valid @RequestBody EventRequest q) {
    return r.tx.execute(
        t -> {
          UUID id = UUID.randomUUID();
          db.update(
              "insert into event(id,title,description,art,starts_at,price,rows,cols)"
                  + " values(?,?,?,'/art/event-0.svg',clock_timestamp()+interval '30 days',?,?,?)",
              id,
              q.title(),
              q.description(),
              q.price(),
              q.rows(),
              q.cols());
          layout(id, q.rows(), q.cols());
          r.audit(user(a), id, "CREATE_EVENT", "SUCCESS");
          return Map.of("id", id);
        });
  }

  void layout(UUID id, int rows, int cols) {
    for (int i = 1; i <= rows; i++)
      for (int j = 1; j <= cols; j++)
        db.update(
            "insert into seat(event_id,label,row_no,col_no) values(?,?,?,?)",
            id,
            "" + (char) (64 + i) + j,
            i,
            j);
  }

  @PutMapping("/ops/events/{id}")
  Object edit(Authentication a, @PathVariable UUID id, @Valid @RequestBody EventRequest q) {
    return r.tx.execute(
        t -> {
          r.lock(user(a), id);
          var ev = r.one("select * from event where id=?", id);
          boolean resize = !ev.get("rows").equals(q.rows()) || !ev.get("cols").equals(q.cols());
          if (resize
              && db.queryForObject("select count(*) from hold where event_id=?", Integer.class, id)
                  > 0) throw new Problem(409, "LAYOUT_HAS_RESERVATIONS");
          if (resize) {
            db.update("delete from seat where event_id=?", id);
            layout(id, q.rows(), q.cols());
          }
          db.update(
              "update event set title=?,description=?,price=?,rows=?,cols=? where id=?",
              q.title(),
              q.description(),
              q.price(),
              q.rows(),
              q.cols(),
              id);
          r.changed(id, user(a), "EDIT_EVENT", "SUCCESS");
          return Map.of("id", id);
        });
  }

  @GetMapping("/ops/reservations")
  Object inspect(@RequestParam UUID event) {
    return Map.of(
        "holds",
        db.queryForList(
            "select id,event_id,expires_at,status from hold where event_id=? order by created_at"
                + " desc limit 100",
            event),
        "bookings",
        db.queryForList(
            "select reference,created_at from booking where event_id=? order by created_at desc"
                + " limit 100",
            event));
  }

  @GetMapping("/logs")
  Object logs(
      @RequestParam(defaultValue = "0") long before,
      @RequestParam(defaultValue = "") String operation,
      @RequestParam(defaultValue = "") String outcome,
      @RequestParam(required = false) UUID event) {
    return db.queryForList(
        "select * from audit where (?=0 or id<?) and (?='' or operation=?) and (?='' or outcome=?)"
            + " and (?::uuid is null or event_id=?::uuid) order by id desc limit 50",
        before,
        before,
        operation,
        operation,
        outcome,
        outcome,
        event,
        event);
  }

  record Run(
      @NotBlank String scenario,
      @Min(2) @Max(300) int attempts,
      @Min(1) @Max(24) int concurrency) {}

  @PostMapping("/lab")
  Object start(Authentication a, @Valid @RequestBody Run q) {
    return lab.start(user(a), q.scenario(), q.attempts(), q.concurrency());
  }

  @GetMapping("/lab")
  Object jobs() {
    return db.queryForList("select * from lab_job order by created_at desc limit 20").stream()
        .map(this::decodeJob)
        .toList();
  }

  @PostMapping("/lab/{id}/cancel")
  void cancel(@PathVariable UUID id) {
    db.update("update lab_job set cancel_requested=true where id=? and status='RUNNING'", id);
  }

  @GetMapping("/lab/{id}/report")
  Object report(@PathVariable UUID id) {
    return decodeJob(r.one("select * from lab_job where id=?", id));
  }

  Map<String, Object> decodeJob(Map<String, Object> row) {
    Object value = row.get("result");
    if (value != null) {
      try {
        row.put("result", lab.json.readValue(value.toString(), Map.class));
      } catch (Exception e) {
        row.put("status", "UNVERIFIED");
        row.put("result", Map.of("reason", "Report could not be decoded"));
      }
    }
    return row;
  }

  final Map<SseEmitter, UUID> streams = new ConcurrentHashMap<>();

  @GetMapping(value = "/events/{id}/stream", produces = "text/event-stream")
  SseEmitter stream(@PathVariable UUID id) {
    r.snapshot(id);
    SseEmitter s = new SseEmitter(600000L);
    streams.put(s, id);
    s.onCompletion(() -> streams.remove(s));
    s.onTimeout(
        () -> {
          streams.remove(s);
          s.complete();
        });
    return s;
  }

  @Scheduled(fixedDelay = 1000)
  void publish() {
    streams.forEach(
        (s, id) -> {
          try {
            var snap = r.snapshot(id);
            s.send(
                SseEmitter.event().name("snapshot").id(snap.get("version").toString()).data(snap));
          } catch (Exception e) {
            streams.remove(s);
            s.complete();
          }
        });
  }

  @Scheduled(fixedDelay = 1000)
  void maintenance() {
    try {
      for (var c :
          db.queryForList(
              "select id from checkout where status='PENDING' and ready_at<=clock_timestamp() limit"
                  + " 50"))
        r.complete((UUID) c.get("id"));
      r.expire();
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(Api.class)
          .warn("maintenance retry: {}", e.getClass().getSimpleName());
    }
  }
}
