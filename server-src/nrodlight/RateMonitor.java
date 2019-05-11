package nrodlight;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RateMonitor {
   private final Map rateMap = new HashMap();
   private final List stompDelays = new ArrayList();
   private final List descrDelays = new ArrayList();
   private final AtomicInteger wsPeakConns = new AtomicInteger();
   private static PrintWriter logStream;
   private static File logFile;
   private static String lastLogDate = "";
   private final String[] topics = new String[]{"StompMessages", "StompDelay", "TDDelay", "TDMessages", "WSConnections", "TRUSTMessages", "VSTPMessages"};
   private static RateMonitor instance = null;

   private RateMonitor() {
      Date logDate = new Date();
      logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "RateMonitor" + File.separator + NRODLight.sdfDate.format(logDate).replace("/", "-") + ".csv");
      boolean fileExisted = logFile.exists();
      logFile.getParentFile().mkdirs();
      lastLogDate = NRODLight.sdfDate.format(logDate);

      try {
         logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
      } catch (IOException var7) {
         NRODLight.printThrowable(var7, "RateMonitor");
      }

      if (!fileExisted) {
         logStream.print("time,");

         for(int i = 0; i < this.topics.length; ++i) {
            logStream.print(this.topics[i] + (i >= this.topics.length - 1 ? "" : ","));
         }

         logStream.println();
      }

      String[] var8 = this.topics;
      int var4 = var8.length;

      for(int var5 = 0; var5 < var4; ++var5) {
         String topic = var8[var5];
         this.rateMap.put(topic, new AtomicInteger(0));
      }

      long currTim = System.currentTimeMillis();
      Calendar wait = Calendar.getInstance();
      wait.setTimeInMillis(currTim);
      wait.set(14, 0);
      wait.set(13, 0);
      wait.add(12, 1);
      Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
         try {
            Date logDateNew = new Date();
            String logDateNewStr = NRODLight.sdfDate.format(logDateNew);
            int i;
            if (!NRODLight.sdfDate.format(logDateNew).equals(lastLogDate)) {
               logStream.close();
               logFile = new File(NRODLight.EASM_STORAGE_DIR, "Logs" + File.separator + "RateMonitor" + File.separator + logDateNewStr.replace("/", "-") + ".csv");
               logFile.getParentFile().mkdirs();
               lastLogDate = logDateNewStr;

               try {
                  logStream = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
               } catch (IOException var8) {
                  NRODLight.printThrowable(var8, "RateMonitor");
               }

               logStream.print("time,");
               i = 0;

               while(true) {
                  if (i >= this.topics.length) {
                     logStream.println();
                     break;
                  }

                  logStream.print(this.topics[i] + (i >= this.topics.length - 1 ? "" : ","));
                  ++i;
               }
            }

            logStream.print(NRODLight.sdfTime.format(logDateNew) + ",");

            for(i = 0; i < this.topics.length; ++i) {
               String topic = this.topics[i];
               ArrayList delays;
               double avg;
               if ("StompDelay".equals(topic)) {
                  delays = new ArrayList(this.stompDelays);
                  this.stompDelays.clear();
                  avg = delays.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
                  logStream.print(String.format("%.3f", avg));
               } else if ("TDDelay".equals(topic)) {
                  delays = new ArrayList(this.descrDelays);
                  this.descrDelays.clear();
                  avg = delays.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
                  logStream.print(String.format("%.3f", avg));
               } else if ("WSConnections".equals(topic)) {
                  int countx = NRODLight.webSocket != null ? NRODLight.webSocket.getConnections().size() : 0;
                  logStream.print(this.wsPeakConns.getAndSet(countx));
               } else {
                  long count = (long)((AtomicInteger)this.rateMap.get(topic)).getAndSet(0);
                  logStream.print(count);
               }

               if (i < this.topics.length - 1) {
                  logStream.print(',');
               }
            }

            logStream.println();
         } catch (Exception var9) {
            NRODLight.printThrowable(var9, "RateMonitor");
         }

      }, wait.getTimeInMillis() - currTim, 60000L, TimeUnit.MILLISECONDS);
   }

   public static RateMonitor getInstance() {
      if (instance == null) {
         instance = new RateMonitor();
      }

      return instance;
   }

   public void onTDMessage(Double delayStomp, Double delayTD, int msgCount) {
      this.stompDelays.add(delayStomp);
      this.descrDelays.add(delayTD);
      ((AtomicInteger)this.rateMap.get("StompMessages")).incrementAndGet();
      ((AtomicInteger)this.rateMap.get("TDMessages")).addAndGet(msgCount);
   }

   public void onWSOpen() {
      this.wsPeakConns.incrementAndGet();
   }

   public void onVSTPMessage() {
      ((AtomicInteger)this.rateMap.get("VSTPMessages")).incrementAndGet();
   }

   public void onTRUSTMessage(int msgCount) {
      ((AtomicInteger)this.rateMap.get("TRUSTMessages")).addAndGet(msgCount);
   }
}
