package Core.IDE;

import TAM.Instruction;
import TAM.Instruction;
import TAM.Machine;
import TAM.Machine;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.swing.SwingUtilities;

/**
 * Versión paso a paso del intérprete TAM para el Debugger.
 */
public class DebugInterpreter {

    public int[] data = new int[1024];
    public static final int CB_VAL = 0;
    public static final int SB_VAL = 0;
    public static final int HB_VAL = 1024;

    public int CT;
    public int CP;
    public int ST;
    public int HT;
    public int LB;
    public int status;

    public static final int RUNNING                    = 0;
    public static final int HALTED                     = 1;
    public static final int FAILED_DATA_STORE_FULL     = 2;
    public static final int FAILED_INVALID_CODE_ADDR   = 3;
    public static final int FAILED_INVALID_INSTRUCTION = 4;
    public static final int FAILED_OVERFLOW            = 5;
    public static final int FAILED_ZERO_DIVIDE         = 6;
    public static final int FAILED_IO_ERROR            = 7;

    private long accumulator;
    private int  currentChar;

    private Thread           interpreterThread;
    private final Object     stepLock   = new Object();
    private volatile boolean canProceed = false;
    private volatile boolean stepMode   = true;
    private volatile boolean shouldStop = false;

    private Runnable onStepDone;
    private Runnable onProgramHalted;

    public void setOnStepDone(Runnable r)       { onStepDone = r; }
    public void setOnProgramHalted(Runnable r)  { onProgramHalted = r; }

    public boolean loadProgram(String objectName) {
        FileInputStream  objectFile   = null;
        DataInputStream  objectStream = null;
        int     addr;
        boolean finished = false;

        try {
            objectFile   = new FileInputStream(objectName);
            objectStream = new DataInputStream(objectFile);
            addr = Machine.CB;
            while (!finished) {
                Machine.code[addr] = Instruction.read(objectStream);
                if (Machine.code[addr] == null)
                    finished = true;
                else
                    addr++;
            }
            CT = addr;
            objectFile.close();
            return CT > Machine.CB;
        } catch (FileNotFoundException e) {
            CT = Machine.CB;
            System.err.println("DebugInterpreter: archivo no encontrado: " + e.getMessage());
            return false;
        } catch (IOException e) {
            CT = Machine.CB;
            System.err.println("DebugInterpreter: error de I/O: " + e.getMessage());
            return false;
        }
    }

    public void startDebug() {
        ST         = SB_VAL;
        HT         = HB_VAL;
        LB         = SB_VAL;
        CP         = Machine.CB;
        status     = RUNNING;
        canProceed = false;
        shouldStop = false;
        stepMode   = true;

        interpreterThread = new Thread(() -> runLoop());
        interpreterThread.setDaemon(true);
        interpreterThread.setName("TAM-DebugInterpreter");
        interpreterThread.start();
    }

    /** Ejecuta una instrucción y pausa hasta la siguiente señal. */
    public void step() {
        synchronized (stepLock) {
            stepMode   = true;
            canProceed = true;
            stepLock.notifyAll();
        }
    }

    /** Ejecuta hasta que el programa termine. */
    public void finishRun() {
        synchronized (stepLock) {
            stepMode   = false;
            canProceed = true;
            stepLock.notifyAll();
        }
    }

    /** Detiene la ejecución inmediatamente. */
    public void stop() {
        synchronized (stepLock) {
            shouldStop = true;
            canProceed = true;
            stepLock.notifyAll();
        }
        if (interpreterThread != null)
            interpreterThread.interrupt();
    }

    private void runLoop() {
        do {
            // Esperando señal step/finish
            if (stepMode) {
                synchronized (stepLock) {
                    while (!canProceed && !shouldStop) {
                        try { stepLock.wait(); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            status = HALTED;
                            fireHalted();
                            return;
                        }
                    }
                    if (shouldStop) { status = HALTED; fireHalted(); return; }
                    canProceed = false;
                }
            } else {
                synchronized (stepLock) {
                    if (shouldStop) { status = HALTED; fireHalted(); return; }
                }
            }

            // Ejecutar una instrucción
            executeOneInstruction();

            // Verificar CP válido
            if (status == RUNNING && (CP < Machine.CB || CP >= CT))
                status = FAILED_INVALID_CODE_ADDR;

            if (onStepDone != null)
                SwingUtilities.invokeLater(onStepDone);

        } while (status == RUNNING);

        fireHalted();
    }

    private void fireHalted() {
        if (onProgramHalted != null)
            SwingUtilities.invokeLater(onProgramHalted);
    }

    private void executeOneInstruction() {
        Instruction instr = Machine.code[CP];
        if (instr == null) { status = FAILED_INVALID_CODE_ADDR; return; }

        int op = instr.op, r = instr.r, n = instr.n, d = instr.d;
        int addr, index;

        switch (op) {
            case Machine.LOADop:
                addr = d + content(r); checkSpace(n);
                if (status != RUNNING) return;
                for (index = 0; index < n; index++) data[ST + index] = data[addr + index];
                ST += n; CP++; break;

            case Machine.LOADAop:
                addr = d + content(r); checkSpace(1);
                if (status != RUNNING) return;
                data[ST] = addr; ST++; CP++; break;

            case Machine.LOADIop:
                ST--; addr = data[ST]; checkSpace(n);
                if (status != RUNNING) return;
                for (index = 0; index < n; index++) data[ST + index] = data[addr + index];
                ST += n; CP++; break;

            case Machine.LOADLop:
                checkSpace(1); if (status != RUNNING) return;
                data[ST] = d; ST++; CP++; break;

            case Machine.STOREop:
                addr = d + content(r); ST -= n;
                for (index = 0; index < n; index++) data[addr + index] = data[ST + index];
                CP++; break;

            case Machine.STOREIop:
                ST--; addr = data[ST]; ST -= n;
                for (index = 0; index < n; index++) data[addr + index] = data[ST + index];
                CP++; break;

            case Machine.CALLop:
                addr = d + content(r);
                if (addr >= Machine.PB) { callPrimitive(addr - Machine.PB); CP++; }
                else {
                    checkSpace(3); if (status != RUNNING) return;
                    if ((0 <= n) && (n <= 15)) data[ST] = content(n);
                    else { status = FAILED_INVALID_INSTRUCTION; return; }
                    data[ST+1] = LB; data[ST+2] = CP+1;
                    LB = ST; ST += 3; CP = addr;
                }
                break;

            case Machine.CALLIop:
                ST -= 2; addr = data[ST+1];
                if (addr >= Machine.PB) { callPrimitive(addr - Machine.PB); CP++; }
                else { data[ST+1] = LB; data[ST+2] = CP+1; LB = ST; ST += 3; CP = addr; }
                break;

            case Machine.RETURNop:
                addr = LB - d; CP = data[LB+2]; LB = data[LB+1]; ST -= n;
                for (index = 0; index < n; index++) data[addr + index] = data[ST + index];
                ST = addr + n; break;

            case Machine.PUSHop:
                checkSpace(d); if (status != RUNNING) return;
                ST += d; CP++; break;

            case Machine.POPop:
                addr = ST - n - d; ST -= n;
                for (index = 0; index < n; index++) data[addr + index] = data[ST + index];
                ST = addr + n; CP++; break;

            case Machine.JUMPop:    CP = d + content(r); break;
            case Machine.JUMPIop:   ST--; CP = data[ST]; break;
            case Machine.JUMPIFop:  ST--; CP = (data[ST] == n) ? d + content(r) : CP + 1; break;
            case Machine.HALTop:    status = HALTED; break;
            default:                status = FAILED_INVALID_INSTRUCTION;
        }
    }

