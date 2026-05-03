package Core.IDE;

public class IDEDebugger {

    private final DebugInterpreter debugInterpreter;
    private boolean programLoaded = false;

    public IDEDebugger() {
        debugInterpreter = new DebugInterpreter();
    }

    public boolean loadProgram(String tamFilePath) {
        programLoaded = debugInterpreter.loadProgram(tamFilePath);
        return programLoaded;
    }

    /**
     * Inicia la sesión de depuración
     */
    public boolean startDebug() {
        if (!programLoaded) return false;
        debugInterpreter.startDebug();
        return true;
    }
    /** Ejecuta una instrucción TAM y pausa. */
    public void step()        { debugInterpreter.step(); }

    /** Corre hasta que el programa finalice. */
    public void finishRun() { debugInterpreter.finishRun(); }

    /** Aborta la sesión de depuración. */
    public void stop()        { debugInterpreter.stop(); }

    public void setOnStepDone(Runnable r)      { debugInterpreter.setOnStepDone(r); }

    public void setOnProgramHalted(Runnable r) { debugInterpreter.setOnProgramHalted(r); }

    public DebugInterpreter getInterpreter() { return debugInterpreter; }

    public boolean isProgramLoaded() { return programLoaded; }
    public boolean isRunning()       { return debugInterpreter.isRunning(); }
}