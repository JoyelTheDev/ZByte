package dev.joyel.zbyte.report;

import java.util.Collection;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

public record Metrics(int classes, int fields, int methods, int instructions, int jumps, int tryCatchBlocks, int stringConstants) {

    public static Metrics empty() {
        return new Metrics(0, 0, 0, 0, 0, 0, 0);
    }

    public static Metrics of(Collection<ClassNode> nodes) {
        int classes = 0;
        int fields = 0;
        int methods = 0;
        int instructions = 0;
        int jumps = 0;
        int tryCatch = 0;
        int strings = 0;
        for (ClassNode node : nodes) {
            classes++;
            if (node.fields != null) {
                fields += node.fields.size();
            }
            if (node.methods == null) {
                continue;
            }
            for (MethodNode method : node.methods) {
                methods++;
                if (method.tryCatchBlocks != null) {
                    tryCatch += method.tryCatchBlocks.size();
                }
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() >= 0) {
                        instructions++;
                    }
                    if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                        jumps++;
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                        strings++;
                    }
                }
            }
        }
        return new Metrics(classes, fields, methods, instructions, jumps, tryCatch, strings);
    }
}
