package nrodlight.stomp;

import net.ser1.stomp.Listener;

public interface NRODListener extends Listener
{
    default long getTimeout() { return 0; };
    default long getTimeoutThreshold() { return 100000; }
}
