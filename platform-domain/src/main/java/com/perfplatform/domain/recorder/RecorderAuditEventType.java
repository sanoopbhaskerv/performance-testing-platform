package com.perfplatform.domain.recorder;

import com.perfplatform.domain.spec.support.SpecEnums;

/** Security-relevant events in a browser recording session. */
public enum RecorderAuditEventType {

    /** A user requested a new governed recording session. */
    SESSION_REQUESTED,

    /** The recorder browser or bridge became available. */
    SESSION_STARTED,

    /** A browser destination was refused by the route guard or network floor. */
    DESTINATION_BLOCKED,

    /** Captured request/response material was reduced before storage or draft generation. */
    CAPTURE_SANITIZED,

    /** The recording produced a draft specification or source artifact. */
    DRAFT_CREATED,

    /** A user or policy stopped the session. */
    SESSION_CANCELLED,

    /** The session expired without an explicit cancellation. */
    SESSION_EXPIRED;

    public static RecorderAuditEventType from(String raw) {
        return SpecEnums.parse(RecorderAuditEventType.class, raw, "recorder audit event type");
    }
}
