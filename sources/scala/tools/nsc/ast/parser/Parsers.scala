/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.ast.parser;

import scala.tools.nsc.util.Position;
import util.ListBuffer;
import symtab.Flags;
import Tokens._;

/** Performs the following context-free rewritings:
 *  (1) Places all pattern variables in Bind nodes. In a pattern, for identifiers `x':
 *                 x  => x @ _
 *               x:T  => x @ (_ : T)
 *
 *  (2) Removes pattern definitions (PatDef's) as follows:
 *      If pattern is a simple (typed) identifier:
 *        val x = e     ==>  val x = e
 *        val x: T = e  ==>  val x: T = e
 *
 *      if there are no variables in pattern
 *        val p = e  ==>  e.match (case p => ())
 *
 *      if there is exactly one variable in pattern
 *        val x_1 = e.match (case p => (x_1))
 *
 *      if there is more than one variable in pattern
 *        val p = e  ==>  private synthetic val t$ = e.match (case p => (x_1, ..., x_N))
 *                        val x_1 = t$._1
 *                        ...
 *                        val x_N = t$._N
 *
 *  (3) Removes function types as follows:
 *        (argtpes) => restpe   ==>   scala.Function_n[argtpes, restpe]
 *
 *  (4) Wraps naked case definitions in a match as follows:
 *        { cases }   ==>   (x => x.match {cases}), except when already argument to match
 */