    private void callPrimitive(int disp) {
        int addr, size; char ch;
        switch (disp) {
            case Machine.idDisplacement:      break;
            case Machine.notDisplacement:     data[ST-1] = toInt(!isTrue(data[ST-1])); break;
            case Machine.andDisplacement:     ST--; data[ST-1] = toInt(isTrue(data[ST-1]) & isTrue(data[ST])); break;
            case Machine.orDisplacement:      ST--; data[ST-1] = toInt(isTrue(data[ST-1]) | isTrue(data[ST])); break;
            case Machine.succDisplacement:    data[ST-1] = overflowChecked(data[ST-1] + 1); break;
            case Machine.predDisplacement:    data[ST-1] = overflowChecked(data[ST-1] - 1); break;
            case Machine.negDisplacement:     data[ST-1] = -data[ST-1]; break;
            case Machine.addDisplacement:
                ST--; accumulator = data[ST-1]; data[ST-1] = overflowChecked(accumulator + data[ST]); break;
            case Machine.subDisplacement:
                ST--; accumulator = data[ST-1]; data[ST-1] = overflowChecked(accumulator - data[ST]); break;
            case Machine.multDisplacement:
                ST--; accumulator = data[ST-1]; data[ST-1] = overflowChecked(accumulator * data[ST]); break;
            case Machine.divDisplacement:
                ST--; accumulator = data[ST-1];
                if (data[ST] != 0) data[ST-1] = (int)(accumulator / data[ST]);
                else status = FAILED_ZERO_DIVIDE; break;
            case Machine.modDisplacement:
                ST--; accumulator = data[ST-1];
                if (data[ST] != 0) data[ST-1] = (int)(accumulator % data[ST]);
                else status = FAILED_ZERO_DIVIDE; break;
            case Machine.ltDisplacement:  ST--; data[ST-1] = toInt(data[ST-1] <  data[ST]); break;
            case Machine.leDisplacement:  ST--; data[ST-1] = toInt(data[ST-1] <= data[ST]); break;
            case Machine.geDisplacement:  ST--; data[ST-1] = toInt(data[ST-1] >= data[ST]); break;
            case Machine.gtDisplacement:  ST--; data[ST-1] = toInt(data[ST-1] >  data[ST]); break;
            case Machine.eqDisplacement:
                size = data[ST-1]; ST -= 2*size; data[ST-1] = toInt(equal(size, ST-1, ST-1+size)); break;
            case Machine.neDisplacement:
                size = data[ST-1]; ST -= 2*size; data[ST-1] = toInt(!equal(size, ST-1, ST-1+size)); break;
            case Machine.eolDisplacement:  data[ST] = toInt(currentChar == '\n'); ST++; break;
            case Machine.eofDisplacement:  data[ST] = toInt(currentChar == -1);  ST++; break;
            case Machine.getDisplacement:
                ST--; addr = data[ST];
                try { currentChar = System.in.read(); } catch (IOException e) { status = FAILED_IO_ERROR; }
                data[addr] = currentChar; break;
            case Machine.putDisplacement:
                ST--; ch = (char) data[ST]; System.out.print(ch); break;
            case Machine.geteolDisplacement:
                try { while ((currentChar = System.in.read()) != '\n'); } catch (IOException e) { status = FAILED_IO_ERROR; } break;
            case Machine.puteolDisplacement:
                System.out.println(); break;
            case Machine.getintDisplacement:
                ST--; addr = data[ST];
                try { accumulator = readInt(); } catch (IOException e) { status = FAILED_IO_ERROR; }
                data[addr] = (int) accumulator; break;
            case Machine.putintDisplacement:
                ST--; accumulator = data[ST]; System.out.print(accumulator); break;
            case Machine.newDisplacement:
                size = data[ST-1]; checkSpace(size); HT -= size; data[ST-1] = HT; break;
            case Machine.disposeDisplacement:
                ST--; break;
        }
    }

