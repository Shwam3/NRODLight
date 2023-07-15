package nrodlight.ws;

import nrodlight.NRODLight;
import nrodlight.stomp.handlers.TDHandler;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketListener;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class EASMWebSocketImpl extends WebSocketImpl
{
    private List<String> areas = Collections.emptyList();
    private final AtomicLong messageID = new AtomicLong(-1);
    private final Map<String, Boolean> clientOptions;
    private String IP = null;

    public EASMWebSocketImpl(WebSocketListener listener, Draft draft)
    {
        super(listener, draft);

        clientOptions = new HashMap<>();
        clientOptions.put("delayColouration", false);
        clientOptions.put("splitFullMessages", true);
        clientOptions.put("messageIDs", false);
    }

    public EASMWebSocketImpl(WebSocketListener listener, List<Draft> drafts)
    {
        super(listener, drafts == null || drafts.isEmpty() ? Collections.singletonList(new EASMDraft_6455()) : drafts);

        clientOptions = new HashMap<>();
        clientOptions.put("delayColouration", false);
        clientOptions.put("splitFullMessages", true);
        clientOptions.put("messageIDs", false);
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

    public boolean areasNotEmpty()
    {
        return !areas.isEmpty();
    }

    public boolean optSplitFullMessages()
    {
        return clientOptions.get("splitFullMessages");
    }

    public boolean optDelayColouration()
    {
        return clientOptions.get("delayColouration");
    }

    public boolean optMessageIDs()
    {
        return clientOptions.get("messageIDs");
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

    public void sendAll(boolean clientRequested) throws WebsocketNotConnectedException
    {
        if (!areas.isEmpty() && (!optMessageIDs() || clientRequested))
        {
            JSONObject message = new JSONObject();
            JSONObject content = new JSONObject();
            content.put("type", "SEND_ALL");
            content.put("messageID", "%nextid%");
            content.put("timestamp", System.currentTimeMillis());
            message.put("Message", content);
            if (!optSplitFullMessages())
            {
                content.put("message", TDHandler.DATA_MAP);

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

                    JSONObject obj = splitMessages.getOrDefault(area, new JSONObject());
                    splitMessages.putIfAbsent(area, obj);
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

    @Override
    public void send(String text)
    {
        if (optMessageIDs() && text.contains("\"%nextid%\""))
            text = text.replace("\"%nextid%\"", Long.toString(messageID.incrementAndGet()));
        else
            text = text.replace("\"%nextid%\"", "-1");

        super.send(text);
    }

    public void receive(String message)
    {
        try
        {
            JSONObject msg = new JSONObject(message);
            msg = msg.getJSONObject("Message");

            String type = msg.getString("type");
            if ("SET_AREAS".equals(type) || "SET_OPTIONS".equals(type))
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

                boolean delayColourationNew = false;
                boolean splitFullMessagesNew = false;
                boolean messageIDsNew = false;

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
                        else if ("message_ids".equals(iStr))
                            messageIDsNew = true;
                    }
                }

                clientOptions.put("delayColouration", delayColourationNew);
                clientOptions.put("splitFullMessages", splitFullMessagesNew);
                clientOptions.put("messageIDs", messageIDsNew);

                try {
                    sendAll(true);
                    if (delayColourationNew)
                        sendDelayData(EASMWebSocket. getDelayData());
                } catch (JSONException e) {
                    NRODLight.printThrowable(e, "WebSocket");
                }
            }
        }
        catch(WebsocketNotConnectedException e)
        {
            EASMWebSocket.printWebSocket("Client sent message while not connected (" + getReadyState() +
                    "): \"" + message + "\"", true);
        }
        catch(JSONException e)
        {
            EASMWebSocket.printWebSocket(String.format("Unrecognised message \"%s\": %s (%s)", message, e.getMessage(), e.getStackTrace()[0]), true);
        }
    }

    @Override
    public String toString()
    {
        return EASMWebSocketImpl.class.getName() + ":" + getRemoteSocketAddress() + new JSONArray(areas);
    }

    public void sendDelayData(JSONObject delayData)
    {
        if (!optDelayColouration() || !isOpen() || delayData == null)
            return;

        long start = System.nanoTime();
        final JSONArray delaysDataSend = new JSONArray();
        for (final Object x : delayData.getJSONArray("message")) {
            if (x instanceof JSONObject) {
                final JSONObject o = (JSONObject) x;
                final List<Object> tds = o.getJSONArray("tds").toList();

                for (String area : areas) {
                    if (tds.contains(area)) {
                        delaysDataSend.put(o);
                        break;
                    }
                }
            }
        }

        JSONObject content = new JSONObject();
        content.put("type", "DELAYS");
        content.put("messageID", "%nextid%");
        content.put("timestamp", System.currentTimeMillis());
        content.put("timestamp_data", delayData.getLong("timestamp_data"));
        content.put("message", delaysDataSend);

        JSONObject message = new JSONObject();
        message.put("Message", content);

        if (NRODLight.verbose)
            EASMWebSocket.printWebSocket(String.format("Constructing delay packet took %.2fms", (System.nanoTime() - start) / 1000000d), false);

        try
        {
            if (isOpen())
                send(message.toString());
        } catch (WebsocketNotConnectedException ignored) {}
    }
}
