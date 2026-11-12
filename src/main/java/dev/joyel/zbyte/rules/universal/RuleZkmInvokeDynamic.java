package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.ZByte;
import dev.joyel.zbyte.rules.Rule;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.zkm.ZkmInvokeDynamicResolver;
import dev.joyel.zbyte.transformers.zkm.ZkmTransformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;
import java.util.Collections;

public class RuleZkmInvokeDynamic implements Rule {

    @Override
    public String getDescription() {
        return "Zelix KlassMaster 26 hides member accesses and calls behind invokedynamic call sites that take two long keys";
    }

    @Override
    public String test(ZByte zbyte) {
        int sites = 0;
        String first = null;
        for (ClassNode classNode : zbyte.getClasses().values()) {
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof InvokeDynamicInsnNode indy && ZkmInvokeDynamicResolver.isZkmSite(indy)) {
                        sites++;
                        if (first == null) {
                            first = classNode.name + "." + method.name + method.desc;
                        }
                    }
                }
            }
        }
        return sites == 0 ? null : "Found " + sites + " ZKM invokedynamic call site(s), first in " + first;
    }

    @Override
    public Collection<Class<? extends Transformer<?>>> getRecommendTransformers() {
        return Collections.singletonList(ZkmTransformer.class);
    }
}
