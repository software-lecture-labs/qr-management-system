package com.restofy.qr.application.port;

import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Closed Order boundary. A real adapter must verify service identity and bind the complete Order/Payment scope.
 * All I/O is invoked outside QR transactions. No browser claim or local absence is source authority.
 */
@Component
public class OrderSessionAccess {
    public OrderSessionState inspect(UUID restaurantId, UUID sessionId) {
        return OrderSessionState.unknown(restaurantId, sessionId);
    }

    /** Order must serialize this durable operation with checkout before issuing a fenced permit. */
    public Authority prepare(Command command) { throw new SessionClosureUnavailableException(); }

    /** Read the SAME durable operation after an ambiguous response; do not invent a new command. */
    public Authority status(Command command) { throw new SessionClosureUnavailableException(); }

    /** A COMPLETED receipt may include scope drift; local closure is never rolled back for that drift. */
    public Confirmation acknowledge(Command command, LocalResult result) { throw new SessionClosureUnavailableException(); }

    /** Only a definitive, durable abort permits QR to leave its closing state. Timeout is not abort. */
    public Confirmation abort(Command command) { throw new SessionClosureUnavailableException(); }
}
