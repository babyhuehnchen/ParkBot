package tf.dodoapps.parkbot.core;

import java.time.*;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Serialized by the Android adapter. Every submission is persisted BEFORE calling the modem. */
public final class Engine {
    public static final long CALLBACK_TIMEOUT_MS = 5 * 60_000L;
    public interface Store { Session load(); void save(Session s); void event(String text); }
    public interface Platform {
        String blockingReason(int simId);
        int parts(String message);
        void send(Session s, BooleanSupplier allowed);
        void arm(Long at);
    }
    private final Store store;
    private final Platform platform;
    private final LongSupplier clock;
    private final BooleanSupplier stopped;
    public Engine(Store store, Platform platform, LongSupplier clock, BooleanSupplier stopped) {
        this.store = store; this.platform = platform; this.clock = clock; this.stopped = stopped;
    }
    public static String number(String raw) {
        String n = raw.trim().replaceAll("[\\s().-]", "");
        if (!n.matches("\\+?[0-9]{3,15}")) throw new IllegalArgumentException("Enter a phone number or parking short code (3–15 digits).");
        return n;
    }
    /** Initial starts use the next available minute, never a time in the past. */
    public static long wholeMinuteAtOrAfter(long time) {
        long remainder = Math.floorMod(time, 60_000L);
        return remainder == 0 ? time : Math.addExact(time, 60_000L - remainder);
    }
    /** Renewal timing ignores seconds and milliseconds in the successful send time. */
    public static long renewalAt(long sentAt, long intervalMs) {
        return Math.addExact(sentAt - Math.floorMod(sentAt, 60_000L), intervalMs);
    }
    /** Start means the next occurrence of that local time; stop may be the following day. */
    public static long[] window(ZonedDateTime now, LocalTime start, LocalTime stop, boolean immediately) {
        ZonedDateTime from = immediately ? now : now.toLocalDate().atTime(start).atZone(now.getZone());
        if (!immediately && from.isBefore(now)) from = from.plusDays(1);
        ZonedDateTime until = from.toLocalDate().atTime(stop).atZone(now.getZone());
        if (!until.isAfter(from)) until = until.plusDays(1);
        return new long[] { wholeMinuteAtOrAfter(from.toInstant().toEpochMilli()), until.toInstant().toEpochMilli() };
    }
    public void start(String number, String message, int simId, int minutes, long startAt, long stopAt) {
        Session old = store.load();
        if (old.active()) throw new IllegalStateException("Stop the current session before starting another.");
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Choose 1–1440 minutes.");
        if (message.trim().isEmpty() || message.length() > 2000) throw new IllegalArgumentException("Enter the SMS text (up to 2,000 characters).");
        long now = clock.getAsLong();
        if (stopAt <= Math.max(startAt, now)) throw new IllegalArgumentException("Stop time must be after the first SMS.");
        Session s = new Session();
        s.id = UUID.randomUUID().toString(); s.number = number(number); s.message = message;
        s.simId = simId; s.intervalMs = minutes * 60_000L; s.startAt = wholeMinuteAtOrAfter(Math.max(startAt, now)); s.stopAt = stopAt;
        // Use the same minute-based calculation when restarting for the same number.
        long previous = s.number.equals(old.number) ? Math.max(old.lastSentAt, old.submittedAt) : 0;
        s.lastSentAt = s.number.equals(old.number) ? old.lastSentAt : 0;
        s.submittedAt = s.number.equals(old.number) ? old.submittedAt : 0;
        s.nextAt = wholeMinuteAtOrAfter(Math.max(s.startAt, previous == 0 ? s.startAt : renewalAt(previous, Math.max(s.intervalMs, old.intervalMs))));
        if (s.nextAt >= stopAt) throw new IllegalArgumentException("The first eligible full minute is at or after your stop time. Choose a later stop time.");
        s.parts = platform.parts(message);
        if (s.parts < 1 || s.parts > 10) throw new IllegalArgumentException("The message must fit in 1–10 SMS segments.");
        String problem = platform.blockingReason(simId);
        if (problem != null) throw new IllegalStateException(problem);
        s.status = "SCHEDULED"; s.detail = "First SMS scheduled.";
        store.save(s); store.event("Parking started. First SMS scheduled.");
        try { arm(s); } catch (RuntimeException error) { fail(s, "Could not schedule: " + error.getMessage()); throw error; }
        tick();
    }
    public void stop(String reason) {
        Session s = store.load(); s.status = "STOPPED"; s.detail = reason;
        store.save(s); store.event(reason); platform.arm(null);
    }
    public void clockChanged() { if (store.load().active()) stop("Phone clock changed. Check your ticket before restarting."); }
    public void tick() {
        Session s = store.load();
        if (!s.active()) { platform.arm(null); return; }
        long now = clock.getAsLong();
        if (stopped.getAsBoolean()) { stop("Parking stopped."); return; }
        if (now >= s.stopAt) {
            s.status = "FINISHED"; s.detail = "Stop time reached. No more SMS will be sent.";
            store.save(s); store.event(s.detail); platform.arm(null); return;
        }
        String problem = platform.blockingReason(s.simId);
        if (problem != null) { fail(s, problem); return; }
        if (s.status.equals("WAITING")) {
            if (now >= s.submittedAt + CALLBACK_TIMEOUT_MS) {
                fail(s, "The phone did not confirm sending. Check your ticket before restarting; no automatic retry.");
            } else arm(s);
            return;
        }
        // Recalculate saved renewals from older versions; never alter a pending SMS.
        long aligned = s.sentCount > 0 && s.lastSentAt > 0 ? renewalAt(s.lastSentAt, s.intervalMs) : wholeMinuteAtOrAfter(s.nextAt);
        if (aligned != s.nextAt) {
            s.nextAt = aligned;
            if (s.nextAt >= s.stopAt) {
                s.status = "FINISHED"; s.detail = "No full-minute send remains before the stop time.";
            }
            store.save(s);
            if (!s.active()) { arm(s); return; }
        }
        if (now < s.nextAt) { arm(s); return; }
        s.token = UUID.randomUUID().toString(); s.sentMask = 0; s.submittedAt = now;
        s.status = "WAITING"; s.detail = "Waiting for the phone to confirm sending.";
        store.save(s);
        store.event("Submitting SMS" + (now - s.nextAt >= 60_000 ? " (delayed " + ((now - s.nextAt) / 60_000) + " min)" : "") + ".");
        try {
            arm(s);
            platform.send(s, () -> !stopped.getAsBoolean() && clock.getAsLong() < s.stopAt && platform.blockingReason(s.simId) == null);
        } catch (RuntimeException error) {
            fail(s, "Sending stopped: " + error.getMessage() + ". Check your ticket; no automatic retry.");
        }
    }
    public void sent(String token, int part, boolean success, String detail) {
        Session s = store.load();
        if (!s.active() || !s.status.equals("WAITING") || !s.token.equals(token) || part < 0 || part >= s.parts) return;
        if (!success) { fail(s, detail + " Check your ticket before restarting."); return; }
        s.sentMask |= 1 << part;
        if (s.sentMask == (1 << s.parts) - 1) {
            long now = clock.getAsLong(); s.lastSentAt = now; s.sentCount++;
            s.nextAt = renewalAt(now, s.intervalMs);
            s.status = s.nextAt < s.stopAt ? "SCHEDULED" : "FINISHED";
            s.detail = s.status.equals("FINISHED") ? "Last SMS sent. No more renewals before the stop time." : "SMS sent. Next renewal is calculated from the sent minute, ignoring seconds.";
            store.event("SMS sent successfully. " + (s.active() ? "Next renewal: sent minute + " + s.intervalMs / 60_000 + " minutes (seconds ignored)." : "Session complete."));
        }
        store.save(s); arm(s);
    }
    private void arm(Session s) {
        Long at = !s.active() ? null : Math.min(s.stopAt, s.status.equals("WAITING") ? s.submittedAt + CALLBACK_TIMEOUT_MS : s.nextAt);
        platform.arm(at);
    }
    private void fail(Session s, String reason) {
        s.status = "FAILED"; s.detail = reason; store.save(s); store.event(reason); platform.arm(null);
    }
}




