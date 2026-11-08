package dev.joyel.zbyte.analysis.value.impl;

@FunctionalInterface
public interface UncheckedFunction<T, R> {
    R apply(T value) throws Exception;
}
