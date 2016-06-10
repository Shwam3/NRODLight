package nrodclient.stomp;

import net.ser1.stomp.Listener;

public interface NRODListener extends Listener
{
    default public long getTimeout() { return 0; };
    default public long getTimeoutThreshold() { return 100000; }
}
