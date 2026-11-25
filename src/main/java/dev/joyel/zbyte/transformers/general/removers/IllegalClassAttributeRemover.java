package dev.joyel.zbyte.transformers.general.removers;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;

public class IllegalClassAttributeRemover extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            if (classNode.sourceDebug != null) {
                classNode.sourceDebug = null;
                changed = true;
            }
            if (classNode.outerClass != null && !known(classNode.outerClass)) {
                classNode.outerClass = null;
                classNode.outerMethod = null;
                classNode.outerMethodDesc = null;
                changed = true;
            }
            if (classNode.nestHostClass != null && !known(classNode.nestHostClass)) {
                classNode.nestHostClass = null;
                changed = true;
            }
            changed |= prune(classNode.nestMembers);
            if (classNode.nestMembers != null && classNode.nestMembers.isEmpty()) {
                classNode.nestMembers = null;
            }
            changed |= prune(classNode.permittedSubclasses);
            if (classNode.permittedSubclasses != null && classNode.permittedSubclasses.isEmpty()) {
                classNode.permittedSubclasses = null;
            }
            if (classNode.innerClasses != null) {
                changed |= classNode.innerClasses.removeIf(inner -> inner.outerName != null && inner.outerName.equals(inner.name));
            }
        }
        return changed;
    }

    private boolean prune(List<String> names) {
        return names != null && names.removeIf(name -> !known(name));
    }

    private boolean known(String name) {
        return classes.containsKey(name) || classpath.containsKey(name);
    }
}
