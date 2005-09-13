/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$
package scala.tools.nsc.typechecker;

import symtab.Flags._;
import collection.mutable.HashMap;
import transform.InfoTransform;

/** Post-attribution checking and transformation.
 *  //todo: check whether we always check type parameter bounds.
 *
 *  This phase performs the following checks.
 *
 *   - All overrides conform to rules.
 *   - All type arguments conform to bounds.
 *   - All type variable uses conform to variance annotations.
 *   - No forward reference to a term symbol extends beyond a value definition.
 *
 *  It performs the following transformations.
 *
 *   - Local modules are replaced by variables and classes
 *   - caseArity, caseElement implementations added to case classes
 *   - equals, and hashCode and toString methods are added to case classes,
 *       unless they are defined in the class or a baseclass
 *       different from java.lang.Object
 *   - toString method is added to case objects,
 *       unless they are defined in the class or a baseclass
 *       different from java.lang.Object
 *   - Calls to case factory methods are replaced by new's.
 *   - References to parameter accessors with aliases are replaced by super references to
 *     these aliases.
 */
abstract class RefChecks extends InfoTransform {

  import global._;
  import definitions._;
  import typer.{typed, typedOperator, atOwner};
  import posAssigner.atPos;

  /** the following two members override abstract members in Transform */
  val phaseName: String = "refchecks";
  def newTransformer(unit: CompilationUnit): Transformer = new RefCheckTransformer(unit);

  def transformInfo(sym: Symbol, tp: Type): Type = {
    if (sym.isModule && !sym.isStatic) {
      sym setFlag (METHOD | STABLE);
      PolyType(List(), tp)
    } else tp
  }

  // var m$: T = null; or, if class member: local var m$: T = _;
  def newModuleVarDef(accessor: Symbol) = {
    val mvar = accessor.owner.newVariable(accessor.pos, nme.moduleVarName(accessor.name))
      setInfo accessor.tpe.finalResultType;
    if (mvar.owner.isClass) {
      mvar setFlag (PRIVATE | LOCAL | SYNTHETIC);
      mvar.owner.info.decls.enter(mvar);
    }
    ValDef(mvar, if (mvar.owner.isClass) EmptyTree else Literal(Constant(null)))
  }

  // def m: T = { if (m$ == null) m$ = new m$class; m$ }
  def newModuleAccessDef(accessor: Symbol, mvar: Symbol) =
    DefDef(accessor, vparamss =>
      Block(
	List(
	  If(
	    Apply(Select(Ident(mvar), nme.eq), List(Literal(Constant(null)))),
	    Assign(Ident(mvar),
                   New(TypeTree(mvar.tpe),
                       List(for (val pt <- mvar.tpe.symbol.primaryConstructor.info.paramTypes)
                            yield This(accessor.owner.enclClass)))),//???
	    EmptyTree)),
	Ident(mvar)));

  class RefCheckTransformer(unit: CompilationUnit) extends Transformer {

// Override checking ------------------------------------------------------------

    /** 1. Check all members of class `clazz' for overriding conditions.
     *  That is for overriding member M and overridden member O:
     *
     *    1.1. M must have the same or stronger access privileges as O.
     *    1.2. O must not be final.
     *    1.3. O is deferred, or M has `override' modifier.
     *    1.4. If O is an immutable value, then so is M.
     *    1.5. Neither M nor O are a parameterized type alias
     *    1.6. If O is a type alias, then M is an alias of O.
     *    1.7. If O is an abstract type then
     *         either M is an abstract type, and M's bounds are sharper than O's bounds.
     *         or M is an unparameterized type alias or class which conforms to O's bounds.
     *    1.8. If O and M are values, then M's type is a subtype of O's type.
     *  2. Check that only abstract classes have deferred members
     *  3. Check that every member with an `override' modifier
     *     overrides some other member.
     */
    private def checkAllOverrides(clazz: Symbol): unit = {

      val self = clazz.thisType;

      def infoString(sym: Symbol) =
	sym.toString() +
	(if (sym.owner == clazz) ""
	 else (sym.locationString +
	       (if (sym.isAliasType) ", which equals " + self.memberInfo(sym)
		else if (sym.isAbstractType) " with bounds " +  self.memberInfo(sym)
		else if (sym.isTerm) " of type " + self.memberInfo(sym)
		else "")));

      /* Check that all conditions for overriding `other' by `member' are met. */
      def checkOverride(clazz: Symbol, member: Symbol, other: Symbol): unit = {
	val pos = if (member.owner == clazz) member.pos else clazz.pos;

	def overrideError(msg: String): unit =
	  if (other.tpe != ErrorType && member.tpe != ErrorType)
	    unit.error(pos, "error overriding " + infoString(other) +
		       ";\n " + infoString(member) + " " + msg);

	def overrideTypeError(): unit = {
	  if (other.tpe != ErrorType && member.tpe != ErrorType) {
	    overrideError("has incompatible type");
	    explainTypes(member.tpe, other.tpe);
	  }
	}

	//System.out.println(infoString(member) + " overrides " + infoString(other) + " in " + clazz);//DEBUG

	// return if we already checked this combination elsewhere
	if (member.owner != clazz) {
	  if ((member.owner isSubClass other.owner) &&
	      ((member hasFlag DEFERRED) || !(other hasFlag DEFERRED))) {
		//System.out.println(infoString(member) + " shadows1 " + infoString(other) " in " + clazz);//DEBUG
		return;
	      }
	  if (clazz.info.parents exists (parent =>
	    (parent.symbol isSubClass other.owner) && (parent.symbol isSubClass member.owner) &&
	    ((member hasFlag DEFERRED) || !(other hasFlag DEFERRED)))) {
	      //System.out.println(infoString(member) + " shadows2 " + infoString(other) + " in " + clazz);//DEBUG
		return;
	    }
	  if (clazz.info.parents forall (parent =>
	    (parent.symbol isSubClass other.owner) == (parent.symbol isSubClass member.owner))) {
	      //System.out.println(infoString(member) + " shadows " + infoString(other) + " in " + clazz);//DEBUG
	      return;
	    }
	}

	if (member hasFlag PRIVATE) { // (1.1)
	  overrideError("has weaker access privileges; it should not be private");
	} else if ((member hasFlag PROTECTED) && !(other hasFlag PROTECTED)) { // 1
	  overrideError("has weaker access privileges; it should not be protected");
	} else if (other hasFlag FINAL) { // (1.2)
	  overrideError("cannot override final member");
	} else if (!(other hasFlag DEFERRED) && !(member hasFlag (OVERRIDE | ABSOVERRIDE))) { // (1.3)
	  overrideError("needs `override' modifier");
	} else if (other.isStable && !member.isStable) { // (1.4)
	  overrideError("needs to be an immutable value");
	} else {
	  if (other.isAliasType) {
	    if (!member.typeParams.isEmpty) // (1.5)
	      overrideError("may not be parameterized");
	    if (!other.typeParams.isEmpty) // (1.5)
	      overrideError("may not override parameterized type");
	    if (!(self.memberType(member) =:= self.memberType(other))) // (1.6)
	      overrideTypeError();
	  } else if (other.isAbstractType) {
	    if (!member.typeParams.isEmpty) // (1.7)
	      overrideError("may not be parameterized");
	    if (!(self.memberInfo(other).bounds containsType self.memberInfo(member))) // (1.7)
	      overrideTypeError();
	  } else if (other.isTerm) {
	    if (!(self.memberInfo(member) <:< (self.memberInfo(other)))) // 8
	      overrideTypeError();
	  }
	}
      }

      // 1. Check all members for overriding conditions.
      for (val bc <- clazz.info.baseClasses.tail; val other <- bc.info.decls.toList)
	if (!other.isClass && !(other hasFlag PRIVATE) && !other.isConstructor) {
	  val member = clazz.tpe.member(other.name) filter
	    (sym => sym.owner != other.owner &&
             (sym.isType || (self.memberType(sym) matches self.memberType(other))));
	  if (member hasFlag OVERLOADED) {
	    val alt1 = member.alternatives.head;
	    val alt2 = member.alternatives.tail.head;
	    val pos = if (alt1.owner == clazz) alt1.pos
		      else if (alt2.owner == clazz) alt2.pos
		      else clazz.pos;
	    unit.error(pos,
	      "ambiguous override: both " + infoString(alt1) +
	      "\n and " + infoString(alt2) +
	      "\n override " + infoString(other));
	  } else if (member != NoSymbol && !(member hasFlag LOCAL)) {
	    checkOverride(clazz, member, other);
	  }
	}

      // 2. Check that only abstract classes have deferred members
      if (clazz.isClass && !(clazz hasFlag ABSTRACT)) {
	def abstractClassError(msg: String): unit = {
	  unit.error(clazz.pos,
	    (if (clazz.isAnonymousClass || clazz.isModuleClass) "object creation impossible"
	     else clazz.toString() + " needs to be abstract") + ", since " + msg);
	  clazz.setFlag(ABSTRACT);
	}
	for (val member <- clazz.tpe.members)
	  if (member hasFlag DEFERRED) {
	    abstractClassError(
	      infoString(member) + " is not defined" +
	      (if (member.isVariable)
		"\n(Note that variables need to be initialized to be defined)" else ""))
	  } else if (member.isIncompleteIn(clazz)) {
	    val other = member.superSymbol(clazz);
	    abstractClassError(
	      infoString(member) + " is marked `abstract' and `override'" +
	      (if (other != NoSymbol)
		" and overrides incomplete superclass member " + infoString(other)
	       else ""))
	  }
      }

      // 3. Check that every defined member with an `override' modifier overrides some other member.
      for (val member <- clazz.info.decls.toList)
	if ((member hasFlag (OVERRIDE | ABSOVERRIDE)) &&
	    (clazz.info.baseClasses.tail forall (bc => member.overriddenSymbol(bc) == NoSymbol))) {
	  System.out.println(clazz.info.baseClasses.tail);//debug
	  unit.error(member.pos, member.toString() + " overrides nothing");
	  member resetFlag OVERRIDE
	}
    }

  // Basetype Checking --------------------------------------------------------

    /** 1. Check that later type instances in the base-type sequence
     *     are subtypes of earlier type instances of the same trait.
     *  2. Check that case classes do not inherit from case classes.
     *  3. Check that at most one base type is a case-class.
     */
    private def validateBaseTypes(clazz: Symbol): unit = {
      val seenTypes = new Array[Type](clazz.info.closure.length);
      var seenCaseClass = if (clazz hasFlag CASE) clazz else NoSymbol;

      def validateTypes(tps: List[Type], includeSuper: boolean): unit = {
	if (!tps.isEmpty) {
	  for (val tp <- tps.tail.reverse) validateType(tp, false);
	  if (includeSuper) validateType(tps.head, true);
	}
      }

      def validateType(tp: Type, includeSuper: boolean): unit = {
	val baseClass = tp.symbol;
	if (baseClass.isClass) {
	  val index = clazz.info.closurePos(baseClass);
	  if (index >= 0) {
	    if (seenTypes(index) != null && !(seenTypes(index) <:< tp))
	      unit.error(clazz.pos, "illegal inheritance;\n " + clazz +
			 " inherits different type instances of " + baseClass +
			 ":\n" + tp + " and " + seenTypes(index));
	    seenTypes(index) = tp;
	    // check that case classes do not inherit from case classes
	    if (baseClass hasFlag CASE) {
	      if (seenCaseClass != NoSymbol && seenCaseClass != baseClass)
		unit.error(clazz.pos, "illegal combination of case " +
			   seenCaseClass + " and case " + baseClass + " in one object");
	      seenCaseClass = baseClass
	    }
	  }
	  validateTypes(tp.parents, includeSuper);
	}
      }

      validateTypes(clazz.info.parents, true);
    }

  // Variance Checking --------------------------------------------------------

    private val ContraVariance = -1;
    private val NoVariance = 0;
    private val CoVariance = 1;
    private val AnyVariance = 2;

    /** Check variance of type variables in this type
     */
    private def validateVariance(base: Symbol, all: Type, variance: int): unit = {

      def varianceString(variance: int): String =
	if (variance == 1) "covariant"
	else if (variance == -1) "contravariant"
	else "invariant";

      def relativeVariance(tvar: Symbol): int = {
	val clazz = tvar.owner;
	var sym = base;
	var state = CoVariance;
	while (sym != clazz && state != AnyVariance) {
	  //System.out.println("flip: " + sym + " " + sym.isParameter());//DEBUG
	  if ((sym hasFlag PARAM) && !sym.owner.isConstructor) state = -state;
	  else if (!sym.owner.isClass) state = AnyVariance;
	  else if (sym.isAliasType) state = NoVariance;
	  sym = sym.owner;
	}
	state
      }

      def validateVariance(tp: Type, variance: int): unit = tp match {
	case ErrorType => ;
	case WildcardType => ;
	case NoType => ;
	case NoPrefix => ;
	case ThisType(_) => ;
	case ConstantType(_) => ;
	case SingleType(pre, sym) =>
	  validateVariance(pre, variance)
	case TypeRef(pre, sym, args) =>
	  if (sym.variance != NoVariance) {
	    val v = relativeVariance(sym);
	    if (v != AnyVariance && sym.variance != v * variance) {
	      //System.out.println("relativeVariance(" + base + "," + sym + ") = " + v);//DEBUG
	      unit.error(base.pos,
			 varianceString(sym.variance) + " " + sym +
			 " occurs in " + varianceString(v * variance) +
			 " position in type " + all + " of " + base);
	    }
	  }
	  validateVariance(pre, variance);
	  validateVarianceArgs(args, variance, sym.typeParams);
	case ClassInfoType(parents, decls, symbol) =>
	  validateVariances(parents, variance);
	case RefinedType(parents, decls) =>
	  validateVariances(parents, variance);
	case TypeBounds(lo, hi) =>
	  validateVariance(lo, -variance);
	  validateVariance(hi, variance);
	case MethodType(formals, result) =>
	  validateVariance(result, variance);
	case PolyType(tparams, result) =>
	  validateVariance(result, variance);
      }

      def validateVariances(tps: List[Type], variance: int): unit =
	tps foreach (tp => validateVariance(tp, variance));

      def validateVarianceArgs(tps: List[Type], variance: int, tparams: List[Symbol]): unit =
	(tps zip tparams) foreach {
	  case Pair(tp, tparam) => validateVariance(tp, variance * tparam.variance)
	}

      validateVariance(all, variance)
    }

// Forward reference checking ---------------------------------------------------

    class LevelInfo(val outer: LevelInfo) {
      val scope: Scope = if (outer == null) new Scope() else new Scope(outer.scope);
      var maxindex: int = Integer.MIN_VALUE;
      var refpos: int = _;
      var refsym: Symbol = _;
    }

    private var currentLevel: LevelInfo = null;
    private val symIndex = new HashMap[Symbol, int];

    private def pushLevel(): unit =
      currentLevel = new LevelInfo(currentLevel);

    private def popLevel(): unit =
      currentLevel = currentLevel.outer;

    private def enterSyms(stats: List[Tree]): unit = {
      var index = -1;
      for (val stat <- stats) {
	index = index + 1;
	stat match {
          case ClassDef(_, _, _, _, _) | DefDef(_, _, _, _, _, _) if (stat.symbol.isLocal) =>
	    currentLevel.scope.enter(newScopeEntry(stat.symbol, currentLevel.scope));
	    symIndex(stat.symbol) = index;
          case _ =>
	}
      }
    }

    private def enterReference(pos: int, sym: Symbol): unit =
      if (sym.isLocal) {
	val e = currentLevel.scope.lookupEntry(sym.name);
	if (e != null && sym == e.sym) {
          var l = currentLevel;
          while (l.scope != e.owner) l = l.outer;
	  val symindex = symIndex(sym);
	  if (l.maxindex < symindex) {
	    l.refpos = pos;
	    l.refsym = sym;
	    l.maxindex = symindex;
	  }
	}
      }

// Transformation ------------------------------------------------------------

    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] = {
      pushLevel();
      enterSyms(stats);
      var index = -1;
      val stats1 = stats flatMap { stat => index = index + 1; transformStat(stat, index) }
      popLevel();
      stats1
    }

    def transformStat(tree: Tree, index: int): List[Tree] = tree match {
      case ModuleDef(mods, name, impl) =>
	val sym = tree.symbol;
        val localTyper = typer.atOwner(currentOwner);
	val cdef = ClassDef(mods | MODULE, name, List(), EmptyTree, impl)
	  setPos tree.pos
          setSymbol sym.moduleClass
          setType NoType;
	if (sym.isStatic) List(transform(cdef))
	else {
          val vdef =
            localTyper.typed {
              atPos(tree.pos) {
                newModuleVarDef(sym)
              }
            }

          val ddef =
	    atPhase(phase.next) {
	      localTyper.typed {
                newModuleAccessDef(sym, vdef.symbol)
              }
            }

          transformTrees(List(cdef, vdef, ddef))
	}

      case ValDef(_, _, _, _) =>
	val tree1 = transform(tree); // important to do before forward reference check
	//todo: handle variables
	if (tree.symbol.isLocal && index <= currentLevel.maxindex) {
	  if (settings.debug.value) System.out.println(currentLevel.refsym);
	  unit.error(currentLevel.refpos, "forward reference extends over definition of " + tree.symbol);
	}
	List(tree1)

      case Import(_, _) =>
	List()

      case _ =>
	List(transform(tree))
    }

    override def transform(tree: Tree): Tree = try {

      /* Convert a reference of a case factory to a new of the class it produces. */
      def toConstructor: Tree = {
	var tpe = tree.tpe;
	while (!tpe.symbol.isClass) tpe = tpe.resultType;
	assert(tpe.symbol hasFlag CASE);
	typedOperator(atPos(tree.pos)(Select(New(TypeTree(tpe)), tpe.symbol.primaryConstructor)));
      }

      /* Check whether argument types conform to bounds of type parameters */
      def checkBounds(tparams: List[Symbol], argtps: List[Type]): unit = try {
	infer.checkBounds(tree.pos, tparams, argtps, "");
      } catch {
	case ex: TypeError => unit.error(tree.pos, ex.getMessage());
      }

      val sym = tree.symbol;
      var result = tree;
      tree match {
	case ClassDef(mods, name, tparams, tpe, impl) =>
	  validateVariance(sym, sym.info, CoVariance);
	  validateVariance(sym, sym.typeOfThis, CoVariance);

	case DefDef(_, _, _, _, _, _) =>
	  validateVariance(sym, sym.tpe, CoVariance);

	case ValDef(_, _, _, _) =>
	  validateVariance(sym, sym.tpe, if (sym.isVariable) NoVariance else CoVariance);

	case AbsTypeDef(_, _, _, _) =>
	  validateVariance(sym, sym.info, CoVariance);

	case AliasTypeDef(_, _, _, _) =>
	  validateVariance(sym, sym.info, CoVariance);

	case Template(_, _) =>
	  validateBaseTypes(currentOwner);
	  checkAllOverrides(currentOwner);

	case TypeTree() =>
	  new TypeTraverser {
	    def traverse(tp: Type) = tp match {
	      case TypeRef(pre, sym, args) => checkBounds(sym.typeParams, args); this
	      case _ => this
	    }
	  } traverse tree.tpe

	case TypeApply(fn, args) =>
	  checkBounds(fn.tpe.typeParams, args map (.tpe));
	  if (sym.isSourceMethod && sym.hasFlag(CASE)) result = toConstructor;

	case New(tpt) =>
	  enterReference(tree.pos, tpt.tpe.symbol);

	case Ident(name) =>
	  if (sym.isSourceMethod && sym.hasFlag(CASE))
	    result = toConstructor
	  else if (name != nme.WILDCARD && name != nme.WILDCARD_STAR.toTypeName) {
	    assert(sym != NoSymbol, tree);//debug
	    enterReference(tree.pos, sym);
	  }

	case Select(qual, name) =>
	  if (sym.isSourceMethod && sym.hasFlag(CASE))
	    result = toConstructor
	  else qual match {
	    case Super(qualifier, mixin) =>
              val base = currentOwner.enclClass;
              if (sym hasFlag DEFERRED) {
	        val member = sym.overridingSymbol(base);
	        if (mixin != nme.EMPTY.toTypeName || member == NoSymbol ||
		    !((member hasFlag ABSOVERRIDE) && member.isIncompleteIn(base)))
		  unit.error(tree.pos, "symbol accessed from super may not be abstract");
              }
              //System.out.println("super: " + tree + " in " + base);//DEBUG
              if (base.isTrait && mixin == nme.EMPTY.toTypeName) {
                val superAccName = nme.superName(sym.name);
	        val superAcc = base.info.decl(superAccName) suchThat (.alias.==(sym));
	        assert(superAcc != NoSymbol, "" + sym + " " + base + " " + superAccName);//debug
                val tree1 = Select(This(base), superAcc);
                if (settings.debug.value) log("super-replacement: " + tree + "=>" + tree1);
                result = atPos(tree.pos) {
                  Select(gen.This(base), superAcc) setType superAcc.tpe
                }
	      }
            case This(_) =>
	      if ((sym hasFlag PARAMACCESSOR) && (sym.alias != NoSymbol)) {
                result = typed {
                  Select(
                    Super(qual.symbol, qual.symbol.info.parents.head.symbol.name) setPos qual.pos,
                    sym.alias) setPos tree.pos
                }
		if (settings.debug.value)
		  System.out.println("alias replacement: " + tree + " ==> " + result);//debug
              }
            case _ =>
          }
	case _ =>
      }
      super.transform(result)
    } catch {
      case ex: TypeError =>
	if (settings.debug.value) ex.printStackTrace();
	unit.error(tree.pos, ex.getMessage());
	tree
    }
  }
}
