package nrodlight.ws;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import nrodlight.stomp.handlers.TDHandler;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketListener;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EASMWebSocketImpl extends WebSocketImpl {
   private List areas = Collections.emptyList();
   private boolean delayColouration = false;
   private boolean splitFullMessages = false;
   private String IP = null;

   public EASMWebSocketImpl(WebSocketListener listener, Draft draft) {
      super(listener, draft);
   }

   public EASMWebSocketImpl(WebSocketListener listener, List drafts) {
      super(listener, drafts);
   }

   public void setIP(String IP) {
      if (this.IP == null) {
         this.IP = IP;
      }

   }

   public String getIP() {
      return this.IP;
   }

   public boolean optSplitFullMessages() {
      return this.splitFullMessages;
   }

   public boolean optDelayColouration() {
      return this.delayColouration;
   }

   public void send(Map messages) throws WebsocketNotConnectedException {
      if (!this.areas.isEmpty()) {
         Iterator var2 = messages.entrySet().iterator();

         while(true) {
            Entry e;
            do {
               if (!var2.hasNext()) {
                  return;
               }

               e = (Entry)var2.next();
            } while(!"XX".equals(e.getKey()) && !this.areas.contains(e.getKey()));

            this.send((String)e.getValue());
         }
      }
   }

   public void sendSplit(Map splitMessages) {
      if (!this.areas.contains("XX") && splitMessages.containsKey("XX")) {
         this.send((String)splitMessages.get("XX"));
      }

      Stream var10000 = this.areas.stream();
      splitMessages.getClass();
      var10000 = var10000.filter(splitMessages::containsKey);
      splitMessages.getClass();
      var10000.map(splitMessages::get).forEach(this::send);
   }

   public void sendAll() throws WebsocketNotConnectedException {
      if (!this.areas.isEmpty()) {
         JSONObject message = new JSONObject();
         JSONObject content = new JSONObject();
         content.put("type", "SEND_ALL");
         content.put("timestamp", System.currentTimeMillis());
         message.put("Message", content);
         if (!this.splitFullMessages) {
            content.put("message", TDHandler.DATA_MAP);
            this.send(message.toString());
         } else {
            Map splitMessages = new HashMap();
            TDHandler.DATA_MAP.forEach((k, v) -> {
               String area = k.substring(0, 2);
               if (this.areas.contains(area) || "XX".equals(area)) {
                  JSONObject obj = (JSONObject)splitMessages.get(area);
                  if (obj == null) {
                     obj = new JSONObject();
                     splitMessages.put(area, obj);
                  }

                  obj.put(k, v);
               }
            });
            splitMessages.forEach((k, v) -> {
               content.put("td_area", k);
               content.put("message", v);
               this.send(message.toString());
            });
         }
      }

   }

   public void receive(String message) {
      try {
         JSONObject msg = new JSONObject(message);
         msg = msg.getJSONObject("Message");
         String type = msg.getString("type");
         byte var5 = -1;
         switch(type.hashCode()) {
         case 323171113:
            if (type.equals("SET_AREAS")) {
               var5 = 0;
            }
            break;
         case 826775425:
            if (type.equals("SET_OPTIONS")) {
               var5 = 1;
            }
         }

         switch(var5) {
         case 0:
         case 1:
            JSONArray areasNew = msg.optJSONArray("areas");
            if (areasNew != null && areasNew.length() != 0) {
               List l = new ArrayList(areasNew.length());
               Iterator var8 = areasNew.iterator();

               while(var8.hasNext()) {
                  Object i = var8.next();
                  l.add(String.valueOf(i));
               }

               this.areas = l;
            } else {
               this.areas = Collections.emptyList();
            }

            boolean delayColourationNew = false;
            boolean splitFullMessagesNew = false;
            JSONArray options = msg.optJSONArray("options");
            if (options != null && options.length() > 0) {
               Iterator var10 = options.iterator();

               while(var10.hasNext()) {
                  Object i = var10.next();
                  String iStr = String.valueOf(i);
                  if ("delay_colouration".equals(iStr)) {
                     delayColourationNew = true;
                  } else if ("split_full_messages".equals(iStr)) {
                     splitFullMessagesNew = true;
                  }
               }
            }

            this.delayColouration = delayColourationNew;
            this.splitFullMessages = splitFullMessagesNew;
            this.sendAll();
            if (delayColourationNew && EASMWebSocket.getDelayData() != null) {
               this.sendDelayData(EASMWebSocket.getDelayData());
            }
         }
      } catch (JSONException var13) {
         EASMWebSocket.printWebSocket("Unrecognised message \"" + message + "\"", true);
      }

   }

   public String toString() {
      return EASMWebSocketImpl.class.getName() + ":" + this.getRemoteSocketAddress().toString() + (new JSONArray(this.areas)).toString();
   }

   public void sendDelayData(JSONObject delayData) {
      if (this.optDelayColouration()) {
         JSONArray delaysDataSend = new JSONArray();
         StreamSupport.stream(delayData.getJSONArray("message").spliterator(), false).filter((t) -> {
            return StreamSupport.stream(((JSONObject)t).getJSONArray("tds").spliterator(), false).anyMatch((o) -> {
               return this.areas.contains(o);
            });
         }).forEach(delaysDataSend::put);
         JSONObject content = new JSONObject();
         content.put("type", "DELAYS");
         content.put("timestamp", System.currentTimeMillis());
         content.put("timestamp_data", delayData.getLong("timestamp_data"));
         content.put("message", delaysDataSend);
         JSONObject message = new JSONObject();
         message.put("Message", content);
         this.send(message.toString());
      }
   }
}
