package android.os;

import java.util.ArrayDeque;

public class Handler {
    public static final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable runnable, long delay) {
        pending.add(runnable);
        return true;
    }
    public static void drain() {
        int limit = 20;
        while (!pending.isEmpty()) {
            if (--limit == 0) throw new AssertionError("Unbounded recovery");
            pending.remove().run();
        }
    }
}
