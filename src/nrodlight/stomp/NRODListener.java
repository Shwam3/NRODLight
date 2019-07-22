package nrodlight.stomp;

import net.ser1.stomp.Listener;

public interface NRODListener extends Listener
{
    long getTimeout();
    long getTimeoutThreshold();
}