[_trait_] abstract class Parsers: SyntaxAnalyzer {

  import global._;
  import posAssigner.atPos;

  class Parser(unit: CompilationUnit) {

    val in = new Scanner(unit);

    /** the markup parser */
    val xmlp = new MarkupParser(unit, in, Parser.this, true);

    object treeBuilder extends TreeBuilder {
      val global: Parsers.this.global.type = Parsers.this.global;
      def freshName(prefix: String): Name = unit.fresh.newName(prefix);
    }
    import treeBuilder._;

    object symbXMLBuilder extends SymbolicXMLBuilder(treeBuilder, Parser.this, true) { // DEBUG choices
      val global: Parsers.this.global.type = Parsers.this.global;
      def freshName(prefix: String): Name = unit.fresh.newName(prefix);
    }

    /** this is the general parse method
     */
    def parse(): Tree = {
      val t = compilationUnit();
      accept(EOF);
      t
    }

/////// ERROR HANDLING //////////////////////////////////////////////////////

    private def skip(): unit = {
      //System.out.println("<skipping> " + in.token2string(in.token));//DEBUG
      var nparens = 0;
      var nbraces = 0;
      while (true) {
	in.token match {
          case EOF =>
            return;
          case SEMI =>
            if (nparens == 0 && nbraces == 0) return;
          case NEWLINE =>
            if (nparens == 0 && nbraces == 0) return;
          case RPAREN =>
            nparens = nparens - 1;
          case RBRACE =>
            if (nbraces == 0) return;
            nbraces = nbraces - 1;
          case LPAREN =>
            nparens = nparens + 1;
          case LBRACE =>
            nbraces = nbraces + 1;
          case _ =>
	}
	in.nextToken();
      }
    }

    def syntaxError(msg: String, skipIt: boolean): unit =
      syntaxError(in.currentPos, msg, skipIt);

    def syntaxError(pos: int, msg: String, skipIt: boolean): unit = {
      if (pos != in.errpos) {
	unit.error(pos, msg);
	in.errpos = pos;
      }
      if (skipIt) skip();
    }

    def accept(token: int): int = {
      val pos = in.currentPos;
      if (in.token != token)
	syntaxError(
	  if (Position.line(unit.source, in.currentPos) > Position.line(unit.source, in.lastPos)) in.lastPos
          else in.currentPos,
	  in.token2string(token) + " expected but " +
            in.token2string(in.token) + " found.", true);
      if (in.token == token) in.nextToken();
      pos;
    }

    /** SEP = NL | `;'
     *  NL  = `\n' // where allowed
     */
    def acceptStatSep(): unit = if (in.token == NEWLINE) in.nextToken() else accept(SEMI);

    def errorTypeTree = TypeTree().setType(ErrorType).setPos(in.currentPos);
    def errorTermTree = Literal(Constant(null)).setPos(in.currentPos);
    def errorPatternTree = Ident(nme.WILDCARD).setPos(in.currentPos);

/////// TOKEN CLASSES //////////////////////////////////////////////////////

    def isModifier: boolean = in.token match {
      case ABSTRACT | FINAL | SEALED | PRIVATE | PROTECTED | OVERRIDE | IMPLICIT => true
      case _ => false
    }

    def isLocalModifier: boolean = in.token match {
      case ABSTRACT | FINAL | SEALED => true
      case _ => false
    }

    def isDefIntro: boolean = in.token match {
      case VAL | VAR | DEF | TYPE | OBJECT |
           CASEOBJECT | CLASS | CASECLASS | TRAIT => true
      case _ => false
    }

    def isDclIntro: boolean = in.token match {
      case VAL | VAR | DEF | TYPE => true
      case _ => false
    }

    def isExprIntro: boolean = in.token match {
      case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT |
	   STRINGLIT | SYMBOLLIT | TRUE | FALSE | NULL | IDENTIFIER |
	   THIS | SUPER | IF | FOR | NEW | USCORE | TRY | WHILE |
	   DO | RETURN | THROW | LPAREN | LBRACE | XMLSTART => true
      case _ => false
    }

/////// COMMENT AND ATTRIBUTE COLLECTION //////////////////////////////////////

    /** Join the comment associated with a definition
    */
    def joinComment(trees: => List[Tree]): List[Tree] = {
      val buf = in.docBuffer;
      if (buf != null) {
	in.docBuffer = null;
	trees map (t => DocDef(buf.toString(), t) setPos t.pos)
      } else trees
    }

/////// TREE CONSTRUCTION ////////////////////////////////////////////////////

    def scalaDot(name: Name): Tree =
      Select(Ident(nme.scala_), name);
    def scalaAnyRefConstr: Tree =
      scalaDot(nme.AnyRef.toTypeName);
    def scalaScalaObjectConstr: Tree =
      scalaDot(nme.ScalaObject.toTypeName);
    def caseClassConstr: Tree =
      scalaDot(nme.CaseClass.toTypeName);

    /** Convert tree to formal parameter list
    */
    def convertToParams(t: Tree): List[ValDef] = t match {
      case Function(params, TypeTree()) =>
	params
      case Ident(_) | Typed(Ident(_), _) =>
	List(convertToParam(t));
      case Literal(c) if c.tag == UnitTag =>
	Nil
      case _ =>
	syntaxError(t.pos, "malformed formal parameter list", false);
	Nil
    }

    /** Convert tree to formal parameter
    */
    def convertToParam(tree: Tree): ValDef =
      atPos(tree.pos) {
	tree match {
	  case Ident(name) =>
	    ValDef(Modifiers(Flags.PARAM), name, TypeTree(), EmptyTree)
	  case Typed(Ident(name), tpe) =>
	    ValDef(Modifiers(Flags.PARAM), name, tpe, EmptyTree)
	  case _ =>
	    syntaxError(tree.pos, "not a legal formal parameter", false);
	    ValDef(Modifiers(Flags.PARAM), nme.ERROR, errorTypeTree, EmptyTree)
	}
      }

    /** Convert (qual)ident to type identifier
     */
    def convertToTypeId(tree: Tree): Tree = tree match {
      case Ident(name) =>
	Ident(name.toTypeName).setPos(tree.pos)
      case Select(qual, name) =>
	Select(qual, name.toTypeName).setPos(tree.pos)
      case _ =>
        System.out.println(tree);//debug
	syntaxError(tree.pos, "identifier expected", false);
	errorTypeTree
    }

    /** make closure from tree */
    def makeClosure(tree: Tree): Tree = {
      val pname: Name = unit.fresh.newName("x$");
      def insertParam(tree: Tree): Tree = tree match {
	case Ident(name) =>
	  Select(Ident(pname), name)
	case Select(qual, name) =>
	  Select(insertParam(qual), name)
	case Apply(fn, args) =>
	  Apply(insertParam(fn), args)
	case TypeApply(fn, args) =>
	  TypeApply(insertParam(fn), args)
	case _ =>
	  syntaxError(tree.pos, "cannot convert to closure", false);
	  errorTermTree
      }
      Function(
	List(ValDef(Modifiers(Flags.PARAM), pname, TypeTree(), EmptyTree)),
	insertParam(tree))
    }

/////// OPERAND/OPERATOR STACK /////////////////////////////////////////////////

    case class OpInfo(operand: Tree, operator: Name, pos: int);
    var opstack: List[OpInfo] = Nil;

    def precedence(operator: Name): int =
      if (operator eq nme.ERROR) -1
      else {
	val firstCh = operator(0);
	if (((firstCh >= 'A') && (firstCh <= 'Z')) ||
	    ((firstCh >= 'a') && (firstCh <= 'z')))
	  1
	else
	  firstCh match {
	    case '|'             => 2
	    case '^'             => 3
	    case '&'             => 4
	    case '<' | '>'       => 5
	    case '=' | '!'       => 6
	    case ':'             => 7
	    case '+' | '-'       => 8;
	    case '*' | '/' | '%' => 9;
	    case _               => 10;
	  }
      }

    def reduceStack(isExpr: boolean, base: List[OpInfo], top0: Tree, prec: int, leftAssoc: boolean): Tree = {
      var top = top0;
      if (opstack != base &&
	  precedence(opstack.head.operator) == prec &&
	  treeInfo.isLeftAssoc(opstack.head.operator) != leftAssoc) {
	syntaxError(
	  opstack.head.pos,
	  "left- and right-associative operators with same precedence may not be mixed",
	  false);
      }
      while (opstack != base &&
	     (prec < precedence(opstack.head.operator) ||
	      (leftAssoc && prec == precedence(opstack.head.operator)))) {
	top = atPos(opstack.head.pos) {
	  makeBinop(isExpr, opstack.head.operand, opstack.head.operator, top)
	}
	opstack = opstack.tail;
      }
      top
    }

/////// IDENTIFIERS AND LITERALS ////////////////////////////////////////////////////////////

    final val MINUS: Name = "-";
    final val PLUS : Name = "+";
    final val BANG : Name = "!";
    final val TILDE: Name = "~";
    final val STAR : Name = "*";
    final val BAR  : Name = "|";
    final val OPT  : Name = "?";
    final val LT   : Name = "<";

    def ident(): Name =
      if (in.token == IDENTIFIER) {
	val name = in.name.encode;
	in.nextToken();
	name
      } else {
	accept(IDENTIFIER);
	nme.ERROR
      }

    /** StableRef  ::= StableId
     *              |  [Ident `.'] this
     *  SimpleType ::=  StableRef [`.' type]
     */
    def stableRef(thisOK: boolean, typeOK: boolean): Tree = {
      var t: Tree = null;
      if (in.token == THIS) {
	t = atPos(in.skipToken()) { This(nme.EMPTY.toTypeName) }
	if (!thisOK || in.token == DOT)
	  t =  { selectors(t, typeOK, accept(DOT)) }
      } else if (in.token == SUPER) {
	t = atPos(in.skipToken()) {
	  Super(nme.EMPTY.toTypeName, mixinQualifierOpt())
	}
	t = atPos(accept(DOT)) { Select(t, ident()) }
	if (in.token == DOT)
	  t = { selectors(t, typeOK, in.skipToken()) }
      } else {
	val i = atPos(in.currentPos) { Ident(ident()) }
	t = i;
	if (in.token == DOT) {
	  val pos = in.skipToken();
	  if (in.token == THIS) {
	    in.nextToken();
	    t = atPos(i.pos) { This(i.name.toTypeName) }
	    if (!thisOK || in.token == DOT)
	      t = { selectors(t, typeOK, accept(DOT)) }
	  } else if (in.token == SUPER) {
	    in.nextToken();
	    t = atPos(i.pos) { Super(i.name.toTypeName, mixinQualifierOpt()) }
	    t = atPos(accept(DOT)) { Select(t, ident())}
	    if (in.token == DOT)
	      t = { selectors(t, typeOK, in.skipToken()) }
	  } else {
	    t = { selectors(t, typeOK, pos) }
	  }
	}
      }
      t
    }

    def selectors(t: Tree, typeOK: boolean, pos : Int): Tree =
      if (typeOK && in.token == TYPE) {
	in.nextToken();
	atPos(pos) { SingletonTypeTree(t) }
      } else {
	val t1 = atPos(pos) { Select(t, ident()); }
	if (in.token == DOT) { selectors(t1, typeOK, in.skipToken()) }
	else t1
      }

    /** MixinQualifier ::= `[' Id `]'
    */
    def mixinQualifierOpt(): Name =
      if (in.token == LBRACKET) {
	in.nextToken();
	val name = ident().toTypeName;
	accept(RBRACKET);
	name
      } else {
	nme.EMPTY.toTypeName
      }

    /** StableId ::= Id
    *            |  StableRef `.' Id
    *            |  [Id '.'] super [MixinQualifier] ` `.' Id
    */
    def stableId(): Tree =
      stableRef(false, false);

    /** QualId ::= Id {`.' Id}
    */
    def qualId(): Tree = {
      val id = atPos(in.currentPos) { Ident(ident()) }
      if (in.token == DOT) { selectors(id, false, in.skipToken()) }
      else id
    }

    /** SimpleExpr    ::= literal
    *                  | symbol [ArgumentExprs]
    *                  | null
    */
    def literal(isPattern: boolean, isNegated: boolean): Tree = {
      def litToTree() = atPos(in.currentPos) {
	Literal(
	  in.token match {
	    case CHARLIT =>
	      Constant(in.intVal.asInstanceOf[char])
	    case INTLIT =>
	      Constant(in.intVal(isNegated).asInstanceOf[int])
	    case LONGLIT =>
	      Constant(in.intVal(isNegated))
	    case FLOATLIT =>
	      Constant(in.floatVal(isNegated).asInstanceOf[float])
	    case DOUBLELIT =>
	      Constant(in.floatVal(isNegated))
	    case STRINGLIT | SYMBOLLIT =>
	      Constant(in.name.toString())
	    case TRUE =>
	      Constant(true)
	    case FALSE =>
	      Constant(false)
	    case NULL =>
	      Constant(null)
	    case _ =>
	      syntaxError("illegal literal", true);
	      null
	  })
      }

      val isSymLit = in.token == SYMBOLLIT;
      val t = litToTree();
      val pos = in.skipToken();
      if (isSymLit) {
        atPos(pos) {
	  var symid = scalaDot(nme.Symbol);
	  if (isPattern) { symid = convertToTypeId(symid) }
	  Apply(symid, List(t))
        }
      } else {
	t
      }
    }

    def newLineOpt(): unit = if (in.token == NEWLINE) in.nextToken();

//////// TYPES ///////////////////////////////////////////////////////////////

    /** TypedOpt ::= [`:' Type]
    */
    def typedOpt(): Tree =
      if (in.token == COLON) { in.nextToken(); typ() }
      else TypeTree();

    /** RequiresTypedOpt ::= [`:' SimpleType | requires SimpleType]
    */
    def requiresTypeOpt(): Tree =
      if (in.token == COLON | in.token == REQUIRES) { in.nextToken(); simpleType() }
      else TypeTree();

    /** Types ::= Type {`,' Type}
    */
    def types(): List[Tree] = {
      val ts = new ListBuffer[Tree] + typ();
      while (in.token == COMMA) {
	in.nextToken();
	ts += typ();
      }
      ts.toList
    }

    /** Type ::= Type1 `=>' Type
    *         | `(' [Types] `)' `=>' Type
    *         | Type1
    */
    def typ(): Tree = {
      val t =
	if (in.token == LPAREN) {
	  in.nextToken();
	  if (in.token == RPAREN) {
	    in.nextToken();
	    atPos(accept(ARROW)) { makeFunctionTypeTree(List(), typ()) }
	  } else {
	    val t0 = typ();
	    if (in.token == COMMA) {
	      in.nextToken();
	      val ts = new ListBuffer[Tree] + t0 ++ types();
	      accept(RPAREN);
	      atPos (accept(ARROW)) { makeFunctionTypeTree(ts.toList, typ()) }
	    } else {
	      accept(RPAREN); t0
	    }
	  }
	} else {
	  type1()
	}
      if (in.token == ARROW) atPos(in.skipToken()) {
	makeFunctionTypeTree(List(t), typ()) }
      else t
    }

    /** Type1 ::= SimpleType {with SimpleType} [Refinement]
     */
    def type1(): Tree = {
      val pos = in.currentPos;
      var ts = new ListBuffer[Tree] + simpleType();
      while (in.token == WITH) {
	in.nextToken(); ts += simpleType()
      }
      atPos(pos) {
        if (in.token == LBRACE) CompoundTypeTree(Template(ts.toList, refinement()))
        else makeIntersectionTypeTree(ts.toList)
      }
    }

    /** SimpleType ::= SimpleType TypeArgs
     *              | SimpleType `#' Id
     *              | StableId
     *              | StableRef `.' type
     *              | `(' Type `)'
     */
    def simpleType(): Tree = {
      val pos = in.currentPos;
      var t: Tree =
	if (in.token == LPAREN) {
	  in.nextToken();
	  val t = typ();
	  accept(RPAREN);
	  t
	} else {
          val r = stableRef(false, true);
          val x = r match {
            case SingletonTypeTree(_) => r
            case _ => convertToTypeId(r);
          }
	  // System.err.println("SIMPLE_TYPE: " + r.pos + " " + r + " => " + x.pos + " " + x);
	  x;
	}
      while (true) {
	if (in.token == HASH)
	  t = atPos(in.skipToken()) {
	    SelectFromTypeTree(t, ident().toTypeName);
	  }
	else if (in.token == LBRACKET)
	  t = atPos(pos) { AppliedTypeTree(t, typeArgs()) }
	else
	  return t
      }
      null; //dummy
    }

    /** TypeArgs ::= `[' Types `]'
     */
    def typeArgs(): List[Tree] = {
      accept(LBRACKET);
      val ts = types();
      accept(RBRACKET);
      ts
    }

//////// EXPRESSIONS ////////////////////////////////////////////////////////

    /** EqualsExpr ::= `=' Expr
     */
    def equalsExpr(): Tree = {
      accept(EQUALS);
      expr()
    }

    /** Exprs ::= Expr {`,' Expr} [ `:' `_' `*' ]
     */
    def exprs(): List[Tree] = {
      val ts = new ListBuffer[Tree] + expr(true, false);
      while (in.token == COMMA) {
	in.nextToken(); ts += expr(true, false)
      }
      ts.toList
    }

    /** Expr       ::= Bindings `=>' Expr
     *               | Expr1
     *  ResultExpr ::= Bindings `=>' Block
     *               | Expr1
     *  Expr1      ::= if (' Expr `)' [NL] Expr [[`;'] else Expr]
     *               | try `{' block `}' [catch `{' caseClauses `}'] [finally Expr]
     *               | while `(' Expr `)' [NL] Expr
     *               | do Expr [SEP] while `(' Expr `)'
     *               | for (`(' Enumerators `)' | '{' Enumerators '}') [NL] (yield) Expr
     *               | throw Expr
     *               | return [Expr]
     *               | [SimpleExpr `.'] Id `=' Expr
     *               | SimpleExpr ArgumentExprs `=' Expr
     *               | `.' SimpleExpr
     *               | PostfixExpr [`:' Type1]
     *               | PostfixExpr match `{' caseClauses `}'
     *  Bindings   ::= Id [`:' Type1]
     *               | `(' [Binding {`,' Binding}] `)'
     *  Binding    ::= Id [`:' Type]
     */
    def expr(): Tree =
      expr(false, false);

    def expr(isArgument: boolean, isInBlock: boolean): Tree = in.token match {
      case IF =>
        val pos = in.skipToken();
        accept(LPAREN);
        val cond = expr();
        accept(RPAREN);
        newLineOpt();
        val thenp = expr();
        val elsep =
          if (in.token == ELSE) { in.nextToken(); expr() }
          else EmptyTree;
        atPos(pos) { If(cond, thenp, elsep) }
      case TRY =>
        atPos(in.skipToken()) {
          accept(LBRACE);
          val body = block();
          accept(RBRACE);
          val catches =
            if (in.token == CATCH) {
              in.nextToken();
              accept(LBRACE);
              val cases = caseClauses();
              accept(RBRACE);
              cases
            } else List();
          val finalizer =
            if (in.token == FINALLY) { in.nextToken(); expr() }
            else EmptyTree;
          Try(body, catches, finalizer)
        }
      case WHILE =>
        val lname: Name = unit.fresh.newName("label$");
        val pos = in.skipToken();
        accept(LPAREN);
        val cond = expr();
        accept(RPAREN);
        newLineOpt();
        val body = expr();
        atPos(pos) { makeWhile(lname, cond, body) }
      case DO =>
        val lname: Name = unit.fresh.newName("label$");
        val pos = in.skipToken();
        val body = expr();
        if (in.token == SEMI || in.token == NEWLINE) in.nextToken();
        accept(WHILE);
        accept(LPAREN);
        val cond = expr();
        accept(RPAREN);
        atPos(pos) { makeDoWhile(lname, body, cond) }
      case FOR =>
        atPos(in.skipToken()) {
          val startToken = in.token;
          accept(if (startToken == LBRACE) LBRACE else LPAREN);
          val enums = enumerators();
          accept(if (startToken == LBRACE) RBRACE else RPAREN);
          newLineOpt();
          if (in.token == YIELD) {
            in.nextToken(); makeForYield(enums, expr())
          } else makeFor(enums, expr())
        }
      case RETURN =>
        atPos(in.skipToken()) {
          Return(if (isExprIntro) expr() else Literal(()))
        }
      case THROW =>
        atPos(in.skipToken()) {
          Throw(expr())
        }
      case DOT =>
        atPos(in.skipToken()) {
          if (in.token == IDENTIFIER) makeClosure(simpleExpr())
          else { syntaxError("identifier expected", true); errorTermTree }
        }
      case _ =>
        var t = postfixExpr();
        if (in.token == EQUALS) {
          t match {
            case Ident(_) | Select(_, _) | Apply(_, _) =>
              t = atPos(in.skipToken()) { makeAssign(t, expr()) }
            case _ =>
          }
        } else if (in.token == COLON) {
          val pos = in.skipToken();
          if (isArgument && in.token == USCORE) {
            val pos1 = in.skipToken();
            if (in.token == IDENTIFIER && in.name == nme.STAR) {
              in.nextToken();
              t = atPos(pos) {
                Typed(t, atPos(pos1) { Ident(nme.WILDCARD_STAR.toTypeName) })
              }
            } else {
              syntaxError(in.currentPos, "`*' expected", true);
            }
          } else {
            t = atPos(pos) { Typed(t, type1()) }
          }
        } else if (in.token == MATCH) {
          t = atPos(in.skipToken()) {
            accept(LBRACE);
            val cases = caseClauses();
            accept(RBRACE);
            Match(t, cases): Tree
          }
        }
        if (in.token == ARROW) {
          t = atPos(in.skipToken()) {
            Function(convertToParams(t), if (isInBlock) block() else expr())
          }
        }
        t
    }

    /** PostfixExpr   ::= [`.'] InfixExpr [Id]
     *  InfixExpr     ::= PrefixExpr
     *                  | InfixExpr Id InfixExpr
     */
    def postfixExpr(): Tree = {
      val base = opstack;
      var top = prefixExpr();
      while (in.token == IDENTIFIER) {
	top = reduceStack(
	  true, base, top, precedence(in.name), treeInfo.isLeftAssoc(in.name));
	opstack = OpInfo(top, in.name, in.currentPos) :: opstack;
	ident();
	if (isExprIntro) {
	  top = prefixExpr();
	} else {
	  val topinfo = opstack.head;
	  opstack = opstack.tail;
	  return Select(
	    reduceStack(true, base, topinfo.operand, 0, true),
	    topinfo.operator.encode).setPos(topinfo.pos);
	}
      }
      reduceStack(true, base, top, 0, true)
    }

    /** PrefixExpr   ::= [`-' | `+' | `~' | `!'] SimpleExpr
    */
    def prefixExpr(): Tree =
      if (in.token == IDENTIFIER && in.name == MINUS) {
	val name = ident();
	in.token match {
	  case INTLIT | LONGLIT | FLOATLIT | DOUBLELIT => literal(false, true)
	  case _ => atPos(in.currentPos) { Select(simpleExpr(), name) }
	}
      } else if (in.token == IDENTIFIER && (in.name == PLUS || in.name == TILDE || in.name == BANG)) {
	val pos = in.currentPos;
	val name = ident();
	atPos(pos) { Select(simpleExpr(), name) }
      } else {
	simpleExpr()
      }

    /* SimpleExpr    ::= new SimpleType {`(' [Exprs] `)'} {`with' SimpleType} [TemplateBody]
     *                |  SimpleExpr1
     * SimpleExpr1   ::= literal
     *                | xLiteral
     *                | StableRef
     *                | `(' [Expr] `)'
     *                | BlockExpr
     *                | SimpleExpr `.' Id
     *                | SimpleExpr TypeArgs
     *                | SimpleExpr1 ArgumentExprs
     */
    def simpleExpr(): Tree = {
      var t: Tree = null;
      var isNew = false;
      in.token match {
	case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT | STRINGLIT |
	     SYMBOLLIT | TRUE | FALSE | NULL =>
	  t = literal(false, false);
        case XMLSTART =>
          t = xmlp.xLiteral;
          //Console.println("successfully parsed XML at "+t); // DEBUG
	case IDENTIFIER | THIS | SUPER =>
	  t = stableRef(true, false);
	case LPAREN =>
	  val pos = in.skipToken();
	  if (in.token == RPAREN) {
	    in.nextToken();
	    t = Literal(()).setPos(pos);
	  } else {
	    t = expr();
	    if (in.token == COMMA) {
	      val commapos = in.skipToken();
	      val ts = new ListBuffer[Tree] + t ++ exprs();
	      accept(RPAREN);
	      if (in.token == ARROW) {
		t = atPos(pos) {
		  Function(ts.toList map convertToParam, TypeTree())
		}
	      } else {
		syntaxError(commapos, "`)' expected", false);
	      }
	    } else {
	      accept(RPAREN);
	    }
	  }
	case LBRACE =>
	  t = blockExpr()
	case NEW =>
	  t = atPos(in.skipToken()) {
            val parents = new ListBuffer[Tree] + simpleType();
            val argss = new ListBuffer[List[Tree]];
            if (in.token == LPAREN)
	      do { argss += argumentExprs() } while (in.token == LPAREN)
	    else argss += List();
            while (in.token == WITH) {
	      in.nextToken();
	      parents += simpleType()
            }
            val stats = if (in.token == LBRACE) templateBody() else List();
            makeNew(parents.toList, stats, argss.toList)
          }
	  isNew = true
	case _ =>
	  syntaxError("illegal start of simple expression", true);
	  t = errorTermTree
      }
      while (true) {
	in.token match {
	  case DOT =>
	    t = atPos(in.skipToken()) { Select(t, ident()) }
	  case LBRACKET =>
	    t match {
	      case Ident(_) | Select(_, _) =>
		t = atPos(in.currentPos) { TypeApply(t, typeArgs()) }
	      case _ =>
		return t;
	    }
	  case LPAREN | LBRACE if (!isNew) =>
	    t = atPos(in.currentPos) { Apply(t, argumentExprs()) }
	  case _ =>
	    return t
	}
	isNew = false
      }
      null;//dummy
    }

    /** ArgumentExprs ::= `(' [Exprs] `)'
      *                 | BlockExpr
     */
    def argumentExprs(): List[Tree] = {
      if (in.token == LBRACE) {
	List(blockExpr())
      } else {
	accept(LPAREN);
	val ts = if (in.token == RPAREN) List() else exprs();
	accept(RPAREN);
	ts
      }
    }

    /** BlockExpr ::= `{' CaseClauses | Block `}'
     */
    def blockExpr(): Tree = {
      val res = atPos(accept(LBRACE)) {
	if (in.token == CASE) makeVisitor(caseClauses())
        else block()
      }
      accept(RBRACE);
      res
    }

    /** Block ::= BlockStatSeq
    */
    def block(): Tree = makeBlock(blockStatSeq(new ListBuffer[Tree]));

   /** CaseClauses ::= CaseClause {CaseClause}
    */
    def caseClauses(): List[CaseDef] = {
      val ts = new ListBuffer[CaseDef];
      do { ts += caseClause();
      } while (in.token == CASE);
      ts.toList
    }

    /** caseClause : =>= case Pattern [if PostfixExpr] `=>' Block
     */
    def caseClause(): CaseDef =
      atPos(accept(CASE)) {
	val pat = pattern();
	val guard =
	  if (in.token == IF) { in.nextToken(); postfixExpr() }
	  else EmptyTree;
	makeCaseDef(pat, guard, atPos(accept(ARROW))(block()))
      }

    /** Enumerators ::= Generator {SEP Enumerator}
     *  Enumerator  ::= Generator
     *                | Expr
     */
    def enumerators(): List[Tree] = {
      val enums = new ListBuffer[Tree] + generator();
      while (in.token == SEMI || in.token == NEWLINE) {
	in.nextToken();
	enums += (if (in.token == VAL) generator() else expr())
      }
      enums.toList
    }

    /** Generator ::= val Pattern1 `<-' Expr
     */
    def generator(): Tree =
      atPos(accept(VAL)) {
	makeGenerator(pattern1(false), { accept(LARROW); expr() })
      }

//////// PATTERNS ////////////////////////////////////////////////////////////

    /**   Patterns ::= SeqPattern { , SeqPattern }  */
    def patterns(): List[Tree] = {
      val ts = new ListBuffer[Tree];
      ts += pattern(true);
      while (in.token == COMMA) {
	in.nextToken(); ts += pattern(true);
      }
      ts.toList
    }

    /**   Pattern  ::=  Pattern1 { `|' Pattern1 }
     *    SeqPattern ::= SeqPattern1 { `|' SeqPattern1 }
     */
    def pattern(seqOK: boolean): Tree = {
      val pos = in.currentPos;
      val t = pattern1(seqOK);
      if (in.token == IDENTIFIER && in.name == BAR) {
	val ts = new ListBuffer[Tree] + t;
	while (in.token == IDENTIFIER && in.name == BAR) {
          in.nextToken(); ts += pattern1(seqOK);
	}
	atPos(pos) { makeAlternative(ts.toList) }
      } else t
    }

    def pattern(): Tree = pattern(false);

    /**   Pattern1    ::= varid `:' Type1
     *                 |  `_' `:' Type1
     *                 |  Pattern2
     *    SeqPattern1 ::= varid `:' Type1
     *                 |  `_' `:' Type1
     *                 |  [SeqPattern2]
     */
    def pattern1(seqOK: boolean): Tree =
      if (seqOK && !isExprIntro) {
	atPos(in.currentPos) { Sequence(List()) }
      } else {
	val p = pattern2(seqOK);
	p match {
	  case Ident(name) if (treeInfo.isVariableName(name) && in.token == COLON) =>
	    atPos(in.skipToken()) { Typed(p, type1()) }
	  case _ =>
	    p
	}
      }

    /*   Pattern2    ::=  varid [ @ Pattern3 ]
     *                |   Pattern3
     *   SeqPattern2 ::=  varid [ @ SeqPattern3 ]
     *                |   SeqPattern3
     */
    def pattern2(seqOK: boolean): Tree = {
      val p = pattern3(seqOK);
      if (in.token == AT) {
	p match {
          case Ident(name) =>
	    if (name == nme.WILDCARD) {
	      in.nextToken(); pattern3(seqOK)
	    } else if (treeInfo.isVariableName(name)) {
	      atPos(in.skipToken()) { Bind(name, pattern3(seqOK)) }
	    } else {
	      p
	    }
          case _ =>
	    p
	}
      } else p
    }

    /*   Pattern3    ::= SimplePattern
     *                |  SimplePattern {Id SimplePattern}
     *   SeqPattern3 ::= SeqSimplePattern [ '*' | '?' | '+' ]
     *                |  SeqSimplePattern {Id SeqSimplePattern}
     */
    def pattern3(seqOK: boolean): Tree = {
      val base = opstack;
      var top = simplePattern(seqOK);
      if (seqOK && in.token == IDENTIFIER) {
	if (in.name == STAR)
	  return atPos(in.skipToken())(Star(top))
	else if (in.name == PLUS)
          return atPos(in.skipToken())(makePlus(top))
	else if (in.name == OPT)
	  return atPos(in.skipToken())(makeOpt(top))
      }
      while (in.token == IDENTIFIER && in.name != BAR) {
	top = reduceStack(
          false, base, top, precedence(in.name), treeInfo.isLeftAssoc(in.name));
	opstack = OpInfo(top, in.name, in.currentPos) :: opstack;
	ident();
	top = simplePattern(seqOK)
      }
      reduceStack(false, base, top, 0, true)
    }

    /** SimplePattern    ::= varid
     *                    |  `_'
     *                    |  literal
     *                    |  `<' xLiteralPattern
     *                    |  StableId [ `(' Patterns `)' ]
     *                    |  `(' [Pattern] `)'
     *  SimpleSeqPattern ::= varid
     *                    |  `_'
     *                    |  literal
     *                    |  `<' xLiteralPattern
     *                    |  StableId [ `(' Patterns `)' ]
     *                    |  `(' Patterns `)'
     */
    def simplePattern(seqOK: boolean): Tree = in.token match {
      case IDENTIFIER | THIS =>
        var t = stableId();
	in.token match {
	  case INTLIT | LONGLIT | FLOATLIT | DOUBLELIT =>
	    t match {
	      case Ident(name) if name == nme.MINUS =>
		return literal(true, true);
	      case _ =>
	    }
	  case _ =>
	}
        if (in.token == LPAREN) {
	  atPos(in.skipToken()) {
	    val ps = if (in.token == RPAREN) List() else patterns();
	    accept(RPAREN);
	    Apply(convertToTypeId(t), ps)
	  }
        } else t
      case USCORE =>
	atPos(in.skipToken()) { Ident(nme.WILDCARD) }
      case CHARLIT | INTLIT | LONGLIT | FLOATLIT | DOUBLELIT | STRINGLIT | SYMBOLLIT | TRUE | FALSE | NULL =>
	literal(true, false)
      case LPAREN =>
	val pos = in.skipToken();
	val p =
	  if (seqOK) atPos(pos) { makeSequence(patterns()) }
	  else if (in.token != RPAREN) pattern(false);
	  else Literal(()).setPos(pos);
	accept(RPAREN);
	p
      case XMLSTART =>
        val r = xmlp.xLiteralPattern;
        //Console.println("successfully parsed xml pattern "+r); DEBUG
        r
      case _ =>
	syntaxError("illegal start of simple pattern", true);
	errorPatternTree
    }

////////// MODIFIERS ////////////////////////////////////////////////////////////

    /** Modifiers ::= {Modifier}
     *  Modifier  ::= final
     *             | private [ "[" Id "]" ]
     *             | protected
     *             | override
     *             | abstract
     */
    def modifiers(): Modifiers = {
      def loop(mods: int): int = in.token match {
	case ABSTRACT =>
          loop(addMod(mods, Flags.ABSTRACT))
	case FINAL =>
          loop(addMod(mods, Flags.FINAL))
	case SEALED =>
          loop(addMod(mods, Flags.SEALED))
	case PRIVATE =>
          loop(addMod(mods, Flags.PRIVATE))
	case PROTECTED =>
          loop(addMod(mods, Flags.PROTECTED))
	case OVERRIDE =>
          loop(addMod(mods, Flags.OVERRIDE))
	case IMPLICIT =>
          loop(addMod(mods, Flags.IMPLICIT))
	case _ =>
          mods
      }
      var mods = loop(0);
      if ((mods & (Flags.ABSTRACT | Flags.OVERRIDE)) == (Flags.ABSTRACT | Flags.OVERRIDE))
        mods = mods & ~(Flags.ABSTRACT | Flags.OVERRIDE) | Flags.ABSOVERRIDE;
      Modifiers(mods)
    }

    /** LocalClassModifiers ::= {LocalClassModifier}
     *  LocalClassModifier  ::= final
     *                       | private
     */
    def localClassModifiers(): Modifiers = {
      def loop(mods: int): int = in.token match {
	case ABSTRACT =>
          loop(addMod(mods, Flags.ABSTRACT))
	case FINAL =>
          loop(addMod(mods, Flags.FINAL))
	case SEALED =>
          loop(addMod(mods, Flags.SEALED))
	case _ =>
          mods
      }
      Modifiers(loop(0))
    }

    private def addMod(mods: int, mod: int): int = {
      if ((mods & mod) != 0)
	syntaxError(in.currentPos, "repeated modifier", false);
      in.nextToken();
      mods | mod;
    }

//////// PARAMETERS //////////////////////////////////////////////////////////

    /** ParamClauses ::= {`(' [Param {`,' Param}] ')'}
     *                   [`(' implicit Param {`,' Param} `)']
     *  Param ::= Id `:' ParamType
     *  ClassParamClauses ::= {`(' [ClassParam {`' ClassParam}] ')'}
     *                        [`(' implicit ClassParam {`,' ClassParam} `)']
     *  ClassParam ::= [[modifiers] (val | var)] Param
     */
    def paramClauses(owner: Name, implicitViews: List[Tree], ofCaseClass: boolean): List[List[ValDef]] = {
      var implicitmod = 0;
      var caseParam = ofCaseClass;
      def param(): ValDef = {
	atPos(in.currentPos) {
	  var mods = Modifiers(Flags.PARAM);
	  if (owner.isTypeName) {
	    mods = modifiers() | Flags.PARAMACCESSOR;
	    if (in.token == VAL) {
              in.nextToken()
	    } else if (in.token == VAR) {
              mods = mods | Flags.MUTABLE;
              in.nextToken()
            } else {
	      if (mods.flags != Flags.PARAMACCESSOR) accept(VAL);
	      if (!(caseParam)) mods = mods | Flags.PRIVATE | Flags.LOCAL;
	    }
	    if (caseParam) mods = mods | Flags.CASEACCESSOR;
	  }
          val name = ident();
          accept(COLON);
          val bynamemod = if (in.token == ARROW) Flags.BYNAMEPARAM else 0;
          ValDef(mods | implicitmod | bynamemod, name, paramType(), EmptyTree)
	}
      }
      def paramClause(): List[ValDef] = {
	val params = new ListBuffer[ValDef];
	if (in.token != RPAREN) {
	  if (in.token == IMPLICIT) {
	    if (!implicitViews.isEmpty)
	      syntaxError("cannot have both view bounds `<%' and implicit parameters", false);
	    in.nextToken();
	    implicitmod = Flags.IMPLICIT
	  }
          params += param();
          while (in.token == COMMA) {
            in.nextToken(); params += param()
          }
	}
	params.toList
      }
      val vds = new ListBuffer[List[ValDef]];
      val pos = in.currentPos;
      while (implicitmod == 0 && in.token == LPAREN) {
	in.nextToken();
	vds += paramClause();
	accept(RPAREN);
	caseParam = false
      }
      val result = vds.toList;
      if (owner == nme.CONSTRUCTOR &&
	  (result.isEmpty ||
           (!result.head.isEmpty && result.head.head.mods.hasFlag(Flags.IMPLICIT))))
	if (in.token == LBRACKET)
	  syntaxError(pos, "no type parameters allowed here", false);
	else
	  syntaxError(pos, "auxiliary constructor needs non-implicit parameter list", false);
      addImplicitViews(owner, result, implicitViews)
    }

    /** ParamType ::= Type | `=>' Type | Type `*'
     */
    def paramType(): Tree =
      if (in.token == ARROW)
        atPos(in.skipToken()) {
          AppliedTypeTree(
              scalaDot(nme.BYNAME_PARAM_CLASS_NAME.toTypeName), List(typ()))
        }
      else {
        val t = typ();
        if (in.token == IDENTIFIER && in.name == STAR) {
          in.nextToken();
          atPos(t.pos) {
            AppliedTypeTree(
              scalaDot(nme.REPEATED_PARAM_CLASS_NAME.toTypeName), List(t))
          }
        } else t
      }

    /** TypeParamClauseOpt ::= [`[' TypeParam {`,' TypeParam} `]']
     *  TypeParam    ::= [`+' | `-'] FunTypeParam
     *  FunTypeParamClauseOpt ::= [`[' FunTypeParam {`,' FunTypeParam} `]']
     *  FunTypeParam ::= Id TypeBounds
     */
    def typeParamClauseOpt(owner: Name, implicitViews: ListBuffer[Tree]): List[AbsTypeDef] = {
      def typeParam(): AbsTypeDef = {
	var mods = Modifiers(Flags.PARAM);
	if (owner.isTypeName && in.token == IDENTIFIER) {
          if (in.name == PLUS) {
            in.nextToken();
            mods = mods | Flags.COVARIANT;
          } else if (in.name == MINUS) {
            in.nextToken();
            mods = mods | Flags.CONTRAVARIANT;
          }
	}
        val pname = ident();
	val param = atPos(in.currentPos) { typeBounds(mods, pname) }
        if (in.token == VIEWBOUND && (implicitViews != null))
          implicitViews += atPos(in.skipToken()) {
            makeFunctionTypeTree(List(Ident(pname.toTypeName)), typ())
          }
        param
      }
      val params = new ListBuffer[AbsTypeDef];
      if (in.token == LBRACKET) {
        in.nextToken();
        params += typeParam();
        while (in.token == COMMA) {
          in.nextToken();
          params += typeParam();
        }
        accept(RBRACKET);
      }
      params.toList
    }

    /** TypeBounds ::= [`>:' Type] [`<:' Type]
     */
    def typeBounds(mods: Modifiers, name: Name): AbsTypeDef = {
      def bound(tok: int, default: Name): Tree =
        if (in.token == tok) { in.nextToken(); typ() }
        else scalaDot(default.toTypeName);
      AbsTypeDef(mods, name.toTypeName,
                 bound(SUPERTYPE, nme.All),
                 bound(SUBTYPE, nme.Any))
    }

//////// DEFS ////////////////////////////////////////////////////////////////


    /** Import  ::= import ImportExpr {`,' ImportExpr}
     */
    def importClause(): List[Tree] = {
      accept(IMPORT);
      val ts = new ListBuffer[Tree] + importExpr();
      while (in.token == COMMA) {
        in.nextToken(); ts += importExpr();
      }
      ts.toList
    }

    /**  ImportRef ::= StableId `.' (Id | `_' | ImportSelectors)
     */
    def importExpr(): Tree =
      atPos(in.currentPos) {
        var t: Tree = null;
        var pos = 0;
        if (in.token == THIS) {
          t = atPos(in.currentPos) { This(nme.EMPTY.toTypeName) }
          t = atPos(accept(DOT)) { Select(t, ident()) }
          pos = accept(DOT);
        } else {
          val i = atPos(in.currentPos) { Ident(ident()) }
          pos = accept(DOT);
          if (in.token == THIS) {
            in.nextToken();
            t = atPos(i.pos) { This(i.name.toTypeName) }
            t = atPos(accept(DOT)) { Select(t, ident()) }
            pos = accept(DOT);
          } else {
            t = i;
          }
        }
        def loop: Tree =
          if (in.token == USCORE) {
            in.nextToken();
            Import(t, List(Pair(nme.WILDCARD, null)))
          } else if (in.token == LBRACE) {
            Import(t, importSelectors())
          } else {
            val name = ident();
            if (in.token == DOT) {
              t = atPos(pos) { Select(t, name) }
              pos = accept(DOT);
              loop
            } else {
              Import(t, List(Pair(name, name)));
            }
          }
        loop
      }

    /** ImportSelectors ::= `{' {ImportSelector `,'} (ImportSelector | `_') `}'
     */
    def importSelectors(): List[Pair[Name, Name]] = {
      val names = new ListBuffer[Pair[Name, Name]];
      accept(LBRACE);
      var isLast = importSelector(names);
      while (!isLast && in.token == COMMA) {
        in.nextToken();
        isLast = importSelector(names);
      }
      accept(RBRACE);
      names.toList
    }

    /** ImportSelector ::= Id [`=>' Id | `=>' `_']
     */
    def importSelector(names: ListBuffer[Pair[Name, Name]]): boolean =
      if (in.token == USCORE) {
        in.nextToken(); names += Pair(nme.WILDCARD, null); true
      } else {
        val name = ident();
        names += Pair(
          name,
          if (in.token == ARROW) {
            in.nextToken();
            if (in.token == USCORE) { in.nextToken(); nme.WILDCARD } else ident()
          } else {
            name
          });
        false
      }

    /** Def    ::= val PatDef {`,' PatDef}
     *           | var VarDef {`,' VatDef}
     *           | def FunDef {`,' FunDef}
     *           | type TypeDef {`,' TypeDef}
     *           | TmplDef
     *  Dcl    ::= val ValDcl {`,' ValDcl}
     *           | var ValDcl {`,' ValDcl}
     *           | def FunDcl {`,' FunDcl}
     *           | type TypeDcl {`,' TypeDcl}
     */
    def defOrDcl(mods: Modifiers): List[Tree] = {
      in.token match {
        case VAL =>
	  patDefOrDcl(mods);
        case VAR =>
          varDefOrDcl(mods);
        case DEF =>
          List(funDefOrDcl(mods));
        case TYPE =>
          in.nextToken();
          List(typeDefOrDcl(mods))
        case _ =>
          List(tmplDef(mods))
      }
    }

    /** PatDef ::= Pattern2 {`,' Pattern2} [`:' Type] `=' Expr
     *  ValDcl ::= Id {`,' Id} `:' Type
     */
    def patDefOrDcl(mods: Modifiers): List[Tree] = {
      var newmods = mods;
      var lhs = new ListBuffer[Tree];
      do {
	in.nextToken();
	lhs += pattern2(false)
      } while (in.token == COMMA);
      val tp = typedOpt();
      val rhs =
	if (tp.isEmpty || in.token == EQUALS) equalsExpr()
	else {
          newmods = newmods | Flags.DEFERRED;
          EmptyTree
        }
      def mkDefs(p: Tree): List[Tree] = {
        //Console.println("DEBUG: p = "+p.toString()); // DEBUG
	val trees =
	  makePatDef(newmods,
                     if (tp.isEmpty)
                       p
                     else
                       Typed(p, tp),
                     rhs.duplicate) map atPos(p.pos);
	if (rhs == EmptyTree) {
	  trees match {
	    case List(ValDef(_, _, _, EmptyTree)) =>
	    case _ => syntaxError(p.pos, "pattern definition may not be abstract", false);
	  }
	}
	trees
      }
      for (val p <- lhs.toList; val d <- mkDefs(p)) yield d
    }

    /** VarDef ::= Id {`,' Id} [`:' Type] `=' Expr
     *           | Id {`,' Id} `:' Type `=' `_'
     *  VarDcl ::= Id {`,' Id} `:' Type
     */
    def varDefOrDcl(mods: Modifiers): List[Tree] = {
      var newmods = mods | Flags.MUTABLE;
      val lhs = new ListBuffer[Pair[Int, Name]];
      do {
	lhs += Pair(in.skipToken(), ident())
      } while (in.token == COMMA);
      val tp = typedOpt();
      val rhs = if (tp.isEmpty || in.token == EQUALS) {
	accept(EQUALS);
	if (tp != EmptyTree && in.token == USCORE) {
	  in.nextToken();
	  EmptyTree
	} else
	  expr();
      } else {
	newmods = newmods | Flags.DEFERRED;
	EmptyTree
      }
      for (val Pair(pos, name) <- lhs.toList) yield
	atPos(pos) { ValDef(newmods, name, tp.duplicate, rhs.duplicate) }
    }

    /** FunDef ::= FunSig `:' Type `=' Expr
     *           | this ParamClause ParamClauses `=' ConstrExpr
     *  FunDcl ::= FunSig `:' Type
     *  FunSig ::= id [FunTypeParamClause] ParamClauses
     */
    def funDefOrDcl(mods: Modifiers): Tree =
      atPos(in.skipToken()) {
	if (in.token == THIS) {
	  in.nextToken();
	  val vparamss = paramClauses(nme.CONSTRUCTOR, List(), false);
	  accept(EQUALS);
	  DefDef(mods, nme.CONSTRUCTOR, List(), vparamss, TypeTree(), constrExpr())
	} else {
	  var newmods = mods;
	  val name = ident();
          val implicitViews = new ListBuffer[Tree];
	  val tparams = typeParamClauseOpt(name, implicitViews);
	  val vparamss = paramClauses(name, implicitViews.toList, false);
	  val restype = typedOpt();
	  val rhs =
	    if (restype.isEmpty || in.token == EQUALS) equalsExpr();
	    else {
	      newmods = newmods | Flags.DEFERRED;
	      EmptyTree
	    }
	  DefDef(newmods, name, tparams, vparamss, restype, rhs)
	}
      }

    /** ConstrExpr      ::=  SelfInvocation
     *                    |  `{' SelfInvocation {SEP BlockStat} `}'
     *  SelfInvocation  ::= this ArgumentExpr
     */
    def constrExpr(): Tree =
      if (in.token == LBRACE) {
        atPos(in.skipToken()) {
          val statlist = new ListBuffer[Tree];
	  statlist += selfInvocation();
          val stats =
            if (in.token == SEMI || in.token == NEWLINE) { in.nextToken(); blockStatSeq(statlist) }
            else statlist.toList;
          accept(RBRACE);
          makeBlock(stats)
        }
      } else selfInvocation();

    /** SelfInvocation  ::= this ArgumentExprs
     */
    def selfInvocation(): Tree =
      atPos(accept(THIS)) { Apply(Ident(nme.CONSTRUCTOR), argumentExprs()) }

    /** TypeDef ::= Id `=' Type
     *  TypeDcl ::= Id TypeBounds
     */
    def typeDefOrDcl(mods: Modifiers): Tree =
      atPos(in.currentPos) {
        val name = ident().toTypeName;
        in.token match {
          case LBRACKET =>
            val tparams = typeParamClauseOpt(name, null);
            accept(EQUALS);
            AliasTypeDef(mods, name, tparams, typ())
          case EQUALS =>
            in.nextToken();
            AliasTypeDef(mods, name, List(), typ())
          case SUPERTYPE | SUBTYPE | SEMI | NEWLINE | COMMA | RBRACE =>
            typeBounds(mods | Flags.DEFERRED, name)
          case _ =>
            syntaxError("`=', `>:', or `<:' expected", true);
            EmptyTree
        }
      }

      /**  TmplDef ::= ([case] class | trait) ClassDef
       *            | [case] object ObjectDef
       */
      def tmplDef(mods: Modifiers): Tree = in.token match {
        case TRAIT =>
          classDef(mods | Flags.TRAIT | Flags.ABSTRACT);
        case CLASS =>
          classDef(mods);
        case CASECLASS =>
          classDef(mods | Flags.CASE);
        case OBJECT =>
          objectDef(mods);
        case CASEOBJECT =>
          objectDef(mods | Flags.CASE);
        case _ =>
          syntaxError("illegal start of definition", true);
	  EmptyTree
      }

    /** ClassDef ::= ClassSig RequiresTypeOpt ClassTemplate
     *  ClassSig ::= Id [TypeParamClause] {ClassParamClause}
     */
    def classDef(mods: Modifiers): Tree =
      atPos(in.skipToken()) {
	val name = ident().toTypeName;
        val implicitViews = new ListBuffer[Tree];
	val tparams = typeParamClauseOpt(name, implicitViews);
	if (mods.hasFlag(Flags.CASE) && in.token != LPAREN) accept(LPAREN);
	val vparamss = paramClauses(name, implicitViews.toList, mods.hasFlag(Flags.CASE));
	val thistpe = requiresTypeOpt();
	val template = classTemplate(mods, name, vparamss);
	val mods1 = if (mods.hasFlag(Flags.TRAIT) && (template.body forall treeInfo.isInterfaceMember))
                      mods | Flags.INTERFACE
                    else mods;
	ClassDef(mods1, name, tparams, thistpe, template)
      }

    /** ObjectDef       ::= Id ClassTemplate
     */
    def objectDef(mods: Modifiers): Tree =
      atPos(in.skipToken()) {
	val name = ident();
	val template = classTemplate(mods, name, List());
	ModuleDef(mods, name, template)
      }

    /** ClassTemplate ::= [`extends' TemplateParents] [[NL] TemplateBody]
     *  TemplateParents ::= SimpleType {`(' [Exprs] `)'} {`with' SimpleType}
     */
    def classTemplate(mods: Modifiers, name: Name, vparamss: List[List[ValDef]]): Template = {
      val ret = atPos(in.currentPos) {
        val parents = new ListBuffer[Tree];
        val argss = new ListBuffer[List[Tree]];
        if (in.token == EXTENDS) {
          in.nextToken();
	  val parent = simpleType();
	  // System.err.println("classTempl: " + parent);
          parents += parent;
          if (in.token == LPAREN)
	    do { argss += argumentExprs() } while (in.token == LPAREN)
	  else argss += List();
          while (in.token == WITH) {
	    in.nextToken();
	    parents += simpleType()
          }
        } else argss += List();
	if (name != nme.ScalaObject.toTypeName)
          parents += scalaScalaObjectConstr;
	if (mods.hasFlag(Flags.CASE)) parents += caseClassConstr;
        val ps = parents.toList;
        if (in.token == NEWLINE && in.next.token == LBRACE) in.nextToken();
	var body =
	  if (in.token == LBRACE) {
	    templateBody()
	  } else {
	    if (!(in.token == SEMI || in.token == NEWLINE || in.token == COMMA || in.token == RBRACE))
              syntaxError("`extends' or `{' expected", true);
            List()
	  }
	if (!mods.hasFlag(Flags.TRAIT)) Template(ps, vparamss, argss.toList, body)
	else Template(ps, body)
      }
      ret;
    }

////////// TEMPLATES ////////////////////////////////////////////////////////////

    /** TemplateBody ::= `{' [TemplateStat {SEP TemplateStat}] `}'
     */
    def templateBody(): List[Tree] = {
      accept(LBRACE);
      var body = templateStatSeq();
      if (body.isEmpty) body = List(EmptyTree);
      accept(RBRACE);
      body
    }

    /** Refinement ::= `{' [RefineStat {SEP RefineStat}] `}'
     */
    def refinement(): List[Tree] = {
      accept(LBRACE);
      val body = refineStatSeq();
      accept(RBRACE);
      body
    }

/////// STATSEQS //////////////////////////////////////////////////////////////

    /** Packaging ::= package QualId `{' TopStatSeq `}'
     */
    def packaging(): Tree = {
      atPos(accept(PACKAGE)) {
        val pkg = qualId();
        accept(LBRACE);
        val stats = topStatSeq();
        accept(RBRACE);
        makePackaging(pkg, stats)
      }
    }

    /** TopStatSeq ::= [TopStat {SEP TopStat}]
     *  TopStat ::= AttributeClauses Modifiers ClsDef
     *            | Packaging
     *            | Import
     *            |
     */
    def topStatSeq(): List[Tree] = {
      val stats = new ListBuffer[Tree];
      while (in.token != RBRACE && in.token != EOF) {
        if (in.token == PACKAGE) {
          stats += packaging()
        } else if (in.token == IMPORT) {
          stats ++= importClause()
        } else if (in.token == CLASS ||
                   in.token == CASECLASS ||
                   in.token == TRAIT ||
                   in.token == OBJECT ||
                   in.token == CASEOBJECT ||
                   in.token == LBRACKET ||
                   isModifier) {
          val attrs = attributeClauses();
          (stats ++
             joinAttributes(attrs, joinComment(List(tmplDef(modifiers() | traitAttribute(attrs))))))
        } else if (in.token != SEMI && in.token != NEWLINE) {
          syntaxError("illegal start of class or object definition", true);
        }
        if (in.token != RBRACE && in.token != EOF) acceptStatSep();
      }
      stats.toList
    }

    /** TemplateStatSeq  ::= TemplateStat {SEP TemplateStat}
     *  TemplateStat     ::= Import
     *                     | AttributeClauses Modifiers Def
     *                     | AttributeClauses Modifiers Dcl
     *                     | Expr
     *                     |
     */
    def templateStatSeq(): List[Tree] = {
      val stats = new ListBuffer[Tree];
      while (in.token != RBRACE && in.token != EOF) {
        if (in.token == IMPORT) {
          stats ++= importClause()
        } else if (isExprIntro) {
          stats += expr()
        } else if (isDefIntro || isModifier || in.token == LBRACKET) {
          val attrs = attributeClauses();
          (stats ++
             joinAttributes(attrs, joinComment(defOrDcl(modifiers() | traitAttribute(attrs)))))
        } else if (in.token != SEMI && in.token != NEWLINE) {
          syntaxError("illegal start of definition", true);
        }
        if (in.token != RBRACE) acceptStatSep();
      }
      stats.toList
    }

    /** AttributeClauses   ::= {AttributeClause}
     *  AttributeClause    ::= `[' Attribute {`,' Attribute} `]' [NL]
     */
    def attributeClauses(): List[Tree] = {
      var attrs = new ListBuffer[Tree];
      while (in.token == LBRACKET) {
        in.nextToken();
        attrs += attribute();
        while (in.token == COMMA) {
          in.nextToken();
          attrs += attribute()
        }
        accept(RBRACKET);
        newLineOpt();
      }
      attrs.toList
    }

    /** Attribute          ::= StableId [TypeArgs] [`(' [Exprs] `)']
     */
    def attribute(): Tree = {
      val pos = in.currentPos;
      var t: Tree = convertToTypeId(stableId());
      if (in.token == LBRACKET)
        t = atPos(in.currentPos)(AppliedTypeTree(t, typeArgs()));
      val args = if (in.token == LPAREN) argumentExprs() else List();
      atPos(pos) { New(t, List(args)) }
    }

    def traitAttribute(attrs: List[Tree]) = {
      def isTraitAttribute(attr: Tree) = attr match {
        case Apply(Select(New(Ident(name)), constr), List()) if (name.toString() == "_trait_") =>
	  true
	case _ =>
          false
      }
      if (attrs exists isTraitAttribute) Flags.TRAIT else 0
    }

    def joinAttributes(attrs: List[Tree], defs: List[Tree]): List[Tree] =
      defs map (defn =>
        (attrs :\ defn) ((attr, tree) => Attributed(attr, tree) setPos attr.pos));

    /** RefineStatSeq    ::= RefineStat {SEP RefineStat}
     *  RefineStat       ::= Dcl
     *                     | type TypeDef
     *                     |
     */
    def refineStatSeq(): List[Tree] = {
      val stats = new ListBuffer[Tree];
      while (in.token != RBRACE && in.token != EOF) {
        if (isDclIntro) {
          stats ++= joinComment(defOrDcl(NoMods))
        } else if (in.token != SEMI && in.token != NEWLINE) {
          syntaxError("illegal start of declaration", true);
        }
        if (in.token != RBRACE) acceptStatSep();
      }
      stats.toList
    }

    /** BlockStatSeq ::= { BlockStat SEP } [Expr]
     *  BlockStat    ::= Import
     *                 | Def
     *                 | LocalModifiers TmplDef
     *                 | Expr
     *                 |
     */
    def blockStatSeq(stats: ListBuffer[Tree]): List[Tree] = {
      while ((in.token != RBRACE) && (in.token != EOF) && (in.token != CASE)) {
        if (in.token == IMPORT) {
          stats ++= importClause();
          acceptStatSep();
        } else if (isExprIntro) {
          stats += expr(false, true);
          if (in.token != RBRACE && in.token != CASE) acceptStatSep();
        } else if (isDefIntro) {
          stats ++= defOrDcl(NoMods);
          acceptStatSep();
          if (in.token == RBRACE || in.token == CASE) {
            stats += Literal(()).setPos(in.currentPos)
          }
        } else if (isLocalModifier) {
          stats += tmplDef(localClassModifiers());
          acceptStatSep();
          if (in.token == RBRACE || in.token == CASE) {
            stats += Literal(()).setPos(in.currentPos)
          }
        } else if (in.token == SEMI || in.token == NEWLINE) {
          in.nextToken();
        } else {
          syntaxError("illegal start of statement", true);
        }
      }
      stats.toList
    }

    /** CompilationUnit ::= package QualId SEP TopStatSeq
     *                    | package QualId `{' TopStatSeq `}'
     *                    | TopStatSeq
     */
    def compilationUnit(): Tree =
      atPos(in.currentPos) {
        if (in.token == PACKAGE) {
          in.nextToken();
          val pkg = qualId();
          if (in.token == SEMI || in.token == NEWLINE) {
            in.nextToken();
            makePackaging(pkg, topStatSeq())
          } else {
            accept(LBRACE);
            val t =  makePackaging(pkg, topStatSeq());
            accept(RBRACE);
	    if (in.token == SEMI || in.token == NEWLINE) in.nextToken();
	    t
          }
        } else {
          makePackaging(Ident(nme.EMPTY_PACKAGE_NAME), topStatSeq())
        }
      }
  }
}



//  LocalWords:  SOcos
