package tf.dodoapps.parkbot.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

object EngineTest {
    private var passed = 0
    private fun test(name: String, check: () -> Unit) {
        try {
            check()
            passed++
            println("PASS " + name)
        } catch (t: Throwable) {
            throw AssertionError(name, t)
        }
    }
    private fun eq(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("Expected " + expected + ", got " + actual)
    }
    private fun rejects(action: () -> Unit) {
        try { action() }
        catch (expected: IllegalArgumentException) { return }
        catch (expected: IllegalStateException) { return }
        throw AssertionError("Expected rejection")
    }
    private val BASE = Instant.parse("2026-09-21T14:00:00Z").toEpochMilli()
    private const val MIN = 60_000L

    private class Memory : Engine.Store {
        var session = Session()
        val log = mutableListOf<String>()
        override fun load() = session.copy()
        override fun save(s: Session) { session = s.copy() }
        override fun event(text: String) { log.add(text) }
    }

    private class Fixture : Engine.Platform {
        var now = BASE
        var alarm: Long? = null
        var sends = 0
        var parts = 1
        var stop = false
        var failArm = false
        var failSend = false
        var problem: String? = null
        var beforeSend: () -> Unit = {}
        val ended = mutableListOf<String>()
        val db = Memory()
        var engine = create()
        fun create() = Engine(db, this, { now }, { stop })
        override fun blockingReason(simId: Int) = problem
        override fun parts(message: String) = parts
        override fun arm(at: Long?) {
            if (failArm && at != null) throw IllegalStateException("alarm denied")
            alarm = at
        }
        override fun send(s: Session, allowed: () -> Boolean) {
            beforeSend()
            check(allowed()) { "guard" }
            eq("WAITING", db.session.status)
            sends++
            if (failSend) throw IllegalStateException("modem")
        }
        override fun ended(s: Session) {
            eq(s.status, db.session.status)
            eq(false, db.session.active())
            ended.add(s.status)
        }
        fun start() = engine.start("12345", "PARK", 1, 18, BASE, BASE + 120*MIN)
        fun sent() = engine.sent(db.session.token, 0, true, "sent")
    }