    private int content(int r) {
        switch (r) {
            case Machine.CBr: return Machine.CB;  case Machine.CTr: return CT;
            case Machine.PBr: return Machine.PB;  case Machine.PTr: return Machine.PT;
            case Machine.SBr: return SB_VAL;      case Machine.STr: return ST;
            case Machine.HBr: return HB_VAL;      case Machine.HTr: return HT;
            case Machine.LBr: return LB;
            case Machine.L1r: return data[LB];
            case Machine.L2r: return data[data[LB]];
            case Machine.L3r: return data[data[data[LB]]];
            case Machine.L4r: return data[data[data[data[LB]]]];
            case Machine.L5r: return data[data[data[data[data[LB]]]]];
            case Machine.L6r: return data[data[data[data[data[data[LB]]]]]];
            case Machine.CPr: return CP;
            default:          return 0;
        }
    }

    private void checkSpace(int n) { if (HT - ST < n) status = FAILED_DATA_STORE_FULL; }
    private boolean isTrue(int d)  { return d == Machine.trueRep; }
    private int toInt(boolean b)   { return b ? Machine.trueRep : Machine.falseRep; }

    private boolean equal(int size, int a1, int a2) {
        for (int i = 0; i < size; i++) if (data[a1+i] != data[a2+i]) return false;
        return true;
    }

    private int overflowChecked(long datum) {
        if ((-Machine.maxintRep <= datum) && (datum <= Machine.maxintRep)) return (int) datum;
        status = FAILED_OVERFLOW; return 0;
    }

    private int readInt() throws IOException {
        int temp = 0, sign = 1;
        do { currentChar = System.in.read(); } while (Character.isWhitespace((char) currentChar));
        if (currentChar == '-' || currentChar == '+') {
            do { sign = (currentChar == '-') ? -1 : 1; currentChar = System.in.read(); }
            while (currentChar == '-' || currentChar == '+');
        }
        if (Character.isDigit((char) currentChar)) {
            do { temp = temp * 10 + (currentChar - '0'); currentChar = System.in.read(); }
            while (Character.isDigit((char) currentChar));
        }
        return sign * temp;
    }

    public int    getCP()      { return CP; }
    public int    getST()      { return ST; }
    public int    getHT()      { return HT; }
    public int    getLB()      { return LB; }
    public int    getCT()      { return CT; }
    public int    getStatus()  { return status; }
    public int[]  getData()    { return data; }
    public boolean isRunning() { return status == RUNNING; }

    public String getStatusMessage() {
        switch (status) {
            case RUNNING:                    return "Ejecutando...";
            case HALTED:                     return "Finalizado correctamente";
            case FAILED_DATA_STORE_FULL:     return "Error: Data Store lleno";
            case FAILED_INVALID_CODE_ADDR:   return "Error: Dirección de código inválida";
            case FAILED_INVALID_INSTRUCTION: return "Error: Instrucción inválida";
            case FAILED_OVERFLOW:            return "Error: Overflow aritmético";
            case FAILED_ZERO_DIVIDE:         return "Error: División por cero";
            case FAILED_IO_ERROR:            return "Error: Fallo de E/S";
            default:                         return "Estado desconocido";
        }
    }

