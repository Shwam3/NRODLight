package nrodlight;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class SigmapsThreadFactory implements ThreadFactory
{
    private final ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String threadPrefix;

    public SigmapsThreadFactory(String threadPrefix) {
        this.threadPrefix = threadPrefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = defaultThreadFactory.newThread(runnable);
        thread.setName(threadPrefix + "-" + threadNumber.getAndIncrement() + "-" + thread.getId());
        return thread;
    }
}
