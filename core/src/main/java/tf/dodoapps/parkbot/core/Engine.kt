package tf.dodoapps.parkbot.core

import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/** Serialized by the Android adapter. Every submission is persisted BEFORE calling the modem. */
class Engine(
    private val store: Store,
    private val platform: Platform,
    private val clock: () -> Long,
    private val stopped: () -> Boolean,
) {
    interface Store {
        fun load(): Session
        fun save(s: Session)
        fun event(text: String)
    }

    interface Platform {
        fun blockingReason(simId: Int): String?
        fun parts(message: String): Int
        fun send(s: Session, allowed: () -> Boolean)
        fun arm(at: Long?)
        fun ended(s: Session)
    }

    companion object {
        const val CALLBACK_TIMEOUT_MS = 5 * 60_000L

        fun number(raw: String): String {
            val n = raw.trim().replace(Regex("[\\s().-]"), "")
            require(n.matches(Regex("\\+?[0-9]{3,15}"))) {
                "Enter a phone number or parking short code (3–15 digits)."
            }
            return n
        }

        /** Initial starts use the next available minute, never a time in the past. */
        fun wholeMinuteAtOrAfter(time: Long): Long {
            val remainder = Math.floorMod(time, 60_000L)
            return if (remainder == 0L) time else Math.addExact(time, 60_000L - remainder)
        }

        /** Renewal timing ignores seconds and milliseconds in the successful send time. */
        fun renewalAt(sentAt: Long, intervalMs: Long): Long =
            Math.addExact(sentAt - Math.floorMod(sentAt, 60_000L), intervalMs)

        /** Start means the next occurrence of that local time; stop may be the following day. */
        fun window(now: ZonedDateTime, start: LocalTime, stop: LocalTime, immediately: Boolean): LongArray {
            var from = if (immediately) now else now.toLocalDate().atTime(start).atZone(now.zone)
            if (!immediately && from.isBefore(now)) from = from.plusDays(1)
            var until = from.toLocalDate().atTime(stop).atZone(now.zone)
            if (!until.isAfter(from)) until = until.plusDays(1)
            return longArrayOf(wholeMinuteAtOrAfter(from.toInstant().toEpochMilli()), until.toInstant().toEpochMilli())
        }
    }

    fun start(number: String, message: String, simId: Int, minutes: Int, startAt: Long, stopAt: Long) {
        val old = store.load()
        check(!old.active()) { "Stop the current session before starting another." }
        require(minutes in 1..1440) { "Choose 1–1440 minutes." }
        require(message.trim().isNotEmpty() && message.length <= 2000) {
            "Enter the SMS text (up to 2,000 characters)."
        }
        val now = clock()
        require(stopAt > maxOf(startAt, now)) { "Stop time must be after the first SMS." }
        val s = Session(
            id = UUID.randomUUID().toString(),
            number = number(number),
            message = message,
            simId = simId,
            intervalMs = minutes * 60_000L,
            startAt = wholeMinuteAtOrAfter(maxOf(startAt, now)),
            stopAt = stopAt,
        )
        // Use the same minute-based calculation when restarting for the same number.
        val previous = if (s.number == old.number) maxOf(old.lastSentAt, old.submittedAt) else 0L
        s.lastSentAt = if (s.number == old.number) old.lastSentAt else 0L
        s.submittedAt = if (s.number == old.number) old.submittedAt else 0L
        s.nextAt = wholeMinuteAtOrAfter(maxOf(s.startAt,
            if (previous == 0L) s.startAt else renewalAt(previous, maxOf(s.intervalMs, old.intervalMs))))
        require(s.nextAt < stopAt) {
            "The first eligible full minute is at or after your stop time. Choose a later stop time."
        }
        s.parts = platform.parts(message)
        require(s.parts in 1..10) { "The message must fit in 1–10 SMS segments." }
        platform.blockingReason(simId)?.let { throw IllegalStateException(it) }
        s.status = "SCHEDULED"
        s.detail = "First SMS scheduled."
        save(s)
        store.event("Parking started. First SMS scheduled.")
        try {
            arm(s)
        } catch (error: RuntimeException) {
            fail(s, "Could not schedule: " + error.message)
            throw error
        }
        tick()
    }

    fun stop(reason: String) {
        val s = store.load()
        if (!s.active()) {
            platform.arm(null)
            return
        }
        s.status = "STOPPED"
        s.detail = reason
        save(s)
        store.event(reason)
        platform.arm(null)
    }

    fun clockChanged() {
        if (store.load().active()) stop("Phone clock changed. Check your ticket before restarting.")
    }

    fun tick() {
        val s = store.load()
        if (!s.active()) {
            platform.arm(null)
            return
        }
        val now = clock()
        if (stopped()) {
            stop("Parking stopped.")
            return
        }
        if (now >= s.stopAt) {
            s.status = "FINISHED"
            s.detail = "Stop time reached. No more SMS will be sent."
            save(s)
            store.event(s.detail)
            platform.arm(null)
            return
        }
        platform.blockingReason(s.simId)?.let {
            fail(s, it)
            return
        }
        if (s.status == "WAITING") {
            if (now >= s.submittedAt + CALLBACK_TIMEOUT_MS) {
                fail(s, "The phone did not confirm sending. Check your ticket before restarting; no automatic retry.")
            } else {
                arm(s)
            }
            return
        }
        // Recalculate saved renewals from older versions; never alter a pending SMS.
        val aligned = if (s.sentCount > 0 && s.lastSentAt > 0) renewalAt(s.lastSentAt, s.intervalMs)
            else wholeMinuteAtOrAfter(s.nextAt)
        if (aligned != s.nextAt) {
            s.nextAt = aligned
            if (s.nextAt >= s.stopAt) {
                s.status = "FINISHED"
                s.detail = "No full-minute send remains before the stop time."
            }
            save(s)
            if (!s.active()) {
                arm(s)
                return
            }
        }
        if (now < s.nextAt) {
            arm(s)
            return
        }
        s.token = UUID.randomUUID().toString()
        s.sentMask = 0
        s.submittedAt = now
        s.status = "WAITING"
        s.detail = "Waiting for the phone to confirm sending."
        save(s)
        store.event("Submitting SMS" +
            (if (now - s.nextAt >= 60_000) " (delayed " + (now - s.nextAt) / 60_000 + " min)" else "") + ".")
        try {
            arm(s)
            platform.send(s) { !stopped() && clock() < s.stopAt && platform.blockingReason(s.simId) == null }
        } catch (error: RuntimeException) {
            fail(s, "Sending stopped: " + error.message + ". Check your ticket; no automatic retry.")
        }
    }

    fun sent(token: String?, part: Int, success: Boolean, detail: String) {
        val s = store.load()
        if (!s.active() || s.status != "WAITING" || s.token != token || part !in 0 until s.parts) return
        if (!success) {
            fail(s, detail + " Check your ticket before restarting.")
            return
        }
        s.sentMask = s.sentMask or (1 shl part)
        if (s.sentMask == (1 shl s.parts) - 1) {
            val now = clock()
            s.lastSentAt = now
            s.sentCount++
            s.nextAt = renewalAt(now, s.intervalMs)
            s.status = if (s.nextAt < s.stopAt) "SCHEDULED" else "FINISHED"
            s.detail = if (s.status == "FINISHED") "Last SMS sent. No more renewals before the stop time."
                else "SMS sent. Next renewal is calculated from the sent minute, ignoring seconds."
            store.event("SMS sent successfully. " + if (s.active())
                "Next renewal: sent minute + " + s.intervalMs / 60_000 + " minutes (seconds ignored)."
                else "Session complete.")
        }
        save(s)
        arm(s)
    }

    private fun save(s: Session) {
        val wasActive = store.load().active()
        // Persist the transition first so reopening or rebooting cannot announce it again.
        store.save(s)
        if (wasActive && !s.active()) platform.ended(s)
    }

    private fun arm(s: Session) {
        val at = if (!s.active()) null else minOf(s.stopAt,
            if (s.status == "WAITING") s.submittedAt + CALLBACK_TIMEOUT_MS else s.nextAt)
        platform.arm(at)
    }

    private fun fail(s: Session, reason: String) {
        s.status = "FAILED"
        s.detail = reason
        save(s)
        store.event(reason)
        platform.arm(null)
    }
}