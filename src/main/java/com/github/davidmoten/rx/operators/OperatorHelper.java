package com.github.davidmoten.rx.operators;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import rx.Observable.Operator;
import rx.Producer;
import rx.Subscriber;
import rx.schedulers.Schedulers;

import com.github.davidmoten.util.BackpressureUtils;
import com.github.davidmoten.util.Drainer;

public class OperatorHelper<T, R> implements Operator<T, R> {

    private final Operator<T, R> operator;
    private long initialRequest;

    public OperatorHelper(Operator<T, R> operator, long initialRequest) {
        this.operator = operator;
        this.initialRequest = initialRequest;
    }

    @Override
    public Subscriber<? super R> call(Subscriber<? super T> child) {
        final AtomicLong requestedDownstream = new AtomicLong();
        // need to make a sufficient initial request so that things get started
        // then emissions are buffered downstream of `operator` and upstream
        // requests are buffered upstream
        final AtomicBoolean openUpstream = new AtomicBoolean(false);
        final AtomicReference<ParentSubscriber<T, R>> parentRef = new AtomicReference<ParentSubscriber<T, R>>();
        Producer producer = new Producer() {
            @Override
            public void request(long n) {
                if (n > 0) {
                    BackpressureUtils.getAndAddRequest(requestedDownstream, n);
                    // now need to request more of the parent
                    long m = parentRef.get().requestedUpstream.getAndSet(0);
                    if (m > 0)
                        parentRef.get().requestMore(m);
                }
            }
        };
        Subscriber<? super T> subscription = child;
        Drainer<T> drainer = Drainer.create(new LinkedList<Object>(), subscription, Schedulers
                .trampoline().createWorker(), child, producer);
        final ParentSubscriber<T, R> parent = new ParentSubscriber<T, R>(drainer, operator, child,
                initialRequest);
        parentRef.set(parent);
        child.add(parent);
        child.setProducer(producer);
        return parent;
    }

    private static class ParentSubscriber<T, R> extends Subscriber<R> {

        private final Drainer<T> drainer;
        final AtomicLong requestedUpstream;

        public ParentSubscriber(Drainer<T> drainer, Operator<T, R> operator,
                Subscriber<? super T> child, long initialRequest) {
            this.drainer = drainer;
            this.requestedUpstream = new AtomicLong(initialRequest);
        }

        public void requestMore(long n) {
            // only request more if requestedDownstream is positive
            request(n);
        }

        @Override
        public void onCompleted() {
            // TODO Auto-generated method stub

        }

        @Override
        public void onError(Throwable e) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onNext(R t) {
            // TODO Auto-generated method stub

        }

    }

}

// sequence:
/*
 * wrapped operator called with child subscriber, initial request sent to
 * upstream as elements arrive they are added to drainer and sent to child when
 * requested. The drainer needs more to replace its empty queue and those
 * requests are added to requestedDownstream. The operator being wrapped is
 * written by the user such that as items arrive from upstream requests to
 * upstream are made with the purpose of having sufficient to emit again to the
 * drainer. Those requests are buffered though until requestedDownstream is
 * positive again. As soon as requestedDownstream hits 0 no requests go through
 * to upstream but are buffered in requestedUpstream.
 */