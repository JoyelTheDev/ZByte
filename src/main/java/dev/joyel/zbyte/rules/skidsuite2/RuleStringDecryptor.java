package dev.joyel.zbyte.rules.skidsuite2;

import java.util.Collection;
import java.util.Collections;

import dev.joyel.zbyte.Deobfuscator;
import dev.joyel.zbyte.analyzer.AnalyzerResult;
import dev.joyel.zbyte.analyzer.MethodAnalyzer;
import dev.joyel.zbyte.analyzer.frame.Frame;
import dev.joyel.zbyte.analyzer.frame.LdcFrame;
import dev.joyel.zbyte.analyzer.frame.MethodFrame;
import dev.joyel.zbyte.analyzer.frame.NewArrayFrame;
import dev.joyel.zbyte.rules.Rule;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.skidsuite2.StringEncryptionTransformer;
import dev.joyel.zbyte.utils.TransformerHelper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class RuleStringDecryptor implements Rule {

    @Override
    public String getDescription() {
        return "SkidSuite2 uses String Arrays for String Obfuscation";
    }

    @Override
    public String test(Deobfuscator deobfuscator) {
        for (ClassNode classNode : deobfuscator.getClasses().values()) {
            for (MethodNode methodNode : classNode.methods) {
                if (methodNode.instructions == null || methodNode.instructions.size() == 0) {
                    continue;
                }
                AnalyzerResult analyzerResult = null;
                insns: for (AbstractInsnNode ain : methodNode.instructions) {
                    if (!TransformerHelper.isInvokeStatic(ain,null, null, "([CLjava/lang/String;I)Ljava/lang/String;")) {
                        continue;
                    }
                    MethodInsnNode min = (MethodInsnNode) ain;

                    try {
                        if (analyzerResult == null) {
                            analyzerResult = MethodAnalyzer.analyze(classNode, methodNode);
                        }
                    } catch (Exception e) {
                        if (deobfuscator.getConfig().isDebugRulesAnalyzer()) {
                            e.printStackTrace();
                        }
                        break;
                    }
                    MethodFrame frame = (MethodFrame) analyzerResult.getFrames().get(min).get(0);

                    for (Frame arg : frame.getArgs()) {
                        if (arg instanceof LdcFrame) {
                            Object cst = ((LdcFrame) arg).getConstant();
                            if (cst == null) {
                                continue insns;
                            }
                            if (deobfuscator.getClasses().containsKey(min.owner)) {
                                return "Found possible encrypted string array in " + classNode.name + "/" + methodNode.name + methodNode.desc;
                            }
                        } else if (arg instanceof NewArrayFrame) {
                            if (deobfuscator.getClasses().containsKey(min.owner)) {
                                return "Found possible encrypted string array in " + classNode.name + "/" + methodNode.name + methodNode.desc;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Collection<Class<? extends Transformer<?>>> getRecommendTransformers() {
        return Collections.singleton(StringEncryptionTransformer.class);
    }
}
