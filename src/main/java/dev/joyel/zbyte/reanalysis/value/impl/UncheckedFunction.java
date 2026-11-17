package dev.joyel.zbyte.reanalysis.value.impl;

@FunctionalInterface
public interface UncheckedFunction<T, R> {
    R apply(T value) throws Exception;
}