    @JvmStatic
    fun main(args: Array<String>) {

        test("immediate start persists claim before SMS", { val f=Fixture(); f.start(); eq(1,f.sends); eq("WAITING",f.db.session.status); });
        test("future start does not send early", { val f=Fixture(); f.engine.start("12345","P",1,18,BASE+10*MIN,BASE+90*MIN); eq(0,f.sends); eq(BASE+10*MIN,f.alarm); });
        test("late alarm sends once rather than skipping", { val f=Fixture(); f.engine.start("12345","P",1,18,BASE+MIN,BASE+120*MIN); f.now=BASE+9*MIN; f.engine.tick(); eq(1,f.sends); });
        test("interval begins at successful callback", { val f=Fixture(); f.start(); f.now+=3*MIN; f.sent(); eq(BASE+21*MIN,f.alarm); });
        test("next delayed renewal gets a full fresh interval", { val f=Fixture(); f.start(); f.sent(); f.now=BASE+25*MIN; f.engine.tick(); f.now+=MIN; f.sent(); eq(BASE+44*MIN,f.alarm); eq(2,f.sends); });
        test("duplicate alarm does not resend pending SMS", { val f=Fixture(); f.start(); f.engine.tick(); f.engine.tick(); eq(1,f.sends); });
        test("duplicate callback cannot shift next send", { val f=Fixture(); f.start(); val token=f.db.session.token; f.sent(); f.now+=MIN; f.engine.sent(token,0,true,""); eq(BASE+18*MIN,f.alarm); });
        test("stale callback ignored", { val f=Fixture(); f.start(); f.engine.sent("old",0,true,""); eq("WAITING",f.db.session.status); });
        test("multipart waits for every segment", { val f=Fixture(); f.parts=2; f.start(); f.sent(); eq("WAITING",f.db.session.status); f.now+=MIN; f.engine.sent(f.db.session.token,1,true,""); eq(BASE+19*MIN,f.alarm); });
        test("duplicate multipart callback counts once", { val f=Fixture(); f.parts=2; f.start(); f.sent(); f.sent(); eq("WAITING",f.db.session.status); });
        test("invalid segment ignored", { val f=Fixture(); f.start(); f.engine.sent(f.db.session.token,5,true,""); eq("WAITING",f.db.session.status); });
        test("send failure pauses without retry", { val f=Fixture(); f.start(); f.engine.sent(f.db.session.token,0,false,"No service"); f.now+=20*MIN; f.engine.tick(); eq(1,f.sends); eq("FAILED",f.db.session.status); eq(null,f.alarm); });
        test("callback timeout stops uncertain session", { val f=Fixture(); f.start(); f.now+=Engine.CALLBACK_TIMEOUT_MS; f.engine.tick(); eq("FAILED",f.db.session.status); eq(1,f.sends); });
        test("restored pending send is not resent", { val f=Fixture(); f.start(); f.engine=f.create(); f.engine.tick(); eq(1,f.sends); });
        test("restored pending callback continues safely", { val f=Fixture(); f.start(); f.engine=f.create(); f.sent(); eq(BASE+18*MIN,f.alarm); });
        test("cutoff never submits", { val f=Fixture(); f.engine.start("12345","P",1,18,BASE+MIN,BASE+5*MIN); f.now=BASE+5*MIN; f.engine.tick(); eq(0,f.sends); eq("FINISHED",f.db.session.status); });
        test("cutoff checked immediately before API", { val f=Fixture(); f.beforeSend={ f.now=BASE+120*MIN }; f.start(); eq(0,f.sends); });
        test("stop flag checked immediately before API", { val f=Fixture(); f.beforeSend={ f.stop=true }; f.start(); eq(0,f.sends); });
        test("permission rechecked before API", { val f=Fixture(); f.beforeSend={ f.problem="revoked" }; f.start(); eq(0,f.sends); });
        test("stop ignores late callback", { val f=Fixture(); f.start(); f.engine.stop("Stopped"); f.sent(); eq("STOPPED",f.db.session.status); eq(null,f.alarm); });
        test("last renewal does not schedule beyond stop", { val f=Fixture(); f.engine.start("12345","P",1,18,BASE,BASE+18*MIN); f.sent(); eq("FINISHED",f.db.session.status); eq(null,f.alarm); });
        test("alarm failure leaves inactive state", { val f=Fixture(); f.failArm=true; rejects(f::start); eq(false,f.db.session.active()); eq(0,f.sends); });
        test("transport exception does not retry", { val f=Fixture(); f.failSend=true; f.start(); f.engine.tick(); eq("FAILED",f.db.session.status); eq(1,f.sends); });
        test("clock change pauses session", { val f=Fixture(); f.start(); f.engine.clockChanged(); eq("STOPPED",f.db.session.status); });
        test("cannot start two active sessions", { val f=Fixture(); f.start(); rejects(f::start); eq(1,f.sends); });
        test("restart preserves cooldown", { val f=Fixture(); f.start(); f.sent(); f.engine.stop("stop"); f.now+=MIN; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(BASE+18*MIN,f.alarm); eq(1,f.sends); });
        test("cooldown survives repeated stop and restart", { val f=Fixture(); f.start(); f.sent(); f.engine.stop("stop"); f.now+=MIN; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); f.engine.stop("stop"); f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(BASE+18*MIN,f.alarm); eq(1,f.sends); });
        test("zero minute interval is rejected", { val f=Fixture(); rejects({ f.engine.start("12345","P",1,0,BASE,BASE+120*MIN) }); });
        test("one minute interval sends again only after a full minute", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.sent(); eq(BASE+MIN,f.alarm); f.now=BASE+MIN-1; f.engine.tick(); eq(1,f.sends); f.now=BASE+MIN; f.engine.tick(); eq(2,f.sends); });
        test("empty text rejected", { val f=Fixture(); rejects({ f.engine.start("12345"," ",1,18,BASE,BASE+120*MIN) }); });
        test("short codes and formatted contacts accepted", { eq("123",Engine.number("123")); eq("+4312345678",Engine.number("+43 (123) 45-678")); rejects({ Engine.number("*123#") }); rejects({ Engine.number("123abc") }); });
        test("overnight window", { val w=Engine.window(ZonedDateTime.parse("2026-09-21T21:00:00Z"),LocalTime.of(22,0),LocalTime.of(2,0),false); eq(Instant.parse("2026-09-22T02:00:00Z").toEpochMilli(),w[1]); });
        test("past start schedules tomorrow explicitly", { val w=Engine.window(ZonedDateTime.parse("2026-09-21T21:00:00Z"),LocalTime.of(14,0),LocalTime.of(22,0),false); eq(Instant.parse("2026-09-22T14:00:00Z").toEpochMilli(),w[0]); });
        test("start now rounds to the next full minute", { val n=ZonedDateTime.parse("2026-09-21T14:00:31Z"); val w=Engine.window(n,LocalTime.NOON,LocalTime.of(22,0),true); eq(BASE+MIN,w[0]); });
        test("exact minute is not moved unnecessarily", { eq(BASE,Engine.wholeMinuteAtOrAfter(BASE)); eq(BASE+MIN,Engine.wholeMinuteAtOrAfter(BASE+1)); eq(BASE+MIN,Engine.wholeMinuteAtOrAfter(BASE+MIN-1)); });
        test("start now midway through minute waits", { val f=Fixture(); f.now=BASE+31_000; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(0,f.sends); eq(BASE+MIN,f.alarm); f.now=BASE+MIN; f.engine.tick(); eq(1,f.sends); });
        test("callback seconds are ignored", { val f=Fixture(); f.start(); f.now=BASE+5_000; f.sent(); eq(BASE+18*MIN,f.alarm); });
        test("callback milliseconds are ignored", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+100; f.sent(); eq(BASE+MIN,f.alarm); });
        test("whole minute renewal at cutoff finishes", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+MIN); f.now=BASE+1; f.sent(); eq("FINISHED",f.db.session.status); eq(null,f.alarm); });
        test("start rounding never extends the reviewed stop date", { val n=ZonedDateTime.parse("2026-09-21T14:00:31Z"); val w=Engine.window(n,LocalTime.NOON,LocalTime.of(14,1),true); eq(BASE+MIN,w[0]); eq(BASE+MIN,w[1]); val f=Fixture(); f.now=BASE+31_000; rejects({ f.engine.start("12345","P",1,1,w[0],w[1]) }); eq(0,f.sends); });
        test("restart ignores fractional callback seconds", { val f=Fixture(); f.start(); f.now=BASE+15_000; f.sent(); f.engine.stop("stop"); f.now=BASE+MIN; f.engine.start("12345","P",1,18,f.now,BASE+120*MIN); eq(BASE+18*MIN,f.alarm); });
        test("restored older schedule with seconds is rounded", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE+MIN,BASE+10*MIN); f.db.session.nextAt=BASE+MIN+12_000; f.now=BASE+MIN+12_000; f.engine.tick(); eq(0,f.sends); eq(BASE+2*MIN,f.alarm); });
        test("restored rounded send cannot pass stop", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE+MIN,BASE+2*MIN); f.db.session.nextAt=BASE+MIN+12_000; f.now=BASE+MIN; f.engine.tick(); eq("FINISHED",f.db.session.status); eq(0,f.sends); });
        test("every second within a sent minute produces the same next minute", {
            for (minutes in intArrayOf(1,3,18)) for (second in 0 until 60) {
                val f=Fixture(); f.engine.start("12345","P",1,minutes,BASE,BASE+120*MIN);
                f.now=BASE+second*1000L+999; f.sent(); eq(BASE+minutes*MIN,f.alarm);
            }
        });
        test("14:00:01 with one minute sends at 14:01:00", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+1000; f.sent(); eq(BASE+MIN,f.alarm); f.now=BASE+MIN-1; f.engine.tick(); eq(1,f.sends); f.now=BASE+MIN; f.engine.tick(); eq(2,f.sends); });
        test("14:00:59 with three minutes schedules 14:03:00", { val f=Fixture(); f.engine.start("12345","P",1,3,BASE,BASE+10*MIN); f.now=BASE+59_000; f.sent(); eq(BASE+3*MIN,f.alarm); });
        test("older rounded-up renewal is recalculated on recovery", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+59_000; f.sent(); f.db.session.nextAt=BASE+2*MIN; f.engine=f.create(); f.engine.tick(); eq(BASE+MIN,f.alarm); eq(1,f.sends); });
        test("delayed callback uses its minute without adding extra minute", { val f=Fixture(); f.engine.start("12345","P",1,1,BASE,BASE+10*MIN); f.now=BASE+2*MIN+59_000; f.sent(); eq(BASE+3*MIN,f.alarm); });
        test("idle launch and recovery never announce an end", {
            val f=Fixture(); f.engine.tick(); f.engine=f.create(); f.engine.tick();
            f.engine.stop("stale stop"); eq(emptyList<String>(),f.ended); eq("IDLE",f.db.session.status);
        });
        test("manual stop announces once across reopen and reboot recovery", {
            val f=Fixture(); f.start(); f.engine.stop("Parking stopped.");
            eq(listOf("STOPPED"),f.ended); val events=f.db.log.size;
            f.engine.tick(); f.engine=f.create(); f.engine.tick(); f.engine.stop("duplicate stop");
            eq(listOf("STOPPED"),f.ended); eq(events,f.db.log.size); eq(null,f.alarm);
        });
        test("stop-time finish announces once across recovery", {
            val f=Fixture(); f.engine.start("12345","P",1,18,BASE+MIN,BASE+5*MIN);
            f.now=BASE+5*MIN; f.engine.tick(); eq(listOf("FINISHED"),f.ended);
            f.engine=f.create(); f.engine.tick(); f.engine.tick(); f.engine.stop("stale stop");
            eq(listOf("FINISHED"),f.ended); eq("FINISHED",f.db.session.status); eq(0,f.sends);
        });
        test("last successful SMS announces completion once", {
            val f=Fixture(); f.engine.start("12345","P",1,18,BASE,BASE+18*MIN);
            f.sent(); f.sent(); f.engine=f.create(); f.engine.tick();
            eq(listOf("FINISHED"),f.ended); eq(1,f.sends);
        });
        test("failed session is not announced again by recovery or stale stop", {
            val f=Fixture(); f.start(); f.engine.sent(f.db.session.token,0,false,"No service");
            f.engine=f.create(); f.engine.tick(); f.engine.stop("stale stop");
            eq(listOf("FAILED"),f.ended); eq("FAILED",f.db.session.status);
        });
        test("active recovery keeps scheduling without an end notification", {
            val f=Fixture(); f.engine.start("12345","P",1,18,BASE+10*MIN,BASE+90*MIN);
            f.engine=f.create(); f.engine.tick(); eq(BASE+10*MIN,f.alarm); eq(emptyList<String>(),f.ended);
        });
        test("each new parking session can announce its own stop", {
            val f=Fixture(); f.start(); f.engine.stop("stop");
            f.engine.start("12345","P",1,18,BASE+20*MIN,BASE+120*MIN); f.engine.stop("stop");
            eq(listOf("STOPPED","STOPPED"),f.ended);
        });
        test("clock-change stop announces once", {
            val f=Fixture(); f.start(); f.engine.clockChanged(); f.engine.clockChanged();
            f.engine=f.create(); f.engine.tick(); eq(listOf("STOPPED"),f.ended);
        });
                println(passed.toString() + " scheduler checks passed.")
    }
}
