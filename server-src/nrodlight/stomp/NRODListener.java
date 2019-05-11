package nrodlight.stomp;

import net.ser1.stomp.Listener;

public interface NRODListener extends Listener {
   default long getTimeout() {
      return 0L;
   }

   default long getTimeoutThreshold() {
      return 100000L;
   }
}
