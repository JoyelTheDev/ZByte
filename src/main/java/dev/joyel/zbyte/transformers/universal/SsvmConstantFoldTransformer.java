package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.defined.SsvmMethodProvider;
import dev.xdark.ssvm.VirtualMachine;
import org.objectweb.asm.tree.*;
import java.util.*;

public class SsvmConstantFoldTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        VirtualMachine vm = buildVm();
        boolean changed = false;

        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            MethodNode clinit = clinitOf(classNode);
            if (clinit == null) continue;

            Map<String, Object> resolved = new HashMap<>();
            Context ctx = buildContext(vm, resolved);

            try {
                MethodExecutor.execute(classNode, clinit, Collections.emptyList(), null, ctx);
            } catch (Throwable ignored) {
            }

            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != GETSTATIC) continue;
                    FieldInsnNode f = (FieldInsnNode) insn;
                    if (!f.owner.equals(classNode.name)) continue;
                    Object val = resolved.get(f.name);
                    if (!(val instanceof String)) continue;

                    method.instructions.set(insn, new LdcInsnNode(val));
                    changed = true;
                }
            }
        }
        return changed;
    }

    private Context buildContext(VirtualMachine vm, Map<String, Object> resolved) {
        Context ctx = new Context();
        ctx.provider.register(new dev.joyel.zbyte.executor.defined.JVMMethodProvider());
        ctx.provider.register(new SsvmMethodProvider(vm));
        ctx.dispatcher.intercept((frame, insn) -> {
            if (insn.getOpcode() == PUTSTATIC) {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (!frame.stack.isEmpty()) {
                    Object top = frame.stack.get(frame.stack.size() - 1).value();
                    if (top instanceof String || top instanceof Number) {
                        resolved.put(f.name, top);
                    }
                }
            }
            return dev.joyel.zbyte.executor.insn.InterceptResult.PROCEED;
        }, PUTSTATIC);
        return ctx;
    }

    private VirtualMachine buildVm() {
        VirtualMachine vm = new VirtualMachine();
        vm.bootstrap();
        for (ClassNode node : classNodes()) {
            vm.getClassDefiner().defineClass(node);
        }
        return vm;
    }

    private static MethodNode clinitOf(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (m.name.equals("<clinit>")) return m;
        }
        return null;
    }
}