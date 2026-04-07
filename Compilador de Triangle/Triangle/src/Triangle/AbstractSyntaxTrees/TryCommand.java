/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package Triangle.AbstractSyntaxTrees;

import Triangle.SyntacticAnalyzer.SourcePosition;

/**
 *
 * @author aaron
 */
public class TryCommand extends Command{
    public TryCommand(Command c1AST, Identifier iAST, Command c2AST, SourcePosition thePosition ) {
        super(thePosition);
        C1 = c1AST;
        I = iAST;
        C2 = c2AST;
    }
    public Object visit(Visitor v, Object o ){
        return v.visitTryCommand(this, o);
    }
    
    public Command C1;
    public Identifier I;
    public Command C2;
}
