package org.zstack.core.asyncbatch;

import org.zstack.core.thread.ThreadGlobalProperty;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.workflow.WhileCompletion;
import org.zstack.utils.DebugUtils;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by xing5 on 2017/3/5.
 */

public class While<T> {
    private Collection<T> items;
    private Do consumer;

    private int step;
    private WhileMode mode;

    private AtomicBoolean isOver = new AtomicBoolean(false);
    private AtomicInteger doneCount = new AtomicInteger(0);

    public interface Do<T> {
        void accept(T item, WhileCompletion completion);
    }

    public While(Collection<T> items) {
        this.items = items;
        doneCount.set(items.size());
    }

    public While each(Do<T> consumer) {
        mode = WhileMode.EACH;
        this.consumer = consumer;
        return this;
    }

    @Deprecated
    public While all(Do<T> consumer) {
        this.mode = WhileMode.STEP;
        this.consumer = consumer;

        int concurrencyLevel = WhileGlobalProperty.CONCURRENCY_LEVEL_OF_ALL_MODE;
        if (concurrencyLevel <= 0) {
            this.step = items.size();
        } else {
            this.step = ThreadGlobalProperty.MAX_THREAD_NUM * concurrencyLevel / 100;
        }

        this.step = this.step == 0 ? 1 : this.step;
        return this;
    }

    private void run(Iterator<T> it, NoErrorCompletion completion) {
        if (!it.hasNext()) {
            completion.done();
            return;
        }

        T t = it.next();
        consumer.accept(t, new WhileCompletion(completion) {
            @Override
            public void allDone() {
                completion.done();
            }

            @Override
            public void done() {
                run(it, completion);
            }
        });
    }

    public While step(Do<T> consumer, int step) {
        if (step < 0) {
            throw new IllegalArgumentException(String.format("step must be greater than zero, got %s", step));
        }

        this.consumer = consumer;
        this.step = step;
        mode = WhileMode.STEP;
        return this;
    }

    public void run(NoErrorCompletion completion) {
        DebugUtils.Assert(consumer != null, "each() or all() or step() must be called before run()");
        if (items.isEmpty()) {
            completion.done();
            return;
        }

        switch (mode) {
            case EACH: {
                run(items.iterator(), completion);
            }
            break;
            case STEP: {
                runStep(completion);
            }
            break;
            default:
                DebugUtils.Assert(false, "should be here");
                break;
        }
    }

    private void runStep(NoErrorCompletion completion) {
        int s = Math.min(step, items.size());

        Iterator<T> it = items.iterator();
        for (int i = 0; i < s; i++) {
            runStep(it, completion);
        }
    }

    private void runStep(Iterator<T> it, NoErrorCompletion completion) {
        T t;
        synchronized (it) {
            if (!it.hasNext() || isOver.get()) {
                return;
            }

            t = it.next();
        }

        consumer.accept(t, new WhileCompletion(completion) {
            @Override
            public void allDone() {
                doneCompletion(completion);
            }

            @Override
            public void done() {
                if (doneCount.decrementAndGet() == 0) {
                    doneCompletion(completion);
                    return;
                }
                runStep(it, completion);
            }
        });
    }

    private void doneCompletion(NoErrorCompletion completion) {
        if (isOver.compareAndSet(false, true)) {
            completion.done();
        }
    }
}
