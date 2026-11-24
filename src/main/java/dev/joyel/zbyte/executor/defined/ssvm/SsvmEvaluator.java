package dev.joyel.zbyte.executor.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.classloading.BootClassLoader;
import dev.xdark.ssvm.classloading.ClassParseResult;
import dev.xdark.ssvm.classloading.RuntimeBootClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SsvmEvaluator implements AutoCloseable {

    private final ExecutorService worker;
    private final SsvmBridge bridge;
    private final long timeoutMillis;
    private volatile boolean aborted;

    public SsvmEvaluator(Map<String, ClassNode> classes, long timeoutMillis) throws Exception {
        this.timeoutMillis = timeoutMillis;
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "zbyte-ssvm");
            thread.setDaemon(true);
            return thread;
        });
        Future<SsvmBridge> boot = worker.submit(() -> new SsvmBridge(createVm(classes)));
        try {
            this.bridge = boot.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            aborted = true;
            worker.shutdownNow();
            throw e;
        } catch (ExecutionException e) {
            worker.shutdownNow();
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    public boolean isAborted() {
        return aborted;
    }

    public Object invokeStatic(String owner, String name, String desc, Object... arguments) throws Throwable {
        if (aborted) {
            throw new IllegalStateException("SSVM evaluator was aborted after a timeout");
        }
        Future<Object> call = worker.submit(() -> bridge.invoke(owner, name, desc, null, arguments));
        try {
            return call.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            aborted = true;
            call.cancel(true);
            worker.shutdownNow();
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private static VirtualMachine createVm(Map<String, ClassNode> classes) {
        VirtualMachine vm = new VirtualMachine() {
            @Override
            protected BootClassLoader createBootClassLoader() {
                BootClassLoader runtime = new RuntimeBootClassLoader();
                return new BootClassLoader() {
                    @Override
                    public ClassParseResult findBootClass(String name) {
                        ClassNode node = classes.get(name);
                        if (node == null) {
                            return runtime.findBootClass(name);
                        }
                        ClassWriter writer = new ClassWriter(0);
                        node.accept(writer);
                        ClassReader reader = new ClassReader(writer.toByteArray());
                        ClassNode copy = new ClassNode();
                        reader.accept(copy, 0);
                        return new ClassParseResult(reader, copy);
                    }
                };
            }
        };
        vm.bootstrap();
        return vm;
    }
}
