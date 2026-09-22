package tf.dodoapps.parkbot.core;

import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class EngineTest {
    static int passed;
    static void test(String name, Runnable check) { try { check.run(); passed++; System.out.println("PASS " + name); } catch (Throwable t) { throw new AssertionError(name, t); } }
    static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("Expected " + a + ", got " + b); }
    static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Expected rejection"); }
    static final long BASE = Instant.parse("2026-09-21T14:00:00Z").toEpochMilli();
    static final long MIN = 60_000L;
    static class Memory implements Engine.Store {
        Session session = new Session(); List<String> log = new ArrayList<>();
        public Session load() { return copy(session); }
        public void save(Session s) { session = copy(s); }
        public void event(String s) { log.add(s); }
        static Session copy(Session s) {
            Session v = new Session();
            v.id=s.id; v.number=s.number; v.message=s.message; v.token=s.token; v.detail=s.detail; v.status=s.status;
            v.simId=s.simId; v.parts=s.parts; v.sentMask=s.sentMask; v.sentCount=s.sentCount;
            v.startAt=s.startAt; v.stopAt=s.stopAt; v.intervalMs=s.intervalMs; v.nextAt=s.nextAt; v.submittedAt=s.submittedAt; v.lastSentAt=s.lastSentAt;
            return v;
        }
    }
    static class Fixture implements Engine.Platform {
        long now = BASE; Long alarm; int sends, parts = 1; boolean stop, failArm, failSend; String problem;
        Runnable beforeSend = () -> {};
        Memory db = new Memory(); Engine engine = create();
        Engine create() { return new Engine(db, this, () -> now, () -> stop); }
        public String blockingReason(int sim) { return problem; }
        public int parts(String text) { return parts; }
        public void arm(Long at) { if (failArm && at != null) throw new IllegalStateException("alarm denied"); alarm = at; }
        public void send(Session s, BooleanSupplier allowed) { beforeSend.run(); if (!allowed.getAsBoolean()) throw new IllegalStateException("guard"); eq("WAITING", db.session.status); sends++; if (failSend) throw new IllegalStateException("modem"); }
        void start() { engine.start("12345", "PARK", 1, 18, BASE, BASE + 120*MIN); }
        void sent() { engine.sent(db.session.token, 0, true, "sent"); }
    }
    public static void main(String[] args) {
        test("immediate start persists claim before SMS", () -> { Fixture f=new Fixture(); f.start(); eq(1,f.sends); eq("WAITING",f.db.session.status); });
        test("future start does not send early", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,18,BASE+10*MIN,BASE+90*MIN); eq(0,f.sends); eq(BASE+10*MIN,f.alarm); });
        test("late alarm sends once rather than skipping", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,18,BASE+MIN,BASE+120*MIN); f.now=BASE+9*MIN; f.engine.tick(); eq(1,f.sends); });
        test("interval begins at successful callback", () -> { Fixture f=new Fixture(); f.start(); f.now+=3*MIN; f.sent(); eq(BASE+21*MIN,f.alarm); });
        test("next delayed renewal gets a full fresh interval", () -> { Fixture f=new Fixture(); f.start(); f.sent(); f.now=BASE+25*MIN; f.engine.tick(); f.now+=MIN; f.sent(); eq(BASE+44*MIN,f.alarm); eq(2,f.sends); });
        test("duplicate alarm does not resend pending SMS", () -> { Fixture f=new Fixture(); f.start(); f.engine.tick(); f.engine.tick(); eq(1,f.sends); });
        test("duplicate callback cannot shift next send", () -> { Fixture f=new Fixture(); f.start(); String token=f.db.session.token; f.sent(); f.now+=MIN; f.engine.sent(token,0,true,""); eq(BASE+18*MIN,f.alarm); });
        test("stale callback ignored", () -> { Fixture f=new Fixture(); f.start(); f.engine.sent("old",0,true,""); eq("WAITING",f.db.session.status); });
        test("multipart waits for every segment", () -> { Fixture f=new Fixture(); f.parts=2; f.start(); f.sent(); eq("WAITING",f.db.session.status); f.now+=MIN; f.engine.sent(f.db.session.token,1,true,""); eq(BASE+19*MIN,f.alarm); });
        test("duplicate multipart callback counts once", () -> { Fixture f=new Fixture(); f.parts=2; f.start(); f.sent(); f.sent(); eq("WAITING",f.db.session.status); });
        test("invalid segment ignored", () -> { Fixture f=new Fixture(); f.start(); f.engine.sent(f.db.session.token,5,true,""); eq("WAITING",f.db.session.status); });
        test("send failure pauses without retry", () -> { Fixture f=new Fixture(); f.start(); f.engine.sent(f.db.session.token,0,false,"No service"); f.now+=20*MIN; f.engine.tick(); eq(1,f.sends); eq("FAILED",f.db.session.status); eq(null,f.alarm); });
        test("callback timeout stops uncertain session", () -> { Fixture f=new Fixture(); f.start(); f.now+=Engine.CALLBACK_TIMEOUT_MS; f.engine.tick(); eq("FAILED",f.db.session.status); eq(1,f.sends); });
        test("restored pending send is not resent", () -> { Fixture f=new Fixture(); f.start(); f.engine=f.create(); f.engine.tick(); eq(1,f.sends); });
        test("restored pending callback continues safely", () -> { Fixture f=new Fixture(); f.start(); f.engine=f.create(); f.sent(); eq(BASE+18*MIN,f.alarm); });
        test("cutoff never submits", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,18,BASE+MIN,BASE+5*MIN); f.now=BASE+5*MIN; f.engine.tick(); eq(0,f.sends); eq("FINISHED",f.db.session.status); });
        test("cutoff checked immediately before API", () -> { Fixture f=new Fixture(); f.beforeSend=()->f.now=BASE+120*MIN; f.start(); eq(0,f.sends); });
        test("stop flag checked immediately before API", () -> { Fixture f=new Fixture(); f.beforeSend=()->f.stop=true; f.start(); eq(0,f.sends); });
        test("permission rechecked before API", () -> { Fixture f=new Fixture(); f.beforeSend=()->f.problem="revoked"; f.start(); eq(0,f.sends); });
        test("stop ignores late callback", () -> { Fixture f=new Fixture(); f.start(); f.engine.stop("Stopped"); f.sent(); eq("STOPPED",f.db.session.status); eq(null,f.alarm); });
        test("last renewal does not schedule beyond stop", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,18,BASE,BASE+18*MIN); f.sent(); eq("FINISHED",f.db.session.status); eq(null,f.alarm); });
        test("alarm failure leaves inactive state", () -> { Fixture f=new Fixture(); f.failArm=true; rejects(f::start); eq(false,f.db.session.active()); eq(0,f.sends); });
        test("transport exception does not retry", () -> { Fixture f=new Fixture(); f.failSend=true; f.start(); f.engine.tick(); eq("FAILED",f.db.session.status); eq(1,f.sends); });
        test("clock change pauses session", () -> { Fixture f=new Fixture(); f.start(); f.engine.clockChanged(); eq("STOPPED",f.db.session.status); });
        test("cannot start two active sessions", () -> { Fixture f=new Fixture(); f.start(); rejects(f::start); eq(1,f.sends); });
        test("restart preserves cooldown", () -> { Fixture f=new Fixture(); f.start(); f.sent(); f.engine.stop("stop"); f.now+=MIN; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(BASE+18*MIN,f.alarm); eq(1,f.sends); });
        test("cooldown survives repeated stop and restart", () -> { Fixture f=new Fixture(); f.start(); f.sent(); f.engine.stop("stop"); f.now+=MIN; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); f.engine.stop("stop"); f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(BASE+18*MIN,f.alarm); eq(1,f.sends); });
        test("zero minute interval is rejected", () -> { Fixture f=new Fixture(); rejects(()->f.engine.start("12345","P",1,0,BASE,BASE+120*MIN)); });
        test("one minute interval sends again only after a full minute", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.sent(); eq(BASE+MIN,f.alarm); f.now=BASE+MIN-1; f.engine.tick(); eq(1,f.sends); f.now=BASE+MIN; f.engine.tick(); eq(2,f.sends); });
        test("empty text rejected", () -> { Fixture f=new Fixture(); rejects(()->f.engine.start("12345"," ",1,18,BASE,BASE+120*MIN)); });
        test("short codes and formatted contacts accepted", () -> { eq("123",Engine.number("123")); eq("+4312345678",Engine.number("+43 (123) 45-678")); rejects(()->Engine.number("*123#")); rejects(()->Engine.number("123abc")); });
        test("overnight window", () -> { long[] w=Engine.window(ZonedDateTime.parse("2026-09-21T21:00:00Z"),LocalTime.of(22,0),LocalTime.of(2,0),false); eq(Instant.parse("2026-09-22T02:00:00Z").toEpochMilli(),w[1]); });
        test("past start schedules tomorrow explicitly", () -> { long[] w=Engine.window(ZonedDateTime.parse("2026-09-21T21:00:00Z"),LocalTime.of(14,0),LocalTime.of(22,0),false); eq(Instant.parse("2026-09-22T14:00:00Z").toEpochMilli(),w[0]); });
        test("start now rounds to the next full minute", () -> { ZonedDateTime n=ZonedDateTime.parse("2026-09-21T14:00:31Z"); long[] w=Engine.window(n,LocalTime.NOON,LocalTime.of(22,0),true); eq(BASE+MIN,w[0]); });
        test("exact minute is not moved unnecessarily", () -> { eq(BASE,Engine.wholeMinuteAtOrAfter(BASE)); eq(BASE+MIN,Engine.wholeMinuteAtOrAfter(BASE+1)); eq(BASE+MIN,Engine.wholeMinuteAtOrAfter(BASE+MIN-1)); });
        test("start now midway through minute waits", () -> { Fixture f=new Fixture(); f.now=BASE+31_000; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(0,f.sends); eq(BASE+MIN,f.alarm); f.now=BASE+MIN; f.engine.tick(); eq(1,f.sends); });
        test("callback seconds are ignored", () -> { Fixture f=new Fixture(); f.start(); f.now=BASE+5_000; f.sent(); eq(BASE+18*MIN,f.alarm); });
        test("callback milliseconds are ignored", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+100; f.sent(); eq(BASE+MIN,f.alarm); });
        test("whole minute renewal at cutoff finishes", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+MIN); f.now=BASE+1; f.sent(); eq("FINISHED",f.db.session.status); eq(null,f.alarm); });
        test("start rounding never extends the reviewed stop date", () -> { ZonedDateTime n=ZonedDateTime.parse("2026-09-21T14:00:31Z"); long[] w=Engine.window(n,LocalTime.NOON,LocalTime.of(14,1),true); eq(BASE+MIN,w[0]); eq(BASE+MIN,w[1]); Fixture f=new Fixture(); f.now=BASE+31_000; rejects(()->f.engine.start("12345","P",1,1,w[0],w[1])); eq(0,f.sends); });
        test("restart ignores fractional callback seconds", () -> { Fixture f=new Fixture(); f.start(); f.now=BASE+15_000; f.sent(); f.engine.stop("stop"); f.now=BASE+MIN; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(BASE+18*MIN,f.alarm); });
        test("restored older schedule with seconds is rounded", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE+MIN,BASE+10*MIN); f.db.session.nextAt=BASE+MIN+12_000; f.now=BASE+MIN+12_000; f.engine.tick(); eq(0,f.sends); eq(BASE+2*MIN,f.alarm); });
        test("restored rounded send cannot pass stop", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE+MIN,BASE+2*MIN); f.db.session.nextAt=BASE+MIN+12_000; f.now=BASE+MIN; f.engine.tick(); eq("FINISHED",f.db.session.status); eq(0,f.sends); });
        test("every second within a sent minute produces the same next minute", () -> {
            for (int minutes : new int[]{1,3,18}) for (int second=0; second<60; second++) {
                Fixture f=new Fixture(); f.engine.start("12345","P",1,minutes,BASE,BASE+120*MIN);
                f.now=BASE+second*1000L+999; f.sent(); eq(BASE+minutes*MIN,f.alarm);
            }
        });
        test("14:00:01 with one minute sends at 14:01:00", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+1000; f.sent(); eq(BASE+MIN,f.alarm); f.now=BASE+MIN-1; f.engine.tick(); eq(1,f.sends); f.now=BASE+MIN; f.engine.tick(); eq(2,f.sends); });
        test("14:00:59 with three minutes schedules 14:03:00", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,3,BASE,BASE+10*MIN); f.now=BASE+59_000; f.sent(); eq(BASE+3*MIN,f.alarm); });
        test("older rounded-up renewal is recalculated on recovery", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+59_000; f.sent(); f.db.session.nextAt=BASE+2*MIN; f.engine=f.create(); f.engine.tick(); eq(BASE+MIN,f.alarm); eq(1,f.sends); });
        test("delayed callback uses its minute without adding extra minute", () -> { Fixture f=new Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+2*MIN+59_000; f.sent(); eq(BASE+3*MIN,f.alarm); });
        System.out.println(passed + " scheduler checks passed.");
    }
}