    public static String instructionToString(int addr) {
        if (addr < 0 || addr >= Machine.code.length || Machine.code[addr] == null)
            return String.format("%3d:  (vacío)", addr);
        Instruction ins = Machine.code[addr];
        StringBuilder sb = new StringBuilder(String.format("%3d:  ", addr));
        switch (ins.op) {
            case Machine.LOADop:    sb.append(String.format("LOAD    (%d) %d[%s]",  ins.n, ins.d, reg(ins.r))); break;
            case Machine.LOADAop:   sb.append(String.format("LOADA   %d[%s]",        ins.d, reg(ins.r)));        break;
            case Machine.LOADIop:   sb.append(String.format("LOADI   (%d)",          ins.n));                    break;
            case Machine.LOADLop:   sb.append(String.format("LOADL   %d",            ins.d));                    break;
            case Machine.STOREop:   sb.append(String.format("STORE   (%d) %d[%s]",  ins.n, ins.d, reg(ins.r))); break;
            case Machine.STOREIop:  sb.append(String.format("STOREI  (%d)",          ins.n));                    break;
            case Machine.CALLop:
                if (ins.r == Machine.PBr) sb.append("CALL    " + prim(ins.d));
                else sb.append(String.format("CALL    (%s) %d[%s]", reg(ins.n), ins.d, reg(ins.r)));             break;
            case Machine.CALLIop:   sb.append("CALLI");                                                          break;
            case Machine.RETURNop:  sb.append(String.format("RETURN  (%d) %d",      ins.n, ins.d));              break;
            case Machine.PUSHop:    sb.append(String.format("PUSH    %d",            ins.d));                    break;
            case Machine.POPop:     sb.append(String.format("POP     (%d) %d",      ins.n, ins.d));              break;
            case Machine.JUMPop:    sb.append(String.format("JUMP    %d[%s]",       ins.d, reg(ins.r)));         break;
            case Machine.JUMPIop:   sb.append("JUMPI");                                                          break;
            case Machine.JUMPIFop:  sb.append(String.format("JUMPIF  (%d) %d[%s]", ins.n, ins.d, reg(ins.r)));  break;
            case Machine.HALTop:    sb.append("HALT");                                                            break;
            default:                sb.append("???");
        }
        return sb.toString();
    }

    private static String reg(int r) {
        switch (r) {
            case Machine.CBr: return "CB"; case Machine.CTr: return "CT";
            case Machine.PBr: return "PB"; case Machine.PTr: return "PT";
            case Machine.SBr: return "SB"; case Machine.STr: return "ST";
            case Machine.HBr: return "HB"; case Machine.HTr: return "HT";
            case Machine.LBr: return "LB"; case Machine.L1r: return "L1";
            case Machine.L2r: return "L2"; case Machine.L3r: return "L3";
            case Machine.L4r: return "L4"; case Machine.L5r: return "L5";
            case Machine.L6r: return "L6"; case Machine.CPr: return "CP";
            default: return "r" + r;
        }
    }

    private static String prim(int d) {
        switch (d) {
            case Machine.idDisplacement:      return "id";
            case Machine.notDisplacement:     return "not";
            case Machine.andDisplacement:     return "and";
            case Machine.orDisplacement:      return "or";
            case Machine.succDisplacement:    return "succ";
            case Machine.predDisplacement:    return "pred";
            case Machine.negDisplacement:     return "neg";
            case Machine.addDisplacement:     return "add";
            case Machine.subDisplacement:     return "sub";
            case Machine.multDisplacement:    return "mult";
            case Machine.divDisplacement:     return "div";
            case Machine.modDisplacement:     return "mod";
            case Machine.ltDisplacement:      return "lt";
            case Machine.leDisplacement:      return "le";
            case Machine.geDisplacement:      return "ge";
            case Machine.gtDisplacement:      return "gt";
            case Machine.eqDisplacement:      return "eq";
            case Machine.neDisplacement:      return "ne";
            case Machine.eolDisplacement:     return "eol";
            case Machine.eofDisplacement:     return "eof";
            case Machine.getDisplacement:     return "get";
            case Machine.putDisplacement:     return "put";
            case Machine.geteolDisplacement:  return "geteol";
            case Machine.puteolDisplacement:  return "puteol";
            case Machine.getintDisplacement:  return "getint";
            case Machine.putintDisplacement:  return "putint";
            case Machine.newDisplacement:     return "new";
            case Machine.disposeDisplacement: return "dispose";
            default: return "prim" + d;
        }
    }
}