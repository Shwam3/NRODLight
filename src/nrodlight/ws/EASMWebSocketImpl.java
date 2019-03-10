package nrodlight.ws;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nrodlight.stomp.handlers.TDHandler;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketListener;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EASMWebSocketImpl extends WebSocketImpl
{
    private List<String> areas = Collections.emptyList();
    private boolean delayColouration = false;
    private boolean splitFullMessages = false;
    private String IP = null;

    public EASMWebSocketImpl(WebSocketListener listener, Draft draft)
    {
        super(listener, draft);
    }

    public EASMWebSocketImpl(WebSocketListener listener, List<Draft> drafts)
    {
        super(listener, drafts);
    }

    public void setIP(String IP)
    {
        if (this.IP == null)
            this.IP = IP;
    }

    public String getIP()
    {
        return IP;
    }

    public boolean optSplitFullMessages()
    {
        return splitFullMessages;
    }

    public boolean optDelayColouration()
    {
        return delayColouration;
    }

    public void send(Map<String, String> messages) throws WebsocketNotConnectedException
    {
        if (!areas.isEmpty())
        {
            for (Map.Entry<String, String> e : messages.entrySet())
            {
                if ("XX".equals(e.getKey()) || areas.contains(e.getKey()))
                {
                    send(e.getValue());
                }
            }

            if (delayColouration)
            {
                JSONObject message = new JSONObject();
                JSONObject content = new JSONObject();
                content.put("type", "SEND_UPDATE");
                content.put("timestamp", System.currentTimeMillis());
                content.put("delay_colours", new JSONArray());
                message.put("Message", content);

                send(message.toString());
            }
        }
    }

    public void sendSplit(Map<String, String> splitMessages)
    {
        if (!areas.contains("XX") && splitMessages.containsKey("XX"))
            send(splitMessages.get("XX"));

        areas.stream()
            .filter(splitMessages::containsKey)
            .map(splitMessages::get)
            .forEach(this::send);
    }

    public void sendAll() throws WebsocketNotConnectedException
    {
        if (!areas.isEmpty())
        {
            JSONObject message = new JSONObject();
            JSONObject content = new JSONObject();
            content.put("type", "SEND_ALL");
            content.put("timestamp", System.currentTimeMillis());
            message.put("Message", content);
            if (!splitFullMessages)
            {
                content.put("message", TDHandler.DATA_MAP);
                if (this.delayColouration)
                    content.put("delay_colours", new JSONArray());

                send(message.toString());
            }
            else
            {
                Map<String, JSONObject> splitMessages = new HashMap<>();
                TDHandler.DATA_MAP.forEach((k,v) ->
                {
                    String area = k.substring(0, 2);
                    if (!areas.contains(area) && !"XX".equals(area))
                        return;

                    JSONObject obj = splitMessages.get(area);
                    if (obj == null)
                    {
                        obj = new JSONObject();
                        splitMessages.put(area, obj);
                    }
                    obj.put(k, v);
                });
                splitMessages.forEach((k,v) ->
                {
                    content.put("td_area", k);
                    content.put("message", v);
                    send(message.toString());
                });
            }
        }
    }

    public void receive(String message)
    {
        try
        {
            JSONObject msg = new JSONObject(message);
            msg = msg.getJSONObject("Message");

            String type = msg.getString("type");
            switch(type)
            {
                case "SET_AREAS":
                case "SET_OPTIONS":
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

                    boolean delayColourationNew = false;
                    boolean splitFullMessagesNew = false;

                    JSONArray options = msg.optJSONArray("options");
                    if (options != null && options.length() > 0)
                    {
                        for (Object i : options)
                        {
                            String iStr = String.valueOf(i);
                            if ("delay_colouration".equals(iStr))
                                delayColourationNew = true;
                            else if ("split_full_messages".equals(iStr))
                                splitFullMessagesNew = true;
                        }
                    }

                    // Disabled for now
                    this.delayColouration = false; //delayColourationNew;
                    this.splitFullMessages = splitFullMessagesNew;

                    sendAll();
                    break;
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
