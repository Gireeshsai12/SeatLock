package com.seatlock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties = {"seatlock.hold-seconds=30", "seatlock.lab-enabled=false"})
class ReservationITTest {
  @Autowired org.springframework.test.web.servlet.MockMvc http;
  @Autowired Reservations r;
  @Autowired JdbcTemplate db;
  UUID event, u, v;

  @BeforeEach
  void setup() {
    event = UUID.randomUUID();
    u = user();
    v = user();
    db.update(
        "insert into event(id,title,description,art,starts_at,price,rows,cols,lab)"
            + " values(?,'Integration','test','/art/event-0.svg',clock_timestamp(),100,1,4,true)",
        event);
    for (int i = 1; i <= 4; i++)
      db.update(
          "insert into seat(event_id,label,row_no,col_no) values(?,?,1,?)", event, "A" + i, i);
  }

  UUID user() {
    UUID u = UUID.randomUUID();
    db.update("insert into app_user values(?,?,'disabled','USER',true)", u, u + "@test.invalid");
    return u;
  }

  UUID hold(UUID user, String... seats) {
    return (UUID) r.hold(user, event, List.of(seats)).get("id");
  }

  void expires(UUID h) {
    db.update("update hold set expires_at=clock_timestamp()-interval '1 second' where id=?", h);
  }

  long count(String table) {
    return db.queryForObject(
        "select count(*) from " + table + " where event_id=?", Long.class, event);
  }

  void code(String expected, Runnable action) {
    assertEquals(expected, assertThrows(Problem.class, action::run).code);
  }

  @Test
  void singleBooking() {
    UUID h = hold(u, "A1");
    var c = r.checkout(u, h, "single-success-key", "SUCCESS");
    assertEquals("SUCCEEDED", c.get("status"));
    assertEquals(1, count("booking_item"));
    assertEquals(1, r.bookings(u).size());
  }

  @Test
  void multiSeatAtomicity() {
    hold(u, "A2");
    code("SEAT_HELD", () -> hold(v, "A1", "A2"));
    assertEquals("AVAILABLE", r.seats(event).getFirst().get("status"));
    assertEquals(1, count("hold"));
  }

