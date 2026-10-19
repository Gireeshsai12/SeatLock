import React, { useState, useEffect, useRef } from "react";
import { createRoot } from "react-dom/client";
import {
  Armchair,
  ShieldCheck,
  Activity,
  ArrowUpRight,
  LockKeyhole,
  Clock3,
  Check,
  LogOut,
  FlaskConical,
  Ticket,
  Search,
  RefreshCw,
  ChevronRight,
  CalendarDays,
  MapPin,
  Radio,
  Download,
  Play,
  Square,
  Terminal,
  Layers,
} from "lucide-react";
import "./style.css";
type Event = {
  id: string;
  title: string;
  description: string;
  art: string;
  starts_at: string;
  price: number;
  rows: number;
  cols: number;
};
type Seat = { label: string; row_no: number; col_no: number; status: string };
type Hold = {
  id: string;
  event_id: string;
  expires_at: string;
  labels: string[];
  serverTime: string;
};
type Session = { token: string; role: string; email: string };
type Job = {
  id: string;
  scenario: string;
  status: string;
  attempts: number;
  completed: number;
  result: Record<string, unknown> | null;
};
let token = "";
async function api<T = any>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const res = await fetch("/api" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + token,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let x;
    try {
      x = await res.json();
    } catch {}
    throw new Error(x?.message || `Request failed (${res.status})`);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}
const money = (n: number) =>
  new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(
    n / 100,
  );
const date = (s: string) =>
  new Date(s).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
