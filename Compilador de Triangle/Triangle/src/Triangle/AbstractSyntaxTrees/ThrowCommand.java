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
public class ThrowCommand extends Command {
    
    public ThrowCommand(Expression East, SourcePosition thePosition){
        super(thePosition);
        E = East;
    }
    
    public Object visit(Visitor v, Object o){
        return v.visitThrowCommand(this, o);
    }
    
    
    public Expression E;
}
