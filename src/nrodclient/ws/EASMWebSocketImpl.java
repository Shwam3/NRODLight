package nrodclient.ws;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import nrodclient.stomp.handlers.TDHandler;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketListener;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EASMWebSocketImpl extends WebSocketImpl
{
    private List areas = Collections.emptyList();
    
    public EASMWebSocketImpl(WebSocketListener listener, Draft draft)
    {
        super(listener, draft);
    }

    public EASMWebSocketImpl(WebSocketListener listener, List<Draft> drafts)
    {
        super(listener, drafts);
    }

    public void send(Map<String, String> messages) throws WebsocketNotConnectedException
    {
        if (areas.isEmpty())
        {
            //EASMWebSocket.printWebSocket(String.format("Sending all %s to %s", messages.get(""), toString()), false);
            send(messages.get(""));
        }
        else
        {
            for (Map.Entry<String, String> e : messages.entrySet())
            {
                if ("XX".equals(e.getKey()) || areas.contains(e.getKey()))
                {
                    //EASMWebSocket.printWebSocket(String.format("Sending \"%s\" %s to %s", e.getKey(), e.getValue(), toString()), false);
                    send(e.getValue());
                }
            }
        }
    }
    
    public void sendAll() throws WebsocketNotConnectedException
    {
        JSONObject message = new JSONObject();
        JSONObject content = new JSONObject();
        content.put("type", "SEND_ALL");
        content.put("timestamp", System.currentTimeMillis());
        content.put("message", TDHandler.DATA_MAP);
        message.put("Message", content);

        send(message.toString());
    }

    public void receive(String message)
    {
        try
        {
            JSONObject msg = new JSONObject(message);  
            msg = msg.getJSONObject("Message");
            
            String type = msg.getString("type");
            if (type.equals("SET_AREAS"))
            {
                JSONArray areasNew = msg.optJSONArray("areas");
                if (areasNew == null || areasNew.length() == 0)
                    areas = Collections.emptyList();
                else
                {
                    List<String> l = new ArrayList<>(areasNew.length());
                    for (Object i : areasNew)
                        l.add(String.valueOf(i));
                    areas = l;
                }
                
                sendAll();
            }
        }
        catch(JSONException e)
        {
            EASMWebSocket.printWebSocket("Unrecognised message \"" + message + "\"", true);
        }
    }

    @Override
    public String toString()
    {
        return EASMWebSocketImpl.class.getName() + ":" + getRemoteSocketAddress().toString() + new JSONArray(areas).toString();
    }
}
