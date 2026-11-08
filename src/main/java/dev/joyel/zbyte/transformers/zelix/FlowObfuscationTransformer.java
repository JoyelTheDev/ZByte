package dev.joyel.zbyte.transformers.zelix;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.Utils;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;

public class FlowObfuscationTransformer extends Transformer<TransformerConfig> {
    @Override
    public boolean transform() throws Throwable {
        AtomicInteger counter = new AtomicInteger();

        classNodes().forEach(classNode -> {
            classNode.methods.stream().filter(Utils::notAbstractOrNative).forEach(methodNode -> {
                counter.addAndGet(methodNode.tryCatchBlocks.size());
                methodNode.tryCatchBlocks.removeIf(tc -> (tc.handler.getNext().getOpcode() == Opcodes.INVOKESTATIC && tc.handler.getNext().getNext().getOpcode() == Opcodes.ATHROW) || tc.handler.getNext().getOpcode() == Opcodes.ATHROW);
                counter.addAndGet(-methodNode.tryCatchBlocks.size());
            });
        });

        logger.info("Removed {} fake try-catch blocks", counter.get());
        return true;
    }
}
