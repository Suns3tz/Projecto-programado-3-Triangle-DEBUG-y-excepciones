package GUI;

import Core.Console.InputRedirector;
import Core.Console.OutputRedirector;
import Core.IDE.DebugInterpreter;
import Core.IDE.IDEDebugger;
import TAM.Machine;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;

/**
 Ventana del Debugger para IDE-Triangle.
 */
public class DebuggerFrame extends JFrame {

    private static final Color COLOR_CURRENT = new Color(255, 230, 100);
    private static final Font  MONO          = new Font("Monospaced", Font.PLAIN, 12);


    private final IDEDebugger      debugger;
    private final OutputRedirector outputRedirector;
    private final InputRedirector  inputRedirector;

    private int instructionCount = 0;
    private int currentHighlight = -1;

    // Panel de código
    private JList<String>            codeList;
    private DefaultListModel<String> codeModel;

    // Tabla de registros
    private JTable            registerTable;
    private DefaultTableModel registerModel;

    // Pila
    private JTextArea stackArea;

    // Consola
    private JTextArea  consoleArea;
    private JTextField inputField;
    private JButton    inputSendButton;

    // Barra de botones
    private JButton stepButton;
    private JButton finishButton;
    private JButton stopButton;

    // Barra de estado
    private JLabel statusLabel;

    public DebuggerFrame(String tamFilePath,
                         OutputRedirector output,
                         InputRedirector  input) {
        super("Debugger TAM  —  " + tamFilePath);
        this.outputRedirector = output;
        this.inputRedirector  = input;
        this.debugger         = new IDEDebugger();

        buildUI();
        wireCallbacks();

        try {
            setIconImage(new ImageIcon(
                getClass().getResource("/GUI/Icons/iconDebug.png")).getImage());
        } catch (Exception ignored) {}

        // Cargar e iniciar
        if (!debugger.loadProgram(tamFilePath)) {
            JOptionPane.showMessageDialog(this,
                "No se pudo cargar el archivo TAM:\n" + tamFilePath,
                "Error del Debugger", JOptionPane.ERROR_MESSAGE);
            dispose();
            return;
        }

        populateCodeList();
        redirectOutputToConsole();
        debugger.startDebug();

        highlightInstruction(0);
        updateRegisters();
        stepButton.setEnabled(true);
        finishButton.setEnabled(true);
        stopButton.setEnabled(true);
        setStatus("Listo — presione Step (F8) para comenzar", Color.DARK_GRAY);
    }

    private void buildUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1020, 700);
        setMinimumSize(new Dimension(820, 520));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { handleClose(); }
        });

        JPanel main = new JPanel(new BorderLayout(6, 6));
        main.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        main.add(buildToolBar(),     BorderLayout.NORTH);
        main.add(buildCodePanel(),   BorderLayout.CENTER);
        main.add(buildRightPanel(),  BorderLayout.EAST);
        main.add(buildBottomPanel(), BorderLayout.SOUTH);
        getContentPane().add(main);

        bindKey(KeyEvent.VK_F8,  () -> handleStep());
        bindKey(KeyEvent.VK_F9,  () -> handleFinish());
        bindKey(KeyEvent.VK_F10, () -> handleStop());
    }

    private JPanel buildToolBar() {
        stepButton     = makeButton("▶ Step (F8)",     e -> handleStep());
        finishButton = makeButton("▶▶ Finish (F9)", e -> handleFinish());
        stopButton     = makeButton("■ Stop (F10)",    e -> handleStop());

        stepButton.setEnabled(false);
        finishButton.setEnabled(false);
        stopButton.setEnabled(false);
        stopButton.setForeground(new Color(150, 0, 0));

        statusLabel = new JLabel("Iniciando...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        bar.add(stepButton);
        bar.add(finishButton);
        bar.add(new JSeparator(JSeparator.VERTICAL));
        bar.add(stopButton);
        bar.add(statusLabel);
        return bar;
    }

    // Panel de código (izquierda)

    private JScrollPane buildCodePanel() {
        codeModel = new DefaultListModel<>();
        codeList  = new JList<>(codeModel);
        codeList.setFont(MONO);
        codeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        codeList.setBackground(new Color(252, 252, 248));

        codeList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (!isSelected) {
                    if (index == currentHighlight) {
                        c.setBackground(COLOR_CURRENT);
                        c.setForeground(Color.BLACK);
                        ((JLabel) c).setFont(MONO.deriveFont(Font.BOLD));
                    } else {
                        c.setBackground(list.getBackground());
                        c.setForeground(list.getForeground());
                        ((JLabel) c).setFont(MONO);
                    }
                }
                return c;
            }
        });

        JScrollPane sp = new JScrollPane(codeList);
        titledBorder(sp, "Código TAM");
        sp.setPreferredSize(new Dimension(430, 400));
        return sp;
    }

    //Panel derecho (registros y pila)

    private JPanel buildRightPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setPreferredSize(new Dimension(290, 400));
        p.add(buildRegisterPanel(), BorderLayout.NORTH);
        p.add(buildStackPanel(),    BorderLayout.CENTER);
        return p;
    }

    private JScrollPane buildRegisterPanel() {
        String[] cols = {"Reg.", "Valor", "Descripción"};
        registerModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        registerTable = new JTable(registerModel);
        registerTable.setFont(MONO);
        registerTable.getTableHeader().setFont(MONO.deriveFont(Font.BOLD));
        registerTable.setRowHeight(20);
        registerTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        registerTable.getColumnModel().getColumn(1).setPreferredWidth(50);
        registerTable.getColumnModel().getColumn(2).setPreferredWidth(175);

        // Filas iniciales
        for (Object[] row : new Object[][]{
            {"CP", "-",    "Code pointer (instrucción actual)"},
            {"ST", "-",    "Stack top"},
            {"LB", "-",    "Local base (frame actual)"},
            {"HT", "-",    "Heap top"},
            {"CT", "-",    "Code top (# instrucciones)"},
            {"SB", "0",    "Stack base (siempre 0)"},
            {"HB", "1024", "Heap base (siempre 1024)"},
        }) registerModel.addRow(row);

        JScrollPane sp = new JScrollPane(registerTable);
        titledBorder(sp, "Registros TAM");
        sp.setPreferredSize(new Dimension(290, 190));
        return sp;
    }

    private JScrollPane buildStackPanel() {
        stackArea = new JTextArea();
        stackArea.setFont(MONO);
        stackArea.setEditable(false);
        stackArea.setBackground(new Color(248, 248, 255));

        JScrollPane sp = new JScrollPane(stackArea);
        titledBorder(sp, "Pila (Stack)");
        sp.setPreferredSize(new Dimension(290, 180));
        return sp;
    }

    // ---- Panel inferior (consola) ----

    private JPanel buildBottomPanel() {
        consoleArea = new JTextArea(5, 80);
        consoleArea.setFont(MONO);
        consoleArea.setEditable(false);
        consoleArea.setBackground(new Color(20, 20, 20));
        consoleArea.setForeground(new Color(200, 230, 200));

        JScrollPane sp = new JScrollPane(consoleArea);
        titledBorder(sp, "Consola");
        sp.setPreferredSize(new Dimension(0, 130));

        inputField      = new JTextField();
        inputField.setFont(MONO);
        inputField.setEnabled(false);
        inputSendButton = new JButton("Enviar");
        inputSendButton.setEnabled(false);

        ActionListener send = e -> sendInput();
        inputSendButton.addActionListener(send);
        inputField.addActionListener(send);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.add(new JLabel("  Entrada: "), BorderLayout.WEST);
        inputRow.add(inputField,      BorderLayout.CENTER);
        inputRow.add(inputSendButton, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(sp,       BorderLayout.CENTER);
        p.add(inputRow, BorderLayout.SOUTH);
        return p;
    }

    private void wireCallbacks() {
        debugger.setOnStepDone(() -> {
            DebugInterpreter interp = debugger.getInterpreter();
            highlightInstruction(interp.getCP());
            updateRegisters();
            updateStack();
            setStatus("CP=" + interp.getCP()
                    + "   ST=" + interp.getST()
                    + "   LB=" + interp.getLB()
                    + "   HT=" + interp.getHT(),
                    Color.DARK_GRAY);
        });
        debugger.setOnProgramHalted(() -> {
            DebugInterpreter interp = debugger.getInterpreter();
            updateRegisters();
            updateStack();
            highlightInstruction(-1);

            boolean ok = (interp.getStatus() == DebugInterpreter.HALTED);
            Color c = ok ? new Color(0, 120, 0) : new Color(160, 0, 0);
            setStatus(interp.getStatusMessage(), c);

            stepButton.setEnabled(false);
            finishButton.setEnabled(false);
            stopButton.setText("Cerrar");
            restoreOutputDelegate();
        });
    }

    private void handleStep() {
        if (!stepButton.isEnabled()) return;
        stepButton.setEnabled(false);
        finishButton.setEnabled(false);
        debugger.step();
        SwingUtilities.invokeLater(() -> {
            if (debugger.isRunning()) {
                stepButton.setEnabled(true);
                finishButton.setEnabled(true);
            }
        });
    }

    private void handleFinish() {
        if (!finishButton.isEnabled()) return;
        stepButton.setEnabled(false);
        finishButton.setEnabled(false);
        setStatus("Ejecutando en modo continuo...", Color.DARK_GRAY);
        debugger.finishRun();
    }

    private void handleStop() {
        debugger.stop();
        restoreOutputDelegate();
        dispose();
    }

    private void handleClose() {
        int r = JOptionPane.showConfirmDialog(this,
            "¿Desea detener la sesión de depuración y cerrar la ventana?",
            "Cerrar Debugger", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            debugger.stop();
            restoreOutputDelegate();
            dispose();
        }
    }

    public void enableInput() {
        SwingUtilities.invokeLater(() -> {
            inputField.setEnabled(true);
            inputSendButton.setEnabled(true);
            inputField.requestFocusInWindow();
        });
    }

    private void sendInput() {
        String text = inputField.getText();
        inputField.setText("");
        inputField.setEnabled(false);
        inputSendButton.setEnabled(false);
        consoleArea.append(text + "\n");
        inputRedirector.addInput(text + "\n");
    }

    private void redirectOutputToConsole() {
        outputRedirector.setDelegate(e -> {
            while (outputRedirector.peekQueue()) {
                String chunk = outputRedirector.readQueue();
                SwingUtilities.invokeLater(() -> {
                    consoleArea.append(chunk);
                    consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
                });
            }
        });
    }

    private void restoreOutputDelegate() {
        while (outputRedirector.peekQueue()) outputRedirector.readQueue();
    }

    private void populateCodeList() {
        codeModel.clear();
        instructionCount = debugger.getInterpreter().getCT();
        for (int i = Machine.CB; i < instructionCount; i++)
            codeModel.addElement(DebugInterpreter.instructionToString(i));
    }

    private void highlightInstruction(int addr) {
        currentHighlight = (addr >= Machine.CB) ? addr - Machine.CB : -1;
        codeList.repaint();
        if (currentHighlight >= 0 && currentHighlight < codeModel.size())
            codeList.ensureIndexIsVisible(currentHighlight);
    }

    private void updateRegisters() {
        DebugInterpreter interp = debugger.getInterpreter();
        Object[][] rows = {
            {"CP",  interp.getCP(),              "Code pointer (instrucción actual)"},
            {"ST",  interp.getST(),              "Stack top"},
            {"LB",  interp.getLB(),              "Local base (frame actual)"},
            {"HT",  interp.getHT(),              "Heap top"},
            {"CT",  interp.getCT(),              "Code top (# instrucciones)"},
            {"SB",  DebugInterpreter.SB_VAL,     "Stack base (siempre 0)"},
            {"HB",  DebugInterpreter.HB_VAL,     "Heap base (siempre 1024)"},
        };
        registerModel.setRowCount(0);
        for (Object[] row : rows) registerModel.addRow(row);
    }

    private void updateStack() {
        DebugInterpreter interp = debugger.getInterpreter();
        int[] data = interp.getData();
        int   st   = interp.getST();
        int   lb   = interp.getLB();
        StringBuilder sb = new StringBuilder();

        if (st <= DebugInterpreter.SB_VAL) {
            sb.append("(pila vacía)");
        } else {
            int start = Math.max(DebugInterpreter.SB_VAL, st - 20);
            for (int i = st - 1; i >= start; i--) {
                sb.append(String.format("%4d: %6d", i, data[i]));
                if (i == st - 1) sb.append("  ← ST");
                if (i == lb)     sb.append("  ← LB");
                sb.append("\n");
            }
            if (start > DebugInterpreter.SB_VAL)
                sb.append("     ... (").append(start).append(" slots más abajo)");
        }

        // Resumen del heap
        int ht = interp.getHT();
        if (ht < DebugInterpreter.HB_VAL) {
            sb.append("\n\n─── Heap ───\n");
            int end = Math.min(DebugInterpreter.HB_VAL, ht + 10);
            for (int i = ht; i < end; i++) {
                sb.append(String.format("%4d: %6d", i, data[i]));
                if (i == ht) sb.append("  ← HT");
                sb.append("\n");
            }
        }

        stackArea.setText(sb.toString());
        stackArea.setCaretPosition(0);
    }

    private void setStatus(String msg, Color color) {
        statusLabel.setText(msg);
        statusLabel.setForeground(color);
    }

    private void bindKey(int key, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(key, 0), "dbg_" + key);
        getRootPane().getActionMap()
            .put("dbg_" + key, new AbstractAction() {
                public void actionPerformed(ActionEvent e) { action.run(); }
            });
    }

    private JButton makeButton(String text, ActionListener l) {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 12));
        btn.addActionListener(l);
        return btn;
    }

    private void titledBorder(JComponent c, String title) {
        TitledBorder b = BorderFactory.createTitledBorder(title);
        b.setTitleFont(b.getTitleFont().deriveFont(Font.BOLD));
        c.setBorder(b);
    }
}