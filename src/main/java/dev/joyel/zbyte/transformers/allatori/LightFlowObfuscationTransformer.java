package dev.joyel.zbyte.transformers.allatori;

import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.executor.defined.JVMComparisonProvider;
import dev.joyel.zbyte.executor.defined.JVMMethodProvider;
import dev.joyel.zbyte.executor.defined.MappedFieldProvider;
import dev.joyel.zbyte.executor.defined.MappedMethodProvider;
import dev.joyel.zbyte.executor.providers.DelegatingProvider;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.Utils;

public class LightFlowObfuscationTransformer extends Transformer<TransformerConfig> 
{
	@Override 
    public boolean transform() throws Throwable {
    	DelegatingProvider provider = new DelegatingProvider();
        provider.register(new JVMMethodProvider());
        provider.register(new JVMComparisonProvider());
        provider.register(new MappedMethodProvider(classes));
        provider.register(new MappedFieldProvider());

        AtomicInteger fixed = new AtomicInteger();

        System.out.println("[Allatori] [LightFlowObfuscationTransformer] Starting");
        for(ClassNode classNode : classNodes())
        	for(MethodNode method : classNode.methods)
        	{
        		boolean modified;
        		do
        		{
        			modified = false;
	        		for(AbstractInsnNode ain : method.instructions.toArray())
	        			if((willPush(ain) || ain.getOpcode() == Opcodes.DUP) && ain.getNext() != null 
	        			&& (willPush(ain.getNext()) || ain.getNext().getOpcode() == Opcodes.DUP)
	        				&& ain.getNext().getNext() != null && ain.getNext().getNext().getOpcode() == Opcodes.POP2)
	        			{
	        				method.instructions.remove(ain.getNext().getNext());
	        				method.instructions.remove(ain.getNext());
	        				method.instructions.remove(ain);
	        				modified = true;
	        				fixed.incrementAndGet();
	        			}
        		}while(modified);
        	}
        System.out.println("[Allatori] [LightFlowObfuscationTransformer] Removed " + fixed + " dead instructions");
        System.out.println("[Allatori] [LightFlowObfuscationTransformer] Done");
        return fixed.get() > 0;
	}
	
	private boolean willPush(AbstractInsnNode ain)
    {
    	if(ain.getOpcode() == Opcodes.LDC && (((LdcInsnNode)ain).cst instanceof Long || ((LdcInsnNode)ain).cst instanceof Double))
    		return false;
    	return Utils.willPushToStack(ain.getOpcode()) && ain.getOpcode() != Opcodes.GETSTATIC
    		&& ain.getOpcode() != Opcodes.LLOAD && ain.getOpcode() != Opcodes.DLOAD;
    }
}
