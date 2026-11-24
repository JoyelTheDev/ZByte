package dev.joyel.zbyte.analyzer.frame;

public class ArgumentFrame extends Frame {
	private int opcode;
	private int local;
	
    public ArgumentFrame(int opcode, int local) {
        super(-1);
        this.opcode = opcode;
        this.local = local;
    }
    
    public int getLocal() {
    	return local;
    }
    
    public int getStoreOpcode() {
    	return opcode;
    }
    
    // fixme how to determine?
    @Override
    public boolean isConstant() {
        return false;
    }
}
