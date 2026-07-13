package com.home.user.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

@Component
public class OAuthSessionInvalidator {
    private final SessionRepository<?> sessions;

    public OAuthSessionInvalidator(SessionRepository<?> sessions) {
        this.sessions = sessions;
    }

    public void invalidate(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return;
        String requestedSessionId = request.getRequestedSessionId();
        String sessionId = session.getId();
        session.invalidate();
        sessions.deleteById(sessionId);
        if (requestedSessionId != null && !requestedSessionId.equals(sessionId)) {
            sessions.deleteById(requestedSessionId);
        }
    }
}
