package nrodlight.ws;

import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.HandshakeBuilder;
import org.java_websocket.handshake.ServerHandshakeBuilder;

public class EASMDraft_6455 extends Draft_6455
{
    public EASMDraft_6455()
    {
        super();
    }

    @Override
    public Draft copyInstance()
    {
        return new EASMDraft_6455();
    }

    @Override
    public HandshakeBuilder postProcessHandshakeResponseAsServer(ClientHandshake request, ServerHandshakeBuilder response) throws InvalidHandshakeException
    {
        HandshakeBuilder hb = super.postProcessHandshakeResponseAsServer(request, response);

        hb.put("Server", "SignalMaps Java-WebSocket");
        hb.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");

        return hb;
    }
}
