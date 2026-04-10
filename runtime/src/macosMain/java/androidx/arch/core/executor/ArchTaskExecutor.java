package androidx.arch.core.executor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ArchTaskExecutor extends TaskExecutor {
    private static volatile ArchTaskExecutor instance;

    private final TaskExecutor defaultTaskExecutor = new DefaultTaskExecutor();
    private TaskExecutor delegate = defaultTaskExecutor;

    private static final Executor mainThreadExecutor =
            command -> getInstance().postToMainThread(command);

    private static final Executor ioThreadExecutor =
            command -> getInstance().executeOnDiskIO(command);

    private ArchTaskExecutor() {
    }

    public static ArchTaskExecutor getInstance() {
        if (instance != null) {
            return instance;
        }
        synchronized (ArchTaskExecutor.class) {
            if (instance == null) {
                instance = new ArchTaskExecutor();
            }
        }
        return instance;
    }

    public void setDelegate(TaskExecutor taskExecutor) {
        delegate = taskExecutor == null ? defaultTaskExecutor : taskExecutor;
    }

    public static Executor getMainThreadExecutor() {
        return mainThreadExecutor;
    }

    public static Executor getIOThreadExecutor() {
        return ioThreadExecutor;
    }

    @Override
    public void executeOnDiskIO(Runnable runnable) {
        delegate.executeOnDiskIO(runnable);
    }

    @Override
    public void postToMainThread(Runnable runnable) {
        delegate.postToMainThread(runnable);
    }

    @Override
    public boolean isMainThread() {
        return delegate.isMainThread();
    }

    private static final class DefaultTaskExecutor extends TaskExecutor {
        private final ExecutorService diskExecutor =
                Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "ComposeNativeHost-ArchDefaultDisk");
                    thread.setDaemon(true);
                    return thread;
                });
        private final ExecutorService mainExecutor =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ComposeNativeHost-ArchDefaultMain");
                    thread.setDaemon(true);
                    return thread;
                });
        private volatile Thread mainThread;

        @Override
        public void executeOnDiskIO(Runnable runnable) {
            diskExecutor.execute(runnable);
        }

        @Override
        public void postToMainThread(Runnable runnable) {
            if (isMainThread()) {
                runnable.run();
                return;
            }
            mainExecutor.execute(() -> {
                mainThread = Thread.currentThread();
                runnable.run();
            });
        }

        @Override
        public boolean isMainThread() {
            return Thread.currentThread() == mainThread;
        }
    }
}