  @Test
  void simultaneousHolds() throws Exception {
    var pool = Executors.newFixedThreadPool(24);
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<Boolean>>();
    for (int i = 0; i < 24; i++) {
      UUID x = user();
      futures.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  hold(x, "A1");
                  return true;
                } catch (Problem p) {
                  assertEquals("SEAT_HELD", p.code);
                  return false;
                }
              }));
    }
    start.countDown();
    int wins = 0;
    for (var f : futures) if (f.get(20, TimeUnit.SECONDS)) wins++;
    pool.shutdownNow();
    assertEquals(1, wins);
    assertEquals(1, count("hold"));
  }

  @Test
  void expiredCannotCheckout() {
    UUID h = hold(u, "A1");
    expires(h);
    code("HOLD_EXPIRED", () -> r.checkout(u, h, "expired-checkout-key", "SUCCESS"));
    assertEquals(0, count("booking"));
  }

  @Test
  void expiresWithoutWorker() {
    UUID h = hold(u, "A1");
    expires(h);
    UUID second = hold(v, "A1");
    assertNotEquals(h, second);
    assertEquals(
        "SUCCEEDED", r.checkout(v, second, "after-expiration-key", "SUCCESS").get("status"));
  }

  @Test
  void releaseOwnership() {
    UUID h = hold(u, "A1");
    code("NOT_FOUND", () -> r.release(v, h));
    code("NOT_FOUND", () -> r.ownHold(v, h));
    code("NOT_FOUND", () -> r.checkout(v, h, "unauthorized-key", "SUCCESS"));
    assertEquals("HELD", r.ownHold(u, h).get("status"));
  }

  @Test
  void duplicateCheckout() {
    UUID h = hold(u, "A1");
    var first = r.checkout(u, h, "duplicate-submit-key", "DUPLICATE");
    var second = r.checkout(u, h, "duplicate-submit-key", "DUPLICATE");
    assertEquals(first.get("booking_id"), second.get("booking_id"));
    assertEquals(1, count("booking"));
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from payment where checkout_id=?", Integer.class, first.get("id")));
  }

  @Test
  void incompatiblePayload() {
    UUID h = hold(u, "A1");
    r.checkout(u, h, "incompatible-key", "DECLINE");
    code("IDEMPOTENCY_CONFLICT", () -> r.checkout(u, h, "incompatible-key", "SUCCESS"));
  }

  @Test
  void declinedPayment() {
    UUID h = hold(u, "A1");
    assertEquals("DECLINED", r.checkout(u, h, "decline-test-key", "DECLINE").get("status"));
    assertEquals(0, count("booking"));
    assertEquals("HELD", r.ownHold(u, h).get("status"));
  }

  @Test
  void lateCompensation() {
    UUID h = hold(u, "A1");
    var c = r.checkout(u, h, "late-payment-key", "LATE");
    expires(h);
    UUID h2 = hold(v, "A1");
    r.checkout(v, h2, "new-payment-key", "SUCCESS");
    db.update("update checkout set ready_at=clock_timestamp() where id=?", c.get("id"));
    r.complete((UUID) c.get("id"));
    assertEquals(
        "COMPENSATED", r.one("select status from checkout where id=?", c.get("id")).get("status"));
    assertEquals(1, count("booking_item"));
  }

  @Test
  void timeoutDoesNotBook() {
    UUID h = hold(u, "A1");
    var c = r.checkout(u, h, "timeout-test-key", "TIMEOUT");
    db.update("update checkout set ready_at=clock_timestamp() where id=?", c.get("id"));
    r.complete((UUID) c.get("id"));
    assertEquals(
        "TIMED_OUT", r.one("select status from checkout where id=?", c.get("id")).get("status"));
    assertEquals(0, count("booking_item"));
  }

  @Test
  void activeHoldLimit() {
    hold(u, "A1");
    code("ACTIVE_HOLD_LIMIT", () -> hold(u, "A2"));
  }

  @Test
  void invalidSeatAndDuplicate() {
    code("INVALID_SEATS", () -> hold(u, "A1", "A1"));
    code("NOT_FOUND", () -> hold(u, "Z99"));
    assertEquals(0, count("hold"));
  }

  @Test
  void eventIsolation() {
    UUID h = hold(u, "A1"), other = UUID.randomUUID();
    db.update(
        "insert into event(id,title,description,art,starts_at,price,rows,cols,lab)"
            + " values(?,'Other','test','/art/event-0.svg',clock_timestamp(),100,1,1,true)",
        other);
    db.update("insert into seat(event_id,label,row_no,col_no) values(?,'A1',1,1)", other);
    assertNotNull(r.hold(v, other, List.of("A1")));
    assertNotNull(h);
  }

  @Test
  void releaseCheckoutRace() throws Exception {
    UUID h = hold(u, "A1");
    race(
        () -> {
          try {
            r.release(u, h);
          } catch (Problem p) {
            assertEquals("INVALID_TRANSITION", p.code);
          }
        },
        () -> {
          try {
            r.checkout(u, h, "release-race-key", "SUCCESS");
          } catch (Problem p) {
            assertEquals("HOLD_EXPIRED", p.code);
          }
        });
    assertTrue(count("booking") <= 1);
    var s = r.seats(event).getFirst();
    assertTrue(Set.of("AVAILABLE", "BOOKED").contains(s.get("status")));
  }

  @Test
  void expirationCheckoutRace() throws Exception {
    UUID h = hold(u, "A1");
    race(
        () ->
            r.tx.executeWithoutResult(
                t -> {
                  r.lock(u, event);
                  db.update(
                      "update hold set expires_at=clock_timestamp()-interval '1 second' where id=?"
                          + " and status='HELD'",
                      h);
                }),
        () -> {
          try {
            r.checkout(u, h, "expiration-race-key", "SUCCESS");
          } catch (Problem p) {
            assertEquals("HOLD_EXPIRED", p.code);
          }
        });
    assertTrue(count("booking") <= 1);
  }

  @Test
  void concurrentDuplicate() throws Exception {
    UUID h = hold(u, "A1");
    race(
        () -> r.checkout(u, h, "same-concurrent-key", "SUCCESS"),
        () -> r.checkout(u, h, "same-concurrent-key", "SUCCESS"));
    assertEquals(1, count("booking"));
  }

  @Test
  void distinctServiceInstances() throws Exception {
    var second = new Reservations(db, r.tx, 30, r.metrics);
    race(
        () -> {
          try {
            r.hold(u, event, List.of("A1"));
          } catch (Problem p) {
            assertEquals("SEAT_HELD", p.code);
          }
        },
        () -> {
          try {
            second.hold(v, event, List.of("A1"));
          } catch (Problem p) {
            assertEquals("SEAT_HELD", p.code);
          }
        });
    assertEquals(1, count("hold"));
  }

  @Test
  void newServiceRecoversHold() {
    UUID h = hold(u, "A1");
    var restarted = new Reservations(db, r.tx, 30, r.metrics);
    assertEquals(h, restarted.active(u).getFirst().get("id"));
    restarted.checkout(u, h, "recover-service-key", "SUCCESS");
    assertEquals(1, restarted.bookings(u).size());
  }

  @Test
  void labReportIsStructuredJSON() throws Exception {
    UUID job = UUID.randomUUID();
    db.update("update app_user set role='OPERATOR' where id=?", u);
    db.update(
        "insert into auth_token values(?,?,clock_timestamp()+interval '1 hour')",
        Security.hash("contract-token"),
        u);
    db.update(
        "insert into lab_job(id,owner_id,event_id,scenario,attempts,concurrency,status,result)"
            + " values(?,?,?,'SINGLE',100,12,'VERIFIED','{\"p95ms\":12,\"invariantViolations\":0}')",
        job,
        u,
        event);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/lab/" + job + "/report")
                .header("Authorization", "Bearer contract-token"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.result.p95ms")
                .value(12));
  }

  @Test
  void userCannotRunLab() throws Exception {
    db.update(
        "insert into auth_token values(?,?,clock_timestamp()+interval '1 hour')",
        Security.hash("user-contract-token"),
        u);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/lab")
                .header("Authorization", "Bearer user-contract-token")
                .contentType("application/json")
                .content("{\"scenario\":\"SINGLE\",\"attempts\":100,\"concurrency\":12}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }

  void race(Runnable a, Runnable b) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    var f =
        pool.submit(
            () -> {
              await(start);
              a.run();
            });
    var g =
        pool.submit(
            () -> {
              await(start);
              b.run();
            });
    start.countDown();
    try {
      f.get(20, TimeUnit.SECONDS);
      g.get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  void await(CountDownLatch c) {
    try {
      c.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