function App() {
  const [session, setSession] = useState<Session | null>(() =>
    JSON.parse(sessionStorage.getItem("seatlock") || "null"),
  );
  token = session?.token || "";
  const [page, setPage] = useState("Events"),
    [events, setEvents] = useState<Event[]>([]),
    [event, setEvent] = useState<Event | null>(null),
    [seats, setSeats] = useState<Seat[]>([]),
    [selected, setSelected] = useState<string[]>([]),
    [hold, setHold] = useState<Hold | null>(null),
    [mode, setMode] = useState("SUCCESS"),
    [checkout, setCheckout] = useState<any>(null),
    [bookings, setBookings] = useState<any[]>([]),
    [jobs, setJobs] = useState<Job[]>([]),
    [logs, setLogs] = useState<any[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [connection, setConnection] = useState("Connecting"),
    [query, setQuery] = useState(""),
    [tick, setTick] = useState(Date.now()),
    [offset, setOffset] = useState(0),
    [holdSeconds, setHoldSeconds] = useState(300),
    [scenario, setScenario] = useState("SINGLE"),
    [attempts, setAttempts] = useState(100),
    [concurrency, setConcurrency] = useState(12),
    [email, setEmail] = useState("operator@seatlock.local"),
    [password, setPassword] = useState("SeatLock-demo-2026!"),
    [opFilter, setOpFilter] = useState(""),
    [outcomeFilter, setOutcomeFilter] = useState(""),
    [eventFilter, setEventFilter] = useState(""),
    [inspection, setInspection] = useState<any>(null);
  const ver = useRef(-1);
  const checkoutKeys = useRef(new Map<string, string>());
  const [editId, setEditId] = useState(""),
    [form, setForm] = useState({
      title: "",
      description: "",
      rows: 8,
      cols: 12,
      price: 4500,
    });
  async function act(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function loadEvents() {
    const x = await api<Event[]>("/events");
    setEvents(x);
    setEvent((e) => x.find((v) => v.id === e?.id) || x[0] || null);
  }
  async function recover() {
    const hs = await api<Hold[]>("/holds");
    setHold(hs[0] || null);
    if (hs[0]) setOffset(Date.parse(hs[0].serverTime) - Date.now());
  }
  useEffect(() => {
    if (!session) return;
    act(async () => {
      await loadEvents();
      await recover();
      setBookings(await api("/bookings"));
    });
  }, [session]);
  useEffect(() => {
    const t = setInterval(() => setTick(Date.now()), 250);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (!session || !event) return;
    setSelected([]);
    setSeats([]);
    ver.current = -1;
    let stopped = false;
    let abort: AbortController | undefined;
    const id = event.id;
    function apply(s: any) {
      setHoldSeconds(s.holdSeconds || 300);
      if (Number(s.version) >= ver.current) {
        ver.current = Number(s.version);
        setSeats(s.seats);
        setOffset(Date.parse(s.serverTime) - Date.now());
      }
    }
    async function connect() {
      while (!stopped) {
        try {
          setConnection("Connecting");
          const s = await api("/events/" + id);
          if (stopped) return;
          apply(s);
          abort = new AbortController();
          const res = await fetch("/api/events/" + id + "/stream", {
            headers: { Authorization: "Bearer " + token },
            signal: abort.signal,
          });
          if (!res.ok || !res.body) throw Error("stream unavailable");
          setConnection("Live");
          const reader = res.body.getReader(),
            dec = new TextDecoder();
          let buffer = "";
          while (!stopped) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += dec.decode(value, { stream: true });
            let i;
            while ((i = buffer.indexOf("\n\n")) >= 0) {
              const block = buffer.slice(0, i);
              buffer = buffer.slice(i + 2);
              const data = block
                .split("\n")
                .filter((x) => x.startsWith("data:"))
                .map((x) => x.slice(5))
                .join("\n");
              if (data && !stopped) apply(JSON.parse(data));
            }
          }
        } catch {}
        if (!stopped) {
          setConnection("Reconnecting");
          await new Promise((r) => setTimeout(r, 2000));
        }
      }
    }
    connect();
    return () => {
      stopped = true;
      abort?.abort();
    };
  }, [event?.id, session]);
  useEffect(() => {
    if (!session) return;
    const t = setInterval(() => {
      if (
        session.role !== "USER" &&
        (page === "Load Test" || page === "Events")
      )
        api<Job[]>("/lab")
          .then(setJobs)
          .catch(() => {});
      if (checkout?.status === "PENDING")
        api("/checkout/" + checkout.id)
          .then((c) => {
            setCheckout(c);
            if (c.status === "SUCCEEDED") {
              recover();
              api("/bookings").then(setBookings);
            }
          })
          .catch((e) => setError(e.message));
    }, 1500);
    return () => clearInterval(t);
  }, [session, page, checkout?.id, checkout?.status]);
  async function loadLogs(before = 0) {
    const data = await api(
      "/logs?before=" +
        before +
        "&operation=" +
        encodeURIComponent(opFilter) +
        "&outcome=" +
        encodeURIComponent(outcomeFilter) +
        (eventFilter ? "&event=" + eventFilter : ""),
    );
    setLogs((x) => (before ? [...x, ...data] : data));
  }
  useEffect(() => {
    if (page === "My bookings")
      act(async () => setBookings(await api("/bookings")));
    if (page === "Logs") act(() => loadLogs());
    if (page === "Load Test") act(async () => setJobs(await api("/lab")));
  }, [page]);
  const remaining = hold
    ? Math.max(
        0,
        Math.ceil((Date.parse(hold.expires_at) - tick - offset) / 1000),
      )
    : 0;
  const mine =
    hold && remaining > 0 && hold.event_id === event?.id ? hold.labels : [];
  const operator = session?.role !== "USER";
  const labPanel = (compact = false) => (
    <section className={"panel lab " + (compact ? "compact" : "")}>
      <div className="section-head">
        <div className="icon-label">
          <FlaskConical size={20} />
          <div>
            <h2>Concurrency laboratory</h2>
            <p>Real requests. Finite inventory. Measured correctness.</p>
          </div>
        </div>
        <span className="tag">ISOLATED SANDBOX</span>
      </div>
      <div className="lab-controls">
        <label>
          Scenario
          <select
            value={scenario}
            onChange={(e) => setScenario(e.target.value)}
          >
            <option value="SINGLE">Many users → one seat</option>
            <option value="POOL">Small seat pool</option>
            <option value="OVERLAP">Overlapping multi-seat holds</option>
            <option value="EXPIRATION">Reserve after expiration</option>
            <option value="CHECKOUT_RACE">Checkout at expired boundary</option>
            <option value="DUPLICATE">Duplicate checkout callbacks</option>
          </select>
        </label>
        <label>
          Attempts
          <input
            type="number"
            min="2"
            max="300"
            value={attempts}
            onChange={(e) => setAttempts(+e.target.value)}
          />
        </label>
        <label>
          Concurrency
          <input
            type="number"
            min="1"
            max="24"
            value={concurrency}
            onChange={(e) => setConcurrency(+e.target.value)}
          />
        </label>
        <button
          className="primary"
          disabled={
            !operator || busy || jobs.some((j) => j.status === "RUNNING")
          }
          title={!operator ? "Operator permission required" : ""}
          onClick={() =>
            act(async () => {
              await api("/lab", "POST", { scenario, attempts, concurrency });
              setJobs(await api("/lab"));
            })
          }
        >
          <Play size={15} />
          Run experiment
        </button>
      </div>
      {!operator && <p>Operator permission required to run experiments.</p>}
      {jobs.slice(0, compact ? 1 : 20).map((j) => (
        <div className="job" key={j.id}>
          <div className="section-head">
            <strong>
              {j.scenario}{" "}
              <span className={j.status === "VERIFIED" ? "good" : "muted"}>
                {j.status}
              </span>
            </strong>
            <span>
              {j.completed} / {j.attempts} attempts{" "}
              {j.status === "RUNNING" ? (
                <button
                  onClick={() =>
                    act(async () => {
                      await api("/lab/" + j.id + "/cancel", "POST");
                    })
                  }
                >
                  <Square size={12} />
                  Cancel
                </button>
              ) : (
                <button
                  onClick={() =>
                    act(async () => {
                      const data = await api("/lab/" + j.id + "/report");
                      const url = URL.createObjectURL(
                        new Blob([JSON.stringify(data, null, 2)], {
                          type: "application/json",
                        }),
                      );
                      const a = document.createElement("a");
                      a.href = url;
                      a.download = "seatlock-" + j.id + ".json";
                      a.click();
                      URL.revokeObjectURL(url);
                    })
                  }
                >
                  <Download size={13} />
                  Report
                </button>
              )}
            </span>
          </div>
          <progress max={j.attempts} value={j.completed} />
          {j.result && (
            <div className="metrics">
              {Object.entries(j.result)
                .filter(([k]) => !["notes", "verified"].includes(k))
                .map(([k, v]) => (
                  <div key={k}>
                    <strong>
                      {typeof v === "number"
                        ? Number.isInteger(v)
                          ? v
                          : v.toFixed(2)
                        : String(v)}
                    </strong>
                    <span>{k.replace(/([A-Z])/g, " $1")}</span>
                  </div>
                ))}
            </div>
          )}
        </div>
      ))}
      {jobs.length === 0 && (
        <div className="empty">
          No measured runs yet. Start an experiment to generate a report.
        </div>
      )}
      {!compact && (
        <p className="note">
          Restart recovery and two-instance contention are exercised by the
          integration test suite, not by this single-process generator.
          Event-level locking favors understandable correctness over maximum
          throughput. Maximum run: 60 seconds; 300 attempts; 24 workers.
        </p>
      )}
    </section>
  );
  if (!session)
    return (
      <div className="login">
        <div className="brand">
          <LockKeyhole />
          SeatLock<span>RESERVATION ENGINE</span>
        </div>
        <section className="panel">
          <span className="eyebrow">YOUR SEAT. GUARANTEED.</span>
          <h1>
            One seat.
            <br />
            One rightful owner.
          </h1>
          <p>
            Explore a reservation engine built for the moments when everyone
            clicks at once.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              act(async () => {
                const s = await api("/auth/login", "POST", { email, password });
                sessionStorage.setItem("seatlock", JSON.stringify(s));
                setSession(s);
              });
            }}
          >
            <label>
              Email
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </label>
            <label>
              Password
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>
            <button className="primary" disabled={busy}>
              Enter SeatLock <ArrowUpRight size={16} />
            </button>
          </form>
          {error && (
            <div role="alert" className="error">
              {error}
            </div>
          )}
          <p className="note">
            Local demo: user@seatlock.local, operator@seatlock.local or
            admin@seatlock.local.
            <br />
            Default password: SeatLock-demo-2026!
          </p>
        </section>
        <p className="muted">
          PostgreSQL authority · Transactional holds · Simulated checkout
        </p>
      </div>
    );
  return (
    <>
      <header>
        <div className="brand">
          <LockKeyhole size={27} />
          SeatLock<span>RESERVATION ENGINE</span>
        </div>
        <nav>
          {["Events", "My bookings", "Operations", "Load Test", "Logs"].map(
            (p) => (
              <button
                className={p === page ? "active" : ""}
                key={p}
                disabled={
                  !operator && ["Operations", "Load Test", "Logs"].includes(p)
                }
                title={!operator ? "Operator permission required" : ""}
                onClick={() => setPage(p)}
              >
                {p}
              </button>
            ),
          )}
        </nav>
        <div className="account">
          <span className="tag">{session.role}</span>
          <button
            aria-label="Sign out"
            onClick={() =>
              act(async () => {
                await api("/auth/logout", "POST");
                sessionStorage.removeItem("seatlock");
                setSession(null);
                setHold(null);
                setCheckout(null);
              })
            }
          >
            <LogOut size={17} />
          </button>
        </div>
      </header>
      <main>
        <div className="page-head">
          <div>
            <div className="eyebrow">THE RESERVATION ENGINE</div>
            <h1>
              {page === "Events"
                ? "Great experiences start with a seat."
                : page}
            </h1>
            <p>
              {page === "Events"
                ? "Choose your moment. We’ll keep your place."
                : "Observe, operate, and verify the reservation lifecycle."}
            </p>
          </div>
          <span
            className={"connection " + (connection === "Live" ? "good" : "")}
          >
            <Radio size={14} />
            {connection}
          </span>
        </div>
        {error && (
          <div className="error" role="alert">
            {error}
            <button onClick={() => setError("")}>Dismiss</button>
          </div>
        )}
        {busy && (
          <div className="working" role="status">
            Working…
          </div>
        )}
        {page === "Events" && (
          <>
            <div className="workspace">
              <aside className="panel catalogue">
                <div className="section-head">
                  <h2>Discover events</h2>
                  <span className="muted">{events.length}</span>
                </div>
                <div className="search">
                  <Search size={16} />
                  <input
                    aria-label="Search events"
                    placeholder="Find your next experience"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                  />
                </div>
                {events
                  .filter((e) =>
                    e.title.toLowerCase().includes(query.toLowerCase()),
                  )
                  .map((e, i) => (
                    <button
                      key={e.id}
                      className={
                        "event-card " + (e.id === event?.id ? "chosen" : "")
                      }
                      onClick={() => {
                        setEvent(e);
                        setCheckout(null);
                      }}
                    >
                      <div className="art-wrap">
                        <img
                          src={e.art}
                          alt="Original abstract event artwork"
                        />
                        <span className="tag">
                          {i === 0
                            ? "LIVE EXPERIENCE"
                            : i === 1
                              ? "LIMITED SEATS"
                              : "FICTIONAL EVENT"}
                        </span>
                      </div>
                      <div className="event-info">
                        <strong>{e.title}</strong>
                        <span>{date(e.starts_at)}</span>
                        <div>
                          From {money(e.price)} <ArrowUpRight size={14} />
                        </div>
                      </div>
                    </button>
                  ))}
                {!events.length && <p>No events yet.</p>}
              </aside>
              <section className="panel seat-panel">
                {event ? (
                  <>
                    <div className="section-head">
                      <div>
                        <span className="eyebrow">
                          LIVE EXPERIENCE / RESERVED SEATING
                        </span>
                        <h2 className="event-title">{event.title}</h2>
                        <div className="event-meta">
                          <span>
                            <CalendarDays size={14} />
                            {date(event.starts_at)}
                          </span>
                          <span>
                            <MapPin size={14} />
                            SeatLock Hall
                          </span>
                        </div>
                      </div>
                      <span className="tag">{money(event.price)} / SEAT</span>
                    </div>
                    <div className="stage">
                      <span>STAGE</span>
                      <div />
                    </div>
                    <div className="map-scroll">
                      <div
                        className="seat-grid"
                        style={{
                          gridTemplateColumns: `24px repeat(${event.cols}, minmax(24px, 1fr))`,
                          minWidth: event.cols * 31 + 24,
                        }}
                      >
                        <span />
                        {Array.from({ length: event.cols }, (_, i) => (
                          <span className="coordinate" key={"c" + i}>
                            {i + 1}
                          </span>
                        ))}
                        {Array.from({ length: event.rows }, (_, r) => (
                          <React.Fragment key={r}>
                            <span className="coordinate">
                              {String.fromCharCode(65 + r)}
                            </span>
                            {Array.from({ length: event.cols }, (_, c) => {
                              const label =
                                  String.fromCharCode(65 + r) + (c + 1),
                                s = seats.find((x) => x.label === label),
                                own = mine.includes(label) && remaining > 0,
                                sel = selected.includes(label);
                              return (
                                <button
                                  key={label}
                                  aria-label={`${label} ${own ? "Your hold" : s?.status || "Loading"}`}
                                  aria-pressed={sel || own}
                                  title={`${label} · ${own ? "Your hold" : s?.status}`}
                                  disabled={
                                    !s ||
                                    s.status !== "AVAILABLE" ||
                                    (!!hold && remaining > 0) ||
                                    busy
                                  }
                                  className={
                                    "seat " +
                                    (own
                                      ? "mine"
                                      : sel
                                        ? "selected"
                                        : s?.status?.toLowerCase() || "loading")
                                  }
                                  onClick={() =>
                                    setSelected((x) =>
                                      sel
                                        ? x.filter((v) => v !== label)
                                        : x.length < 6
                                          ? [...x, label]
                                          : x,
                                    )
                                  }
                                >
                                  {s?.status === "BOOKED" ? (
                                    <span>×</span>
                                  ) : sel || own ? (
                                    <Check size={13} />
                                  ) : (
                                    <Armchair size={15} />
                                  )}
                                </button>
                              );
                            })}
                          </React.Fragment>
                        ))}
                      </div>
                    </div>
                    <div className="legend">
                      <span>
                        <i />
                        Available
                      </span>
                      <span>
                        <i className="blue" />
                        Held / Selected
                      </span>
                      <span>
                        <i className="red" />
                        Booked
                      </span>
                    </div>
                    <div className="seat-note">
                      <ShieldCheck size={16} />
                      <span>
                        Every seat is protected by a database transaction.
                        <br />
                        <small>
                          Pick up to 6 seats. Your hold lasts {holdSeconds}{" "}
                          seconds.
                        </small>
                      </span>
                    </div>
                    {seats.length > 0 &&
                      seats.every((s) => s.status !== "AVAILABLE") && (
                        <p className="error">
                          No available seats right now. Held seats may become
                          available later.
                        </p>
                      )}
                  </>
                ) : (
                  <div className="empty">Loading event inventory…</div>
                )}
              </section>
              <aside className="panel checkout">
                <div className="section-head">
                  <h2>Your reservation</h2>
                  <Ticket size={19} />
                </div>
                <div className="timer">
                  <div>
                    <Clock3 size={17} />
                    {hold && remaining > 0 ? "HOLD EXPIRES IN" : "HOLD WINDOW"}
                  </div>
                  <strong>
                    {hold
                      ? `${Math.floor(remaining / 60)
                          .toString()
                          .padStart(
                            2,
                            "0",
                          )}:${(remaining % 60).toString().padStart(2, "0")}`
                      : `${Math.floor(holdSeconds / 60)
                          .toString()
                          .padStart(
                            2,
                            "0",
                          )}:${(holdSeconds % 60).toString().padStart(2, "0")}`}
                  </strong>
                  <p>
                    {hold
                      ? remaining > 0
                        ? "Server-authoritative expiration"
                        : "Your hold has expired. Select seats again."
                      : "Reserve your seats before checkout"}
                  </p>
                </div>
                <div className="section-head">
                  <strong>Selected seats</strong>
                  <span className="tag">
                    {mine.length || selected.length} SEATS
                  </span>
                </div>
                <div className="seat-chips">
                  {(mine.length ? mine : selected).map((s) => (
                    <span key={s}>
                      <Armchair size={14} />
                      {s}
                    </span>
                  ))}
                  {!mine.length && !selected.length && (
                    <p className="muted">Choose a seat from the map.</p>
                  )}
                </div>
                {hold && hold.event_id !== event?.id && (
                  <p className="note">
                    You have a hold at another event. Release it before
                    reserving here.
                  </p>
                )}
                <div className="total">
                  <span>Total · simulated</span>
                  <strong>
                    {money(
                      (mine.length || selected.length) * (event?.price || 0),
                    )}
                  </strong>
                </div>
                {!hold || remaining === 0 ? (
                  <button
                    className="primary full"
                    disabled={!selected.length || busy}
                    onClick={() =>
                      act(async () => {
                        const h = await api<Hold>("/holds", "POST", {
                          eventId: event?.id,
                          seats: selected,
                        });
                        setHold(h);
                        setSelected([]);
                        setCheckout(null);
                        setOffset(Date.parse(h.serverTime) - Date.now());
                      })
                    }
                  >
                    <LockKeyhole size={16} />
                    Hold selected seats
                  </button>
                ) : (
                  <>
                    <label>
                      Simulated checkout
                      <select
                        value={mode}
                        onChange={(e) => setMode(e.target.value)}
                      >
                        {[
                          "SUCCESS",
                          "DECLINE",
                          "DELAY",
                          "TIMEOUT",
                          "DUPLICATE",
                          "LATE",
                        ].map((m) => (
                          <option key={m}>{m}</option>
                        ))}
                      </select>
                    </label>
                    <button
                      className="primary full"
                      disabled={
                        busy ||
                        hold.event_id !== event?.id ||
                        checkout?.status === "PENDING"
                      }
                      onClick={() =>
                        act(async () => {
                          const fingerprint = hold.id + ":" + mode;
                          const key =
                            checkoutKeys.current.get(fingerprint) ||
                            crypto.randomUUID();
                          checkoutKeys.current.set(fingerprint, key);
                          const c = await api("/checkout", "POST", {
                            holdId: hold.id,
                            key,
                            mode,
                          });
                          setCheckout({ ...c, key });
                          if (c.status === "SUCCEEDED") {
                            setHold(null);
                            setBookings(await api("/bookings"));
                          }
                        })
                      }
                    >
                      <Check size={16} />
                      Complete simulated checkout
                    </button>
                    <button
                      className="full"
                      disabled={busy}
                      onClick={() =>
                        act(async () => {
                          await api("/holds/" + hold.id, "DELETE");
                          setHold(null);
                          setSelected([]);
                        })
                      }
                    >
                      Release hold
                    </button>
                  </>
                )}
                <p className="secure">
                  <ShieldCheck size={13} />
                  No real payment. No card information.
                </p>
                {checkout && (
                  <div
                    className={
                      "confirmation " +
                      (checkout.status === "SUCCEEDED" ? "good" : "")
                    }
                    role="status"
                  >
                    <strong>
                      {checkout.status === "SUCCEEDED"
                        ? "Booking confirmed"
                        : checkout.status}
                    </strong>
                    {checkout.booking_id && (
                      <p>
                        {bookings.find((b) => b.id === checkout.booking_id)
                          ?.reference || checkout.booking_id}
                      </p>
                    )}
                    {checkout.status === "PENDING" && (
                      <p>Waiting for the simulated callback…</p>
                    )}
                    {checkout.status === "COMPENSATED" && (
                      <p>Hold no longer valid. Fictional payment reversed.</p>
                    )}
                  </div>
                )}
                <div className="engine">
                  <Activity size={16} />
                  <div>
                    <strong>Built for contention</strong>
                    <p>
                      Atomic holds. Idempotent checkout.
                      <br />
                      One rightful owner per seat.
                    </p>
                  </div>
                </div>
              </aside>
            </div>
            {labPanel(true)}
          </>
        )}
        {page === "My bookings" && (
          <section className="panel">
            <div className="section-head">
              <h2>Your confirmed experiences</h2>
              <button
                onClick={() =>
                  act(async () => setBookings(await api("/bookings")))
                }
              >
                <RefreshCw size={15} />
                Refresh
              </button>
            </div>
            {bookings.map((b) => (
              <article className="booking" key={b.id}>
                <Ticket />
                <div>
                  <h2>{b.title}</h2>
                  <p>
                    {b.labels} · {date(b.created_at)}
                  </p>
                  <strong className="good">{b.reference}</strong>
                </div>
                <span className="tag">CONFIRMED</span>
              </article>
            ))}
            {!bookings.length && (
              <div className="empty">
                Your first experience is waiting. Select seats and complete
                simulated checkout.
              </div>
            )}
            <p className="note">
              Bookings persist in PostgreSQL. Cancellation is not supported in
              this demo.
            </p>
          </section>
        )}
        {page === "Load Test" && labPanel()}
        {page === "Operations" && (
          <div className="ops-grid">
            <section className="panel">
              <h2>{editId ? "Edit event" : "Create an event"}</h2>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  act(async () => {
                    await api(
                      "/ops/events" + (editId ? "/" + editId : ""),
                      editId ? "PUT" : "POST",
                      form,
                    );
                    await loadEvents();
                    setEditId("");
                    setForm({
                      title: "",
                      description: "",
                      rows: 8,
                      cols: 12,
                      price: 4500,
                    });
                  });
                }}
              >
                <label>
                  Title
                  <input
                    required
                    value={form.title}
                    onChange={(e) =>
                      setForm({ ...form, title: e.target.value })
                    }
                  />
                </label>
                <label>
                  Description
                  <textarea
                    required
                    value={form.description}
                    onChange={(e) =>
                      setForm({ ...form, description: e.target.value })
                    }
                  />
                </label>
                <div className="lab-controls">
                  {(["rows", "cols", "price"] as const).map((k) => (
                    <label key={k}>
                      {k === "price" ? "Price (cents)" : k}
                      <input
                        type="number"
                        min={k === "price" ? 0 : 1}
                        max={k === "rows" ? 26 : k === "cols" ? 30 : 100000}
                        required
                        value={form[k]}
                        onChange={(e) =>
                          setForm({ ...form, [k]: +e.target.value })
                        }
                      />
                    </label>
                  ))}
                </div>
                <p className="note">
                  Layout changes are blocked after any hold history. New events
                  start in 30 days.
                </p>
                <button className="primary" disabled={busy}>
                  Save event
                </button>
                {editId && (
                  <button type="button" onClick={() => setEditId("")}>
                    Cancel edit
                  </button>
                )}
              </form>
            </section>
            <section className="panel">
              <h2>Inventory operations</h2>
              {events.map((e) => (
                <div className="operation" key={e.id}>
                  <strong>{e.title}</strong>
                  <div>
                    <button
                      onClick={() => {
                        setEditId(e.id);
                        setForm({
                          title: e.title,
                          description: e.description,
                          rows: e.rows,
                          cols: e.cols,
                          price: e.price,
                        });
                      }}
                    >
                      Edit
                    </button>
                    <button
                      onClick={() =>
                        act(async () =>
                          setInspection(
                            await api("/ops/reservations?event=" + e.id),
                          ),
                        )
                      }
                    >
                      Inspect
                    </button>
                  </div>
                </div>
              ))}
              {inspection && (
                <div>
                  <h3>Reservation status (latest 100)</h3>
                  <pre>{JSON.stringify(inspection, null, 2)}</pre>
                </div>
              )}
            </section>
          </div>
        )}
        {page === "Logs" && (
          <section className="panel">
            <div className="section-head">
              <h2>
                <Terminal size={18} /> Audit activity
              </h2>
              <button onClick={() => act(() => loadLogs())}>
                <RefreshCw size={14} />
                Refresh
              </button>
            </div>
            <div className="lab-controls">
              <label>
                Event
                <select
                  value={eventFilter}
                  onChange={(e) => setEventFilter(e.target.value)}
                >
                  <option value="">All events</option>
                  {events.map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.title}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Operation
                <input
                  placeholder="e.g. HOLD"
                  value={opFilter}
                  onChange={(e) => setOpFilter(e.target.value)}
                />
              </label>
              <label>
                Outcome
                <input
                  placeholder="e.g. SUCCESS"
                  value={outcomeFilter}
                  onChange={(e) => setOutcomeFilter(e.target.value)}
                />
              </label>
              <button onClick={() => act(() => loadLogs())}>
                Apply filters
              </button>
            </div>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Time</th>
                    <th>Operation</th>
                    <th>Outcome</th>
                    <th>Correlation</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((l) => (
                    <tr key={l.id}>
                      <td>{date(l.at)}</td>
                      <td>{l.operation}</td>
                      <td>{l.outcome}</td>
                      <td>
                        <code>{l.correlation}</code>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!logs.length && (
              <div className="empty">No activity matches these filters.</div>
            )}
            {logs.length >= 50 && (
              <button
                onClick={() => act(() => loadLogs(logs[logs.length - 1].id))}
              >
                Load older activity
              </button>
            )}
          </section>
        )}
        <footer>
          <span>
            <Layers size={14} />
            SeatLock / Reservation Engine
          </span>
          <span>Fictional events · Real transaction guarantees</span>
          <span>POSTGRESQL + SPRING BOOT</span>
        </footer>
      </main>
    </>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
