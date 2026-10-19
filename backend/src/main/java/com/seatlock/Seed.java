package com.seatlock;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.*;

@Component
class Seed implements ApplicationRunner {
  final JdbcTemplate db;
  final BCryptPasswordEncoder enc;
  final String password;

  Seed(JdbcTemplate d, BCryptPasswordEncoder e, @Value("${seatlock.demo-password}") String p) {
    db = d;
    enc = e;
    password = p;
  }

  public void run(ApplicationArguments a) {
    for (String role : List.of("USER", "OPERATOR", "ADMIN"))
      db.update(
          "insert into app_user(id,email,password,role) values(?,?,?,?) on conflict(email) do"
              + " nothing",
          UUID.nameUUIDFromBytes(role.getBytes()),
          role.toLowerCase() + "@seatlock.local",
          enc.encode(password),
          role);
    String[] titles = {
      "Neon Frequencies", "Courtside: The Finals", "Orbit • A Space Symphony", "The Small Room"
    };
    for (int i = 0; i < titles.length; i++) {
      UUID id = UUID.nameUUIDFromBytes(titles[i].getBytes());
      if (db.queryForObject("select count(*) from event where id=?", Integer.class, id) == 0) {
        db.update(
            "insert into event(id,title,description,art,starts_at,price,rows,cols)"
                + " values(?,?,?,?,clock_timestamp()+interval '30 days',?,?,?)",
            id,
            titles[i],
            "A fictional SeatLock demo event. Every checkout is simulated.",
            "/art/event-" + i + ".svg",
            (i + 1) * 2500,
            i == 3 ? 3 : 8,
            i == 3 ? 4 : 12);
        for (int r = 1; r <= (i == 3 ? 3 : 8); r++)
          for (int c = 1; c <= (i == 3 ? 4 : 12); c++)
            db.update(
                "insert into seat(event_id,label,row_no,col_no) values(?,?,?,?) on conflict do"
                    + " nothing",
                id,
                "" + (char) (64 + r) + c,
                r,
                c);
      }
    }
    db.update(
        "update lab_job set status='INCOMPLETE',result=jsonb_build_object('reason','Backend"
            + " restarted before verification') where status='RUNNING' and"
            + " created_at<clock_timestamp()-interval '2 minutes'");
  }
}
