package tf.dodoapps.parkbot.core;

public final class Session {
    public String id = "", number = "", message = "", token = "", detail = "Ready when you are.";
    public String status = "IDLE";
    public int simId = -1, parts, sentMask, sentCount;
    public long startAt, stopAt, intervalMs, nextAt, submittedAt, lastSentAt;
    public boolean active() { return status.equals("SCHEDULED") || status.equals("WAITING"); }
}
