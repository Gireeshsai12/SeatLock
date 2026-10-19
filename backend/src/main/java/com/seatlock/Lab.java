package com.seatlock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class Lab {
  final Reservations r;
  final boolean enabled;
  final ObjectMapper json;
  final ExecutorService coordinator = Executors.newSingleThreadExecutor();

  Lab(Reservations r, @Value("${seatlock.lab-enabled}") boolean e, ObjectMapper j) {
    this.r = r;
    enabled = e;
    json = j;
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    coordinator.shutdownNow();
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
  void reapStaleJobs() {
    r.db.update(
        "update lab_job set status='INCOMPLETE',result=jsonb_build_object('reason','Run exceeded"
            + " its two-minute recovery lease') where status='RUNNING' and"
            + " created_at<clock_timestamp()-interval '2 minutes'");
  }

  Map<String, Object> start(UUID owner, String scenario, int attempts, int concurrency) {
    if (!enabled) throw new Problem(403, "LAB_DISABLED");
    if (!Set.of("SINGLE", "POOL", "OVERLAP", "EXPIRATION", "CHECKOUT_RACE", "DUPLICATE")
        .contains(scenario)) throw new Problem(400, "UNSUPPORTED_SCENARIO");
    UUID id = UUID.randomUUID(), event = UUID.randomUUID();
    r.tx.executeWithoutResult(
        t -> {
          r.db.execute("select pg_advisory_xact_lock(824107)");
          if (r.db.queryForObject(
                  "select count(*) from lab_job where status='RUNNING'", Integer.class)
              > 0) throw new Problem(409, "LAB_BUSY");
          r.db.update(
              "insert into event(id,title,description,art,starts_at,price,rows,cols,lab)"
                  + " values(?,'Lab run','Isolated synthetic"
                  + " inventory','/art/event-0.svg',clock_timestamp(),100,1,?,true)",
              event,
              scenario.equals("SINGLE") || scenario.equals("DUPLICATE") ? 1 : 4);
          int size = scenario.equals("SINGLE") || scenario.equals("DUPLICATE") ? 1 : 4;
          for (int c = 1; c <= size; c++)
            r.db.update(
                "insert into seat(event_id,label,row_no,col_no) values(?,?,1,?)",
                event,
                "A" + c,
                c);
          r.db.update(
              "insert into lab_job(id,owner_id,event_id,scenario,attempts,concurrency,status)"
                  + " values(?,?,?,?,?,?,'RUNNING')",
              id,
              owner,
              event,
              scenario,
              attempts,
              concurrency);
          r.audit(owner, event, "LAB_START", "SUCCESS");
        });
    coordinator.submit(() -> execute(id, event, scenario, attempts, concurrency));
    return Map.of("id", id);
  }

  void execute(UUID id, UUID event, String scenario, int attempts, int concurrency) {
    long start = System.nanoTime();
    AtomicInteger conflicts = new AtomicInteger(),
        errors = new AtomicInteger(),
        completed = new AtomicInteger();
    List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    ExecutorService workers = Executors.newFixedThreadPool(concurrency);
    boolean complete = false;
    try {
      List<UUID> users = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        UUID u = UUID.randomUUID();
        r.db.update(
            "insert into app_user(id,email,password,role,synthetic) values(?,?,"
                + " 'NO_LOGIN','USER',true)",
            u,
            "lab-" + u + "@invalid.local");
        users.add(u);
      }
      UUID sharedHold = null;
      if (scenario.equals("DUPLICATE"))
        sharedHold = (UUID) r.hold(users.getFirst(), event, List.of("A1")).get("id");
      if (Set.of("EXPIRATION", "CHECKOUT_RACE").contains(scenario)) {
        UUID h = (UUID) r.hold(users.getFirst(), event, List.of("A1")).get("id");
        r.db.update("update hold set expires_at=clock_timestamp() where id=?", h);
        if (scenario.equals("CHECKOUT_RACE")) {
          try {
            r.checkout(users.getFirst(), h, "race-expired-key", "SUCCESS");
            errors.incrementAndGet();
          } catch (Problem p) {
            if (!p.code.equals("HOLD_EXPIRED")) errors.incrementAndGet();
          }
        }
      }
      List<Future<?>> futures = new ArrayList<>();
      final UUID dup = sharedHold;
      for (int i = 0; i < attempts; i++) {
        final int index = i;
        futures.add(
            workers.submit(
                () -> {
                  if (r.db.queryForObject(
                          "select cancel_requested from lab_job where id=?", Boolean.class, id)
                      || System.nanoTime() - start > 60_000_000_000L) return;
                  long st = System.nanoTime();
                  try {
                    if (dup != null) {
                      r.checkout(users.getFirst(), dup, "shared-duplicate-key", "DUPLICATE");
                    } else {
                      List<String> seats =
                          switch (scenario) {
                            case "SINGLE" -> List.of("A1");
                            case "OVERLAP" ->
                                index % 2 == 0 ? List.of("A1", "A2") : List.of("A2", "A3");
                            default -> List.of("A" + (index % 4 + 1));
                          };
                      var h = r.hold(users.get(index), event, seats);
                      r.checkout(
                          users.get(index),
                          (UUID) h.get("id"),
                          UUID.randomUUID().toString(),
                          "SUCCESS");
                    }
                  } catch (Problem p) {
                    if (p.status == 409) conflicts.incrementAndGet();
                    else errors.incrementAndGet();
                  } catch (Exception e) {
                    errors.incrementAndGet();
                  } finally {
                    latencies.add((System.nanoTime() - st) / 1_000_000);
                    int c = completed.incrementAndGet();
                    r.db.update(
                        "update lab_job set completed=greatest(completed,?) where id=?", c, id);
                  }
                }));
      }
      for (Future<?> f : futures) f.get(65, TimeUnit.SECONDS);
      complete =
          completed.get() == attempts
              && !r.db.queryForObject(
                  "select cancel_requested from lab_job where id=?", Boolean.class, id);
    } catch (Exception e) {
      errors.incrementAndGet();
    } finally {
      workers.shutdownNow();
    }
    try {
      Collections.sort(latencies);
      double duration = (System.nanoTime() - start) / 1e9;
      int violations =
          r.db.queryForObject(
              "select count(*) from (select event_id,label,count(*) from booking_item where"
                  + " event_id=? group by event_id,label having count(*)>1) x",
              Integer.class,
              event);
      violations +=
          r.db.queryForObject(
              "select count(*) from booking_item i join seat s on s.event_id=i.event_id and"
                  + " s.label=i.label where i.event_id=? and s.booking_id is distinct from"
                  + " i.booking_id",
              Integer.class,
              event);
      violations +=
          r.db.queryForObject(
              "select count(*) from hold h where h.event_id=? and h.status='BOOKED' and (select"
                  + " count(*) from hold_item where hold_id=h.id)<>(select count(*) from"
                  + " booking_item i join booking b on b.id=i.booking_id where b.hold_id=h.id)",
              Integer.class,
              event);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("totalAttempts", attempts);
      result.put("completed", completed.get());
      result.put(
          "uniqueSeats",
          r.db.queryForObject("select count(*) from seat where event_id=?", Integer.class, event));
      result.put(
          "successfulHolds",
          r.db.queryForObject("select count(*) from hold where event_id=?", Integer.class, event));
      result.put(
          "confirmedBookings",
          r.db.queryForObject(
              "select count(*) from booking where event_id=?", Integer.class, event));
      result.put(
          "bookedSeats",
          r.db.queryForObject(
              "select count(*) from booking_item where event_id=?", Integer.class, event));
      result.put("expectedConflicts", conflicts.get());
      result.put(
          "expiredReleasedHolds",
          r.db.queryForObject(
              "select count(*) from hold where event_id=? and (status in ('RELEASED','EXPIRED') or"
                  + " (status='HELD' and expires_at<=clock_timestamp()))",
              Integer.class,
              event));
      result.put("infrastructureErrors", errors.get());
      result.put("durationSeconds", duration);
      result.put("throughput", completed.get() / duration);
      result.put("p50ms", percentile(latencies, .50));
      result.put("p95ms", percentile(latencies, .95));
      result.put("p99ms", percentile(latencies, .99));
      result.put("invariantViolations", violations);
      result.put("verified", complete && errors.get() == 0);
      result.put(
          "notes",
          "One local JVM; serialized event mutation. Expiration fixtures use DB time. Restart and"
              + " multi-instance checks are separate integration tests.");
      String status =
          !complete
              ? "INCOMPLETE"
              : errors.get() > 0 ? "UNVERIFIED" : violations > 0 ? "FAILED" : "VERIFIED";
      r.db.update(
          "update lab_job set status=?,result=?::jsonb where id=?",
          status,
          json.writeValueAsString(result),
          id);
    } catch (Exception e) {
      r.db.update("update lab_job set status='UNVERIFIED' where id=?", id);
    }
  }

  long percentile(List<Long> x, double p) {
    return x.isEmpty() ? 0 : x.get(Math.min(x.size() - 1, (int) Math.ceil(x.size() * p) - 1));
  }
}
