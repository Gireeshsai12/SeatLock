package com.seatlock;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Reservations {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final int seconds;
  final MeterRegistry metrics;

  public Reservations(
      JdbcTemplate d,
      TransactionTemplate t,
      @Value("${seatlock.hold-seconds}") int s,
      MeterRegistry m) {
    db = d;
    tx = t;
    seconds = s;
    metrics = m;
  }

  UUID uuid(Object v) {
    return v instanceof UUID ? (UUID) v : UUID.fromString(v.toString());
  }

  Map<String, Object> one(String sql, Object... args) {
    var l = db.queryForList(sql, args);
    if (l.isEmpty()) throw new Problem(404, "NOT_FOUND");
    return l.getFirst();
  }

  Instant now() {
    return db.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
  }

  void lock(UUID user, UUID event) {
    one("select id from app_user where id=? for update", user);
    one("select id from event where id=? for update", event);
  }

  void changed(UUID event, UUID user, String op, String outcome) {
    db.update("update event set version=version+1 where id=?", event);
    audit(user, event, op, outcome);
    metrics.counter("seatlock.operations", "operation", op, "outcome", outcome).increment();
  }

  void audit(UUID u, UUID e, String op, String outcome) {
    String correlation = UUID.randomUUID().toString();
    var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      Object c = attrs.getAttribute("correlation", 0);
      if (c != null) correlation = c.toString();
    }
    db.update(
        "insert into audit(actor,event_id,operation,outcome,correlation,details)"
            + " values(?,?,?,?,?,?)",
        u,
        e,
        op,
        outcome,
        correlation,
        "Reservation engine transition");
  }

  List<Map<String, Object>> seats(UUID event) {
    return db.queryForList(
        "select s.label,s.row_no,s.col_no,case when s.booking_id is not null then 'BOOKED' when"
            + " h.status='HELD' and h.expires_at>clock_timestamp() then 'HELD' else 'AVAILABLE' end"
            + " status from seat s left join hold h on s.hold_id=h.id where s.event_id=? order by"
            + " s.row_no,s.col_no",
        event);
  }

  public Map<String, Object> snapshot(UUID e) {
    return tx.execute(
        s -> {
          var ev = one("select * from event where id=? for share", e);
          return Map.of(
              "event",
              ev,
              "seats",
              seats(e),
              "serverTime",
              now().toString(),
              "version",
              ev.get("version"),
              "holdSeconds",
              seconds);
        });
  }

  public Map<String, Object> hold(UUID u, UUID e, List<String> labels) {
    long start = System.nanoTime();
    try {
      return tx.execute(
          t -> {
            if (labels == null
                || labels.isEmpty()
                || labels.size() > 6
                || new HashSet<>(labels).size() != labels.size())
              throw new Problem(400, "INVALID_SEATS");
            lock(u, e);
            Instant n = now();
            if (db.queryForObject(
                    "select count(*) from hold where user_id=? and status='HELD' and expires_at>?",
                    Integer.class,
                    u,
                    Timestamp.from(n))
                >= 1) throw new Problem(409, "ACTIVE_HOLD_LIMIT");
            var sorted = labels.stream().sorted().toList();
            for (String label : sorted) {
              var seat =
                  one(
                      "select s.*,h.status hold_status,h.expires_at from seat s left join hold h on"
                          + " s.hold_id=h.id where s.event_id=? and s.label=? for update of s",
                      e,
                      label);
              if (seat.get("booking_id") != null) throw new Problem(409, "SEAT_BOOKED");
              if ("HELD".equals(seat.get("hold_status"))
                  && ((Timestamp) seat.get("expires_at")).toInstant().isAfter(n))
                throw new Problem(409, "SEAT_HELD");
            }
            UUID id = UUID.randomUUID();
            db.update(
                "insert into hold(id,user_id,event_id,expires_at,status) values(?,?,?,?,'HELD')",
                id,
                u,
                e,
                Timestamp.from(n.plusSeconds(seconds)));
            for (String label : sorted) {
              db.update("update seat set hold_id=? where event_id=? and label=?", id, e, label);
              db.update("insert into hold_item values(?,?,?)", id, e, label);
            }
            changed(e, u, "HOLD", "SUCCESS");
            return ownHold(u, id);
          });
    } finally {
      metrics
          .timer("seatlock.hold.latency")
          .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  public Map<String, Object> ownHold(UUID u, UUID id) {
    var h = one("select * from hold where id=? and user_id=?", id, u);
    h.put(
        "labels",
        db.queryForList(
            "select label from hold_item where hold_id=? order by label", String.class, id));
    h.put("serverTime", now().toString());
    return h;
  }

  public List<Map<String, Object>> active(UUID u) {
    var list =
        db.queryForList(
            "select id from hold where user_id=? and status='HELD' and expires_at>clock_timestamp()"
                + " order by created_at desc",
            u);
    return list.stream().map(h -> ownHold(u, uuid(h.get("id")))).toList();
  }

  public void release(UUID u, UUID id) {
    tx.executeWithoutResult(
        t -> {
          var h = ownHold(u, id);
          UUID e = uuid(h.get("event_id"));
          lock(u, e);
          h = one("select * from hold where id=? for update", id);
          if (!"HELD".equals(h.get("status"))) throw new Problem(409, "INVALID_TRANSITION");
          db.update("update seat set hold_id=null where hold_id=?", id);
          db.update("update hold set status='RELEASED' where id=?", id);
          changed(e, u, "RELEASE", "SUCCESS");
        });
  }

  public Map<String, Object> checkout(UUID u, UUID id, String key, String mode) {
    if (key == null
        || key.length() < 8
        || key.length() > 100
        || !Set.of("SUCCESS", "DECLINE", "DELAY", "TIMEOUT", "DUPLICATE", "LATE").contains(mode))
      throw new Problem(400, "INVALID_CHECKOUT");
    Map<String, Object> attempt =
        tx.execute(
            t -> {
              var h = ownHold(u, id);
              UUID e = uuid(h.get("event_id"));
              lock(u, e);
              var old =
                  db.queryForList("select * from checkout where user_id=? and idem_key=?", u, key);
              if (!old.isEmpty()) {
                var c = old.getFirst();
                if (!c.get("hold_id").equals(id) || !c.get("mode").equals(mode))
                  throw new Problem(409, "IDEMPOTENCY_CONFLICT");
                return c;
              }
              h = one("select * from hold where id=? for update", id);
              if (!"HELD".equals(h.get("status"))
                  || !((Timestamp) h.get("expires_at")).toInstant().isAfter(now()))
                throw new Problem(409, "HOLD_EXPIRED");
              UUID cid = UUID.randomUUID();
              int wait =
                  switch (mode) {
                    case "DELAY" -> 2;
                    case "TIMEOUT" -> 3;
                    case "LATE" -> seconds + 1;
                    default -> 0;
                  };
              db.update(
                  "insert into checkout(id,user_id,hold_id,idem_key,mode,status,ready_at)"
                      + " values(?,?,?,?,?,'PENDING',clock_timestamp()+(? * interval '1 second'))",
                  cid,
                  u,
                  id,
                  key,
                  mode,
                  wait);
              db.update(
                  "insert into payment(id,checkout_id,status,amount) select"
                      + " ?,?,'PENDING',e.price*(select count(*) from hold_item where hold_id=?)"
                      + " from event e where e.id=?",
                  UUID.randomUUID(),
                  cid,
                  id,
                  e);
              return one("select * from checkout where id=?", cid);
            });
    UUID cid = uuid(attempt.get("id"));
    if (Set.of("SUCCESS", "DECLINE", "DUPLICATE").contains(mode)) {
      complete(cid);
      if ("DUPLICATE".equals(mode)) complete(cid);
    }
    return one("select * from checkout where id=?", cid);
  }

  public void complete(UUID cid) {
    tx.executeWithoutResult(
        t -> {
          var c =
              one(
                  "select c.*,h.event_id from checkout c join hold h on h.id=c.hold_id where"
                      + " c.id=?",
                  cid);
          UUID u = uuid(c.get("user_id")),
              e = uuid(c.get("event_id")),
              hid = uuid(c.get("hold_id"));
          lock(u, e);
          c = one("select * from checkout where id=? for update", cid);
          if (!"PENDING".equals(c.get("status"))
              || ((Timestamp) c.get("ready_at")).toInstant().isAfter(now())) return;
          var h = one("select * from hold where id=? for update", hid);
          String status;
          UUID bid = null;
          if ("DECLINE".equals(c.get("mode"))) status = "DECLINED";
          else if ("TIMEOUT".equals(c.get("mode"))) status = "TIMED_OUT";
          else if (!"HELD".equals(h.get("status"))
              || !((Timestamp) h.get("expires_at")).toInstant().isAfter(now()))
            status = "COMPENSATED";
          else {
            var items =
                db.queryForList(
                    "select s.label from seat s join hold_item i on i.event_id=s.event_id and"
                        + " i.label=s.label where i.hold_id=? and s.hold_id=? and s.booking_id is"
                        + " null order by s.label for update of s",
                    hid,
                    hid);
            int count =
                db.queryForObject(
                    "select count(*) from hold_item where hold_id=?", Integer.class, hid);
            if (items.size() != count) throw new Problem(409, "STALE_HOLD");
            bid = UUID.randomUUID();
            db.update(
                "insert into booking(id,reference,user_id,event_id,hold_id) values(?,?,?,?,?)",
                bid,
                "SL-" + bid.toString().substring(0, 8).toUpperCase(),
                u,
                e,
                hid);
            for (var i : items)
              db.update("insert into booking_item values(?,?,?)", bid, e, i.get("label"));
            db.update("update seat set booking_id=?,hold_id=null where hold_id=?", bid, hid);
            db.update("update hold set status='BOOKED' where id=?", hid);
            status = "SUCCEEDED";
          }
          db.update("update checkout set status=?,booking_id=? where id=?", status, bid, cid);
          db.update("update payment set status=? where checkout_id=?", status, cid);
          changed(e, u, "CHECKOUT", status);
        });
  }

  public List<Map<String, Object>> bookings(UUID u) {
    return db.queryForList(
        "select b.*,e.title,(select string_agg(label,', ' order by label) from booking_item where"
            + " booking_id=b.id) labels from booking b join event e on e.id=b.event_id where"
            + " b.user_id=? order by b.created_at desc",
        u);
  }

  public void expire() {
    for (var row :
        db.queryForList(
            "select id,user_id,event_id from hold where status='HELD' and"
                + " expires_at<=clock_timestamp() limit 100")) {
      tx.executeWithoutResult(
          t -> {
            UUID u = uuid(row.get("user_id")),
                e = uuid(row.get("event_id")),
                id = uuid(row.get("id"));
            lock(u, e);
            var h = one("select * from hold where id=? for update", id);
            if ("HELD".equals(h.get("status"))
                && !((Timestamp) h.get("expires_at")).toInstant().isAfter(now())) {
              db.update("update seat set hold_id=null where hold_id=?", id);
              db.update("update hold set status='EXPIRED' where id=?", id);
              changed(e, u, "EXPIRE", "SUCCESS");
            }
          });
    }
  }
}
