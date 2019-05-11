package nrodlight.ws;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.KeyStore.LoadStoreParameter;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.xml.bind.DatatypeConverter;
import nrodlight.NRODLight;
import nrodlight.RateMonitor;
import nrodlight.db.DBHandler;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

public class EASMWebSocket extends WebSocketServer {
   private static final AtomicBoolean serverClosed = new AtomicBoolean(false);
   private static JSONObject delayData = null;

   public EASMWebSocket() {
      super(new InetSocketAddress(NRODLight.config.optInt("WSPort", 8443)));
      super.setReuseAddr(true);
      SSLContext sslContext = this.getSSLContextFromLetsEncrypt();
      if (sslContext != null) {
         List ciphers = Arrays.asList("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_DHE_RSA_WITH_AES_128_CBC_SHA", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA", "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256");
         SSLEngine engine = sslContext.createSSLEngine();
         List ciphersAvailable = Arrays.asList(engine.getEnabledCipherSuites());
         Stream var10000 = ciphers.stream();
         ciphersAvailable.getClass();
         ciphers = (List)var10000.filter(ciphersAvailable::contains).collect(Collectors.toList());
         this.setWebSocketFactory(new EASMWebSocketFactory(sslContext, engine.getEnabledProtocols(), (String[])ciphers.toArray(new String[0])));
      } else {
         printWebSocket("Unable to create SSL Context", true);
      }

   }

   private SSLContext getSSLContextFromLetsEncrypt() {
      File certDir = new File(NRODLight.EASM_STORAGE_DIR, "certs");

      try {
         SSLContext context = SSLContext.getInstance("TLS");
         byte[] keyBytes = parseDERFromPEM(Files.readAllBytes((new File(certDir, "privkey.pem")).toPath()), "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");
         FileInputStream fis = new FileInputStream(new File(certDir, "cert.pem"));
         Throwable var6 = null;

         Certificate cert;
         try {
            cert = CertificateFactory.getInstance("X.509").generateCertificate(fis);
         } catch (Throwable var16) {
            var6 = var16;
            throw var16;
         } finally {
            if (fis != null) {
               if (var6 != null) {
                  try {
                     fis.close();
                  } catch (Throwable var15) {
                     var6.addSuppressed(var15);
                  }
               } else {
                  fis.close();
               }
            }

         }

         KeyFactory keyFactory = KeyFactory.getInstance("RSA");
         PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
         KeyStore keystore = KeyStore.getInstance("JKS");
         keystore.load((LoadStoreParameter)null);
         keystore.setCertificateEntry("cert-alias", cert);
         keystore.setKeyEntry("key-alias", key, new char[0], new Certificate[]{cert});
         KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
         kmf.init(keystore, new char[0]);
         context.init(kmf.getKeyManagers(), (TrustManager[])null, (SecureRandom)null);
         return context;
      } catch (KeyManagementException | KeyStoreException | InvalidKeySpecException | UnrecoverableKeyException | NoSuchAlgorithmException | CertificateException | IOException var18) {
         NRODLight.printThrowable(var18, "WebSocket");
         return null;
      }
   }

   private static byte[] parseDERFromPEM(byte[] pem, String beginDelimiter, String endDelimiter) {
      String data = new String(pem);
      String[] tokens = data.split(beginDelimiter);
      tokens = tokens[1].split(endDelimiter);
      return DatatypeConverter.parseBase64Binary(tokens[0]);
   }

   public void onOpen(WebSocket conn, ClientHandshake handshake) {
      if (conn instanceof EASMWebSocketImpl) {
         EASMWebSocketImpl easmconn = (EASMWebSocketImpl)conn;
         easmconn.setIP(handshake.hasFieldValue("X-Forwarded-For") ? handshake.getFieldValue("X-Forwarded-For") : (handshake.hasFieldValue("CF-Connecting-IP") ? handshake.getFieldValue("CF-Connecting-IP") : conn.getRemoteSocketAddress().getAddress().getHostAddress()));
         printWebSocket(String.format("Open connection to %s from %s%s", easmconn.getIP(), handshake.hasFieldValue("Origin") ? handshake.getFieldValue("Origin") : "Unknown", handshake.hasFieldValue("User-Agent") ? " (" + handshake.getFieldValue("User-Agent") + ")" : ""), false);
         JSONObject message = new JSONObject();
         JSONObject content = new JSONObject();
         content.put("type", "HELLO");
         content.put("timestamp", System.currentTimeMillis());
         content.put("areas", new JSONArray());
         content.put("options", new JSONArray());
         message.put("Message", content);
         easmconn.send(message.toString());
         RateMonitor.getInstance().onWSOpen();
      }

   }

   public void onClose(WebSocket conn, int code, String reason, boolean remote) {
      printWebSocket(String.format("Close connection %s %s (%s%s)", remote ? "from" : "to", conn instanceof EASMWebSocketImpl ? ((EASMWebSocketImpl)conn).getIP() : conn.getRemoteSocketAddress().getAddress().getHostAddress(), code, reason != null && !reason.isEmpty() ? "/" + reason : ""), false);
   }

   public void onMessage(WebSocket conn, String message) {
      if (conn instanceof EASMWebSocketImpl) {
         ((EASMWebSocketImpl)conn).receive(message);
      } else {
         printWebSocket("Message (" + conn.getRemoteSocketAddress().getAddress().getHostAddress() + "):\n  " + message, false);
      }

   }

   public void onError(WebSocket conn, Exception ex) {
      String ip = null;
      if (conn != null) {
         try {
            ip = conn instanceof EASMWebSocketImpl ? ((EASMWebSocketImpl)conn).getIP() : conn.getRemoteSocketAddress().getAddress().getHostAddress();
         } catch (Exception var5) {
         }

         printWebSocket("Error (" + (ip != null ? ip : "Unknown") + "):", true);
         conn.close(1000, ex.getMessage());
      }

      NRODLight.printThrowable(ex, "WebSocket" + (conn != null ? "-" + (ip != null ? ip : "Unknown") : ""));
   }

   public void onStart() {
      printWebSocket("Started", false);
   }

   public Collection getConnections() {
      return (Collection)super.getConnections().stream().filter((c) -> {
         return c != null && c.isOpen();
      }).collect(Collectors.toList());
   }

   public void stop(int timeout) throws InterruptedException {
      serverClosed.set(true);
      super.stop(timeout);
   }

   public boolean isClosed() {
      return serverClosed.get();
   }

   public static void printWebSocket(String message, boolean toErr) {
      if (toErr) {
         NRODLight.printErr("[WebSocket] " + message);
      } else {
         NRODLight.printOut("[WebSocket] " + message);
      }

   }

   public static JSONObject updateDelayData() throws SQLException {
      long start = System.nanoTime();
      Connection conn = DBHandler.getConnection();
      PreparedStatement psDelayData = conn.prepareStatement("SELECT a.train_id,a.train_id_current,a.schedule_uid,a.start_timestamp,a.current_delay,a.next_expected_update,a.off_route,a.finished,GROUP_CONCAT(DISTINCT s.td ORDER BY s.td SEPARATOR ',') AS tds FROM activations a INNER JOIN schedule_locations l ON a.schedule_uid=l.schedule_uid AND a.stp_indicator=l.stp_indicator AND a.schedule_date_from=l.date_from AND a.schedule_source=l.schedule_source INNER JOIN corpus c ON l.tiploc=c.tiploc INNER JOIN smart s ON c.stanox=s.stanox WHERE (a.last_update > (CAST(UNIX_TIMESTAMP(CURTIME(3)) AS INT) - 43200) * 1000) AND (a.finished=0 OR a.last_update > (CAST(UNIX_TIMESTAMP(CURTIME(3)) AS INT) - 2700) * 1000) AND a.cancelled=0 GROUP BY a.train_id");
      ResultSet r = psDelayData.executeQuery();
      String[] columns = new String[]{"train_id", "train_id_current", "schedule_uid", "start_timestamp", "current_delay", "next_expected_update", "off_route", "finished", "tds"};
      JSONArray resultData = new JSONArray();

      JSONObject jobj;
      while(r.next()) {
         jobj = new JSONObject();

         for(int i = 0; i < columns.length; ++i) {
            if ("tds".equals(columns[i])) {
               jobj.put(columns[i], new JSONArray(r.getString(i + 1).split(",")));
            } else {
               jobj.put(columns[i], r.getObject(i + 1));
            }
         }

         resultData.put(jobj);
      }

      r.close();
      psDelayData.close();
      jobj = new JSONObject();
      jobj.put("type", "DELAYS");
      jobj.put("timestamp", -1);
      jobj.put("timestamp_data", Long.toString(System.currentTimeMillis()));
      jobj.put("message", resultData);
      NRODLight.printOut("[Delays] Updated in " + (double)(System.nanoTime() - start) / 1000000.0D + "ms");
      delayData = jobj;
      return jobj;
   }

   public static JSONObject getDelayData() {
      return delayData;
   }
}
