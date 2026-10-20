package com.perfplatform.domain.port;

import com.perfplatform.domain.identity.RecorderSessionId;
import com.perfplatform.domain.recorder.RecorderAuditEvent;

import java.util.List;

/** Append-only security audit log for recorder sessions. */
public interface RecorderAuditLog {

    RecorderAuditEvent append(RecorderAuditEvent event);

    List<RecorderAuditEvent> events(RecorderSessionId sessionId, long afterSequence, int limit);
}
