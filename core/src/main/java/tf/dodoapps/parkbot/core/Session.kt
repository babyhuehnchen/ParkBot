package tf.dodoapps.parkbot.core

data class Session(
    var id: String = "",
    var number: String = "",
    var message: String = "",
    var token: String = "",
    var detail: String = "Ready when you are.",
    var status: String = "IDLE",
    var simId: Int = -1,
    var parts: Int = 0,
    var sentMask: Int = 0,
    var sentCount: Int = 0,
    var startAt: Long = 0,
    var stopAt: Long = 0,
    var intervalMs: Long = 0,
    var nextAt: Long = 0,
    var submittedAt: Long = 0,
    var lastSentAt: Long = 0,
) {
    fun active(): Boolean = status == "SCHEDULED" || status == "WAITING"
}