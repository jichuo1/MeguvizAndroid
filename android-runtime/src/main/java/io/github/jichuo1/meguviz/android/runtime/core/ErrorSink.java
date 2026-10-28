package io.github.jichuo1.meguviz.android.runtime.core;

@FunctionalInterface
public interface ErrorSink {
    void onError(Throwable error);
}
