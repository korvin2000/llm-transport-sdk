package net.ai.gate.lifecycle;

import java.util.ArrayList;
import java.util.List;

/// Thread-safe, one-shot cancellation for every call that carries this token or one of its children — one Stop
/// button for a whole workflow run. Cancelling before a call starts means it is never sent.
public final class CancelToken {
    private static final System.Logger LOG = System.getLogger("net.ai.gate");

    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    private CancelToken() { }

    public static CancelToken create() { return new CancelToken(); }

    /// Cancelled with this token; cancelling the child leaves this token alone. The link is released when the child
    /// is cancelled — cancel a child you no longer need, or let the SDK do it: calls link their own tokens to yours
    /// for their lifetime only.
    public CancelToken child() {
        var child = new CancelToken();
        var link = onCancel(child::cancel);
        child.onCancel(link::close);
        return child;
    }

    /// Idempotent; runs the registered actions on the calling thread.
    public void cancel() {
        List<Runnable> run;
        synchronized (actions) {
            if (cancelled) return;
            cancelled = true;
            run = List.copyOf(actions);
            actions.clear();
        }
        for (var action : run) {
            try {
                action.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Cancel action failed", e);
            }
        }
    }

    public boolean isCancelled() {
        synchronized (actions) { return cancelled; }
    }

    /// Runs `action` on cancellation — immediately when already cancelled.
    public Registration onCancel(Runnable action) {
        synchronized (actions) {
            if (!cancelled) {
                actions.add(action);
                return () -> { synchronized (actions) { actions.remove(action); } };
            }
        }
        action.run();
        return () -> { };
    }

    @Override public String toString() { return "CancelToken[" + (isCancelled() ? "cancelled" : "active") + "]"; }
}
