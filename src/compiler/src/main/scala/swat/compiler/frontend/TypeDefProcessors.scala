package swat.compiler.frontend

import scala.collection.mutable
import swat.compiler.js
import swat.compiler.SwatCompilerPlugin

/**
 * Swat compiler component responsible for compilation of ClassDefs (classes, traits, objects) and everything that
 * can reside inside them. Nested classes are compiled separately, so this component ignores them.
 */
trait TypeDefProcessors {
    self: SwatCompilerPlugin with ScalaAstProcessor =>
    import global._

    /**
     * Result of the ClassDef Swat compilation.
     * @param dependencies Dependencies of the compiled class.
     * @param ast JavaScript tree produced by the compilation.
     */
    case class ProcessedTypeDef(dependencies: List[Dependency], ast: js.Ast)

    /**
     * Dependency of processed ClassDef on another type.
     * @param tpe Either type or type identifier of the dependecy.
     * @param isHard Whether the dependency is hard (its JavaScript code has to be executed before the ClassDef).
     */
    case class Dependency(tpe: Either[String, Type], isHard: Boolean)

    /** A factory for TypeDefProcessors. */
    object TypeDefProcessor {
        def apply(classDef: ClassDef): TypeDefProcessor = {
            val symbol = classDef.symbol
            if (symbol.isAnonymousTotalFunction) {
                new AnonymousFunctionClassProcessor(classDef)
            } else if (symbol.isPackageObjectOrClass) {
                new PackageObjectProcessor(classDef)
            } else if (symbol.isModuleOrModuleClass) {
                new ObjectProcessor(classDef)
            } else if (symbol.isTrait) {
                new TraitProcessor(classDef)
            } else {
                new ClassProcessor(classDef)
            }
        }
    }

    /**
     * A class responsible for transformation of a type definition Scala AST to its JavaScript counterpart. Has a
     * subcalss for each kind of a type definition (class, trait object).
     */
    class TypeDefProcessor(val classDef: ClassDef) {

        val dependencies = mutable.ListBuffer[Dependency]()
        val nestingIsApplied = true

        val thisTypeIdentifier = typeIdentifier(classDef.symbol.tpe)
        val thisTypeJsIdentifier = js.Identifier(thisTypeIdentifier)
        val thisTypeString = js.StringLiteral(thisTypeIdentifier)

        val selfIdent = localJsIdentifier("$self")
        val outerIdent = localJsIdentifier("$outer")
        val fieldsIdent = localJsIdentifier("$fields")
        val constructorIdent = localJsIdentifier("$init$")
        val selectorIdent = localJsIdentifier("$selector")
        val matchErrorIdent = js.Identifier("scala.MatchError")
        val selfDeclaration = js.VariableStatement(selfIdent, Some(js.ThisReference))

        def addDependency(tpe: Either[String, Type], isHard: Boolean) {
            dependencies += Dependency(tpe, isHard)
        }
        def addDeclarationDependency(tpe: Type) { addDependency(Right(tpe), true) }
        def addRuntimeDependency(tpe: Type) { addDependency(Right(tpe), false) }
        def addRuntimeDependency(tpe: String) { addDependency(Left(tpe), false) }

        /**
         * Performs the transformation and returns a JavaScript AST corresponding to the class together with all its
         * dependencies.
         */
        def process: ProcessedTypeDef = {
            // Process the single constructor group and method groups
            val (constructorGroup, methodGroups) = extractDefDefGroups(classDef)
            val constructorDeclaration = processConstructorGroup(constructorGroup).toList
            val methodDeclarations = methodGroups.map(processMethodGroup)

            // Linearized super classes.
            val superClassIdentifiers = mutable.ListBuffer[js.Identifier]()
            classDef.symbol.baseClasses.reverse.foreach { symbol =>
                val identifier = typeJsIdentifier(symbol)
                if (!superClassIdentifiers.contains(identifier)) {
                    superClassIdentifiers.prepend(identifier)
                    addDeclarationDependency(symbol.tpe)
                }
            }

            // Return the result and clear the dependencies so the method is referentially transparent.
            val jsConstructorDeclaration = processJsConstructor(js.ArrayLiteral(superClassIdentifiers.toList))
            val ast = js.Block(constructorDeclaration ++ methodDeclarations :+ jsConstructorDeclaration)
            val result = ProcessedTypeDef(dependencies.toList, ast)
            dependencies.clear()
            result
        }

        def extractDefDefGroups(classDef: ClassDef): (Option[List[DefDef]], List[List[DefDef]]) = {
            val groups = classDef.defDefs.groupBy(_.name.toString).toList.sortBy(_._1).map(_._2)
            val filteredGroups = groups.map(_.filter(!_.hasSymbolWhich(s => s.isOuterAccessor || s.isDeferred)))
            val nonEmptyGroups = filteredGroups.filter(_.nonEmpty)
            val (constructorGroups, methodGroups) = nonEmptyGroups.partition(_.head.symbol.isConstructor)
            (constructorGroups.headOption, methodGroups)
        }

        def processConstructorGroup(constructors: Option[List[DefDef]]): Option[js.Statement] = {
            val constructorExpression = constructors match {
                case Some(List(constructor)) => Some(processConstructor(constructor))
                case Some(cs) => Some(processDefDefGroup(cs, processConstructor))
                case _ => None
            }
            val qualifier = memberChain(thisTypeJsIdentifier, constructorIdent)
            constructorExpression.map(e => js.AssignmentStatement(qualifier, e))
        }

        def processMethodGroup(methods: List[DefDef]): js.Statement = {
            val methodExpression = processDefDefGroup(methods, processDefDef(_: DefDef))
            val qualifier = memberChain(thisTypeJsIdentifier, localJsIdentifier(methods.head.name))
            js.AssignmentStatement(qualifier, methodExpression)
        }

        def processDefDefGroup(defDefs: List[DefDef], defDefProcessor: DefDef => js.Expression): js.Expression = {
            // Each method is processed and a type hint containing types of the method formal parameters is
            // added. E.g. 'Int', function(i) { ... },  'String, String', function(s1, s2) { ... }.
            val overloads = defDefs.flatMap { defDef =>
                val parameterTypes = defDef.vparamss.flatten.map(p => p.tpt.tpe)
                val parameterIdents = parameterTypes.map(typeIdentifier)
                List(js.StringLiteral(parameterIdents.mkString(", ")), defDefProcessor(defDef))
            }

            val methodIdentifier = js.StringLiteral(thisTypeIdentifier + "." + localIdentifier(defDefs.head.name))
            swatMethodCall("method", (methodIdentifier +: overloads): _*)
        }

        def processConstructor(c: DefDef): js.Expression = {
            if (c.symbol.isPrimaryConstructor) {
                processPrimaryConstructor(c)
            } else {
                processDefDef(c)
            }
        }

        def processPrimaryConstructor(c: DefDef): js.Expression = {
            val processedConstructor = processDefDef(c)

            // The self decalaration, super constructor call.
            val bodyStart = processedConstructor.body match {
                case List(s, js.ExpressionStatement(js.UndefinedLiteral)) => {
                    // The body doesn't contain a call to super constructor, so it has to be added.
                    List(s, js.ExpressionStatement(superCall(None, constructorIdent.name, Nil)))
                }
                case b => b
            }

            // Initialization of vals, vars and constructor parameters.
            val fields = classDef.valDefs.filter(!_.symbol.isLazy)
            val parameterIdentifiers = c.vparamss.flatten.map(p => localJsIdentifier(p.symbol.name))
            val fieldInitialization = fields.map { f =>
                val parameter = localJsIdentifier(f.symbol.name)
                val value = processExpressionTree(f.rhs)
                fieldSet(f.symbol, if (parameterIdentifiers.contains(parameter)) parameter else value)
            }

            // Initialization of lazy vals.
            val lazyFieldInitialization = classDef.defDefs.filter(_.symbol.isLazy).map { defDef: DefDef =>
                val value = defDef.rhs match {
                    // Classes and objects have the following structure of lazy val getter.
                    case Block(List(Assign(_, rhs), _*), _) => rhs
                    case _ => defDef.rhs
                }
                fieldSet(defDef.symbol, value)
            }

            // Rest of the constructor body.
            val bodyStatements = processStatementTrees(classDef.impl.body.filter(!_.isDef))
            val body = bodyStart ++ fieldInitialization ++ lazyFieldInitialization ++ bodyStatements
            js.FunctionExpression (None, processedConstructor.parameters, body)
        }

        def processDefDef(defDef: DefDef, firstStatement: Option[js.Statement] = Some(selfDeclaration)) = {
            val processedParameters = defDef.vparamss.flatten.map(p => localJsIdentifier(p.name))
            val processedBody =
                if (defDef.symbol.isGetter && defDef.symbol.isLazy) {
                    // Body of a lazy val (which is assigned to the corresponding field in the primary constructor)
                    // can be replaced by simple return of the field, where the lazy val is stored.
                    js.ReturnStatement(Some(fieldGet(defDef.symbol)))
                } else {
                    processReturnTree(defDef.rhs, defDef.tpt.tpe)
                }
            js.FunctionExpression(None, processedParameters, firstStatement.toList ++ unScoped(processedBody))
        }

        def processJsConstructor(superClasses: js.ArrayLiteral): js.Statement = {
            js.AssignmentStatement(thisTypeJsIdentifier, swatMethodCall("type", thisTypeString, superClasses))
        }

        def symbolToField(qualifier: js.Expression, symbol: Symbol): js.Expression = {
            memberChain(qualifier, fieldsIdent, localJsIdentifier(symbol.name))
        }

        /**
         * The most important transformation method, capable of transformation of any expression-level AST. Based on
         * the AST type it delegates the control to a more specific method.
         */
        def processTree(tree: Tree): js.Ast = tree match {
            case EmptyTree => js.UndefinedLiteral
            case b: Block => processBlock(b)
            case l: Literal => processLiteral(l)
            case t: TypeTree => processTypeTree(t)
            case a: ArrayValue => processArrayValue(a)
            case i: Ident => processIdent(i)
            case t: This => processThis(t.symbol)
            case s: Super => processSuper(s)
            case s: Select => processSelect(s)
            case a: Apply => processApply(a)
            case t: TypeApply => processTypeApply(t)
            case t: Typed => processTyped(t)
            case a: Assign => processAssign(a)
            case v: ValDef => processLocalValDef(v)
            case d: DefDef => processLocalDefDef(d)
            case c: ClassDef => processLocalClassDef(c)
            case i: If => processIf(i)
            case l: LabelDef => processLabelDef(l)
            case m: Match => processMatch(m)
            case t: Throw => processThrow(t)
            case t: Try => processTry(t)
            case _ => {
                error("Unknown Scala construct %s: %s".format(tree.getClass, tree.toString))
                js.UndefinedLiteral
            }
        }

        /**
         * Processes the specified AST and makes sure that the returned JavaScript AST is a statement. When it is an
         * expression, it wraps it with the [[swat.compiler.js.ExpressionStatement]] class.
         */
        def processStatementTree(tree: Tree): js.Statement = processTree(tree) match {
            case s: js.Statement => s
            case e: js.Expression => astToStatement(e)
            case _ => {
                error(s"A non-statement tree found on a statement position ($tree)")
                js.Block(Nil)
            }
        }

        /**
         * Processes the specified AST and makes sure that the returned JavaScript AST is an expression. Otherwise
         * an error is reported.
         */
        def processExpressionTree(tree: Tree): js.Expression = processTree(tree) match {
            case e: js.Expression => e
            case _ => {
                error(s"A non-expression tree found on an expression position ($tree)")
                js.UndefinedLiteral
            }
        }

        def processReturnTree(tree: Tree, returnTpe: Type = null): js.Statement = {
            // If the type of the tree is Unit, then the tree appears on the return position of an expression, which
            // actually doesn't return anything. So the 'return' may be omitted.
            if (Option(returnTpe).getOrElse(tree.tpe).isUnit) {
                tree match {
                    // If the tree is a Block with structure { statement; (); } then the block that wraps the statement
                    // may be omitted. The scope protects from shadowing and using the shadowed value instead of the
                    // original value. However it's not possible to shadow and use a variable in one statement, which
                    // isn't itself scoped. The purpose is to get rid of unnecessary scoping.
                    case Block(statement :: Nil, Literal(Constant(_: Unit))) => processStatementTree(statement)
                    case _ => processStatementTree(tree)
                }
            } else {
                js.ReturnStatement(Some(processExpressionTree(tree)))
            }
        }

        def processStatementTrees(trees: List[Tree]): List[js.Statement] = trees.map(processStatementTree)

        def processExpressionTrees(trees: List[Tree]): List[js.Expression] = trees.map(processExpressionTree)

        /**
         * Processes the specified block. Takes care of scoping, throws away empty blocks and makes sure something is
         * not scoped twice.
         */
        def processBlock(block: Block): js.Ast = block match {
            case Block(List(c: ClassDef), _) if c.symbol.isAnonymousTotalFunction => processLocalClassDef(c)
            case b => b.toMatchBlock match {
                case Some(m: MatchBlock) => processMatchBlock(m)
                case _ => scoped {
                    val processedExpr = processReturnTree(b.expr)

                    // If the block contains just the expr, then the expr doesn't have to be scoped, because there
                    // isn't anything to protect from shadowing in the block (the stats are empty)
                    val unScopedExpr = if (b.stats.isEmpty) unScoped(processedExpr) else List(processedExpr)
                    processStatementTrees(b.stats) ++ unScopedExpr
                }
            }
        }

        def processLiteral(literal: Literal): js.Expression = literal.value.value match {
            case () => js.UndefinedLiteral
            case null => js.NullLiteral
            case b: Boolean => js.BooleanLiteral(b)
            case c: Char => js.StringLiteral(c.toString)
            case s: String => js.StringLiteral(s)
            case b: Byte => js.NumericLiteral(b)
            case s: Short => js.NumericLiteral(s)
            case i: Int => js.NumericLiteral(i)
            case l: Long => js.NumericLiteral(l)
            case f: Float => js.NumericLiteral(f)
            case d: Double => js.NumericLiteral(d)
            case ErrorType => js.UndefinedLiteral
            case t: Type => {
                addRuntimeDependency(t)
                swatMethodCall("classOf", typeJsIdentifier(t))
            }
            case l => {
                error(s"Unexpected type of a literal ($l)")
                js.UndefinedLiteral
            }
        }

        def processTypeTree(typeTree: TypeTree): js.Expression = {
            val tpe = typeTree.tpe.underlying
            addRuntimeDependency(tpe)
            typeJsIdentifier(tpe)
        }

        def processArray(values: List[Tree]): js.Expression = js.ArrayLiteral(processExpressionTrees(values))

        def processArrayValue(arrayValue: ArrayValue): js.Expression = processArray(arrayValue.elems)

        def processIdent(identifier: Ident): js.Expression = {
            if (identifier.symbol.isModule) {
                addRuntimeDependency(identifier.tpe)
                objectAccessor(identifier.symbol)
            } else {
                localJsIdentifier(identifier.name)
            }
        }

        def processThis(thisSymbol: Symbol): js.Expression = {
            if (thisSymbol.isPackageClass) {
                packageJsIdentifier(thisSymbol)
            } else {
                getInnerDepth(thisSymbol, classDef.symbol) match {
                    case Some(d) => iteratedMemberChain(selfIdent, outerIdent, d)
                    case None => objectAccessor(thisSymbol)
                }
            }
        }

        def getInnerDepth(outer: Symbol, inner: Symbol): Option[Int] = {
            if (outer == NoSymbol) {
                None
            } else if (outer == inner || inner == NoSymbol) {
                Some(0)
            } else {
                val innerOwnerDepth = getInnerDepth(outer, inner.owner)
                if (!inner.isClass || inner.isAnonymousTotalFunction) {
                    innerOwnerDepth
                } else {
                    innerOwnerDepth.map(_ + 1)
                }
            }
        }

        def processSuper(s: Super): js.Expression = {
            error("Unsupported super AST: " + s.toString)
            js.UndefinedLiteral
        }

        def processSelect(select: Select): js.Expression = {
            lazy val processedSelect =
                memberChain(processExpressionTree(select.qualifier), localJsIdentifier(select.name))

            select.symbol match {
                case s if s.isObject => {
                    addRuntimeDependency(s.tpe)
                    objectAccessor(s)
                }

                // A method invocation without the corresponding apply.
                case m: MethodSymbol => js.CallExpression(processedSelect, Nil)
                case s if s.isField => fieldGet(s)
                case _ => processedSelect
            }
        }

        /**
         * Processes an Apply AST. May return both Expression or Statement depending on the application type, because
         * some applications lead to an assignment statement.
         */
        def processApply(apply: Apply) = apply.fun match {
            // Outer field accessor
            case s: Select if s.symbol.isOuterAccessor => fieldGet(s.symbol)

            // Generic method call.
            case TypeApply(f, typeArgs) => {
                val types = typeArgs.filterNot(_.symbol.isTypeParameterOrSkolem)
                types.foreach(a => addRuntimeDependency(a.tpe))
                processCall(f, apply.args ++ types)
            }

            // Constructor call.
            case Select(n: New, _) => processNew(apply, n)

            // A local object constructor call can be omitted because every object access is via an accessor.
            case f if f.symbol.isModule => {
                addRuntimeDependency(apply.tpe)
                objectAccessor(apply.tpe.typeSymbol)
            }

            // Native JavaScript code.
            case Select(q, n) if q.hasSymbolWhich(_.fullName == "swat.js.package") && n.toString == "native" => {
                processJsNative(apply)
            }

            // An application on an adapter.
            case s: Select if s.qualifier.tpe.typeSymbol.isAdapter => processAdapterApply(s, apply.args)

            // Remote method call.
            case s: Select if s.symbol.isRemoteMethod => processRemoteCall(s, apply.args)

            // Method call.
            case f => processCall(f, apply.args)
        }

        def processJsNative(apply: Apply) = apply.args.head match {
            case l: Literal => js.RawCodeExpression(l.value.value.toString)
            case _ => {
                error(s"The js.native argument has to be a string literal (not ${apply.args.head}}).")
                js.UndefinedLiteral
            }
        }

        def processAdapterApply(method: Select, args: List[Tree]) = {
            val symbol = method.symbol
            if (symbol.isAccessor) {
                val fieldName = localJsIdentifier(method.name.toString.stripSuffix("_$eq"))
                val field = js.MemberExpression(processExpressionTree(method.qualifier), fieldName)
                if (symbol.isGetter) {
                    field
                } else {
                    js.AssignmentStatement(field, processExpressionTree(args.head))
                }
            } else if (symbol.isApplyMethod) {
                functionCall(method.qualifier, args)
            } else {
                val methodExpr = memberChain(processExpressionTree(method.qualifier), localJsIdentifier(method.name))
                functionCall(methodExpr, args)
            }
        }

        def processRemoteCall(method: Select, args: List[Tree]): js.Expression = {
            val fullName = method.symbol.fullNameString
            val resultType = method.tpe.resultType

            // Verify that the method returns a Future.
            if (resultType <:< typeOf[scala.concurrent.Future[_]]) {
                addRuntimeDependency(resultType.typeArgs.head)
                addRuntimeDependency("rpc.RpcProxy$")

                val processedArgs =
                    if (args.nonEmpty) {
                        val tupleTypeName = s"scala.Tuple${args.length}"
                        addRuntimeDependency(tupleTypeName)
                        newObject(js.RawCodeExpression(tupleTypeName), processExpressionTrees(args))
                    } else {
                        js.NullLiteral
                    }

                swatMethodCall("invokeRemote", js.StringLiteral(fullName), processedArgs)
            } else {
                error(s"A remote method $fullName must return a scala.concurrent.Future.")
                js.UndefinedLiteral
            }
        }

        def processCall(method: Tree, args: List[Tree]): js.Expression = method match {
            // A local function call doesn't need the type hint, because it can't be overloaded.
            case f if f.symbol.isLocal => functionCall(f, args)

            // Methods on types that compile to JavaScript primitive types.
            case s @ Select(q, _) if q.tpe.isPrimitiveOrString => processPrimitiveOrStringMethodCall(s.symbol, q, args)

            // Standard methods of the Any class.
            case s @ Select(q, _) if s.symbol.isAnyMethodOrOperator => processAnyMethodCall(s.symbol, q, args)

            // Methods of functions.
            case s @ Select(q, _) if q.tpe.isFunction => processFunctionMethodCall(s.symbol, q, args)

            // Methods of the current class super classes.
            case s @ Select(Super(t: This, mixName), methodName) if t.symbol.tpe =:= classDef.symbol.tpe => {
                val arguments = processMethodArgs(s.symbol, args)
                val mix = if (mixName.isEmpty) None else Some(typeIdentifier(s.symbol.owner.tpe))
                superCall(mix, localIdentifier(methodName), arguments)
            }

            // Overloaded constructor call.
            case s @ Select(q, n) if s.symbol.isConstructor => {
                val methodName = js.StringLiteral(localIdentifier(n))
                val arguments = js.ArrayLiteral(processMethodArgs(s.symbol, args))
                swatMethodCall("invokeThis", List(selfIdent, methodName, arguments, thisTypeString): _*)
            }

            // Method call.
            case s @ Select(q, n) => {
                val methodAccessor = memberChain(processExpressionTree(q), localJsIdentifier(n))
                val processedArgs = processMethodArgs(s.symbol, args)
                js.CallExpression(methodAccessor, processedArgs)
            }

            // Getter with the corresponding apply.
            case i: Ident if i.symbol.isGetter => fieldGet(i.symbol)
        }

        def functionCall(function: Tree, args: List[Tree]): js.Expression = {
            functionCall(processExpressionTree(function), args)
        }
        def functionCall(function: js.Expression, args: List[Tree]): js.Expression = {
            js.CallExpression(function, processExpressionTrees(args))
        }

        def createTypeHint(types: List[Type], alwaysNonEmpty: Boolean = false): Option[js.StringLiteral] = {
            types.map(typeIdentifier) match {
                case Nil if !alwaysNonEmpty => None
                case identifiers => Some(js.StringLiteral(identifiers.mkString(", ")))
            }
        }

        def processMethodArgs(method: Symbol, args: List[Tree]): List[js.Expression] =  {
            val typeHint = createTypeHint(method.info.paramTypes, args.exists(_.isType))
            processExpressionTrees(args) ++ typeHint
        }

        def dispatchCallToCompanion(method: Symbol, qualifier: Tree, args: List[Tree]): js.Expression = {
            val companion = qualifier.tpe.companionSymbol
            addRuntimeDependency(companion.tpe)

            val processedArgs = processExpressionTree(qualifier) +: processExpressionTrees(args)
            val typeHint = createTypeHint(method.owner.tpe :: method.info.paramTypes)
            methodCall(objectAccessor(companion), localJsIdentifier(method.name), (processedArgs ++ typeHint): _*)
        }

        def processPrimitiveOrStringMethodCall(method: Symbol, qualifier: Tree, args: List[Tree]): js.Expression = {
            if (method.isPrimitiveOrStringOperator || method.isEqualityOperator) {
                processAnyValOrStringOperator(method, qualifier, args.headOption)
            } else {
                // Primitive values in JavaScript aren't objects, so methods can't be invoked on them. It's possible
                // to convert a primitive value to an object wrapper (e.g. Number), however these wrappers would have
                // to be extended so they'd provide all methods of Scala primitive values. Consequently, integration
                // of existing libraries would be problematic, if the Scala version of the method overriden something
                // that was defined there by the library. The philosophy of Swat is not to interfere with the
                // environment in any way. So the primitive value methods are defined on the companion objects instead.
                // For example Scala code '123.toDouble' produces JavaScript code 'scala.Int.toDouble(3)'.
                //
                // Pros and cons of native object extensions can be found here:
                // http://perfectionkills.com/extending-built-in-native-objects-evil-or-no
                if (method.isAnyMethodOrOperator) {
                    processAnyMethodCall(method, qualifier, args)
                } else {
                    dispatchCallToCompanion(method, qualifier, args)
                }
            }
        }

        def processAnyValOrStringOperator(symbol: Symbol, operand1: Tree, operand2: Option[Tree]): js.Expression = {
            // Convert the Scala operator name to JavaScript operator. Luckily, all are the same as in Scala.
            val operator = processOperator(symbol.nameString.stripPrefix("unary_"))

            // Chars, that are represented as strings, need to be explicitly converted to integers, so arithmetic
            // operations would work on them.
            def processOperand(operand: Tree): js.Expression = {
                val processedOperand = processExpressionTree(operand)
                if (!symbol.isEqualityOperator && operand.tpe.isChar) {
                    val charCompanion = typeOf[Char].companionSymbol
                    addRuntimeDependency(charCompanion.tpe)
                    val typeHint = js.StringLiteral("scala.Char")
                    methodCall(objectAccessor(charCompanion), localJsIdentifier("toInt"), processedOperand, typeHint)
                } else {
                    processedOperand
                }
            }

            val expr = operand2.map { o2 =>
                js.InfixExpression(processOperand(operand1), operator, processOperand(o2))
            }.getOrElse {
                js.PrefixExpression(operator, processOperand(operand1))
            }

            operator match {
                case "/" if operand1.tpe.isIntegralVal => {
                    // All numbers are represented as doubles, so even if they're integral, their division can yield a
                    // double. E.g. 3 / 2 == 1.5. To ensure the same behavior as in Scala, division results have to be
                    // floored in case that the first operand is of integral type.
                    methodCall(js.Identifier("Math"), js.Identifier("floor"), expr)
                }
                case "&" | "|" | "^" if symbol.isBooleanValOperator => {
                    // The long-circuited logical operations aren't directly supported in JavaScript. But if they're
                    // used on booleans, then the operands are converted to numbers. A result of the corresponding
                    // bitwise is therefore also a number, which has to be converted back to a boolean.
                    js.CallExpression(js.Identifier("Boolean"), List(expr))
                }
                case _ => expr
            }
        }

        def processAnyMethodCall(method: Symbol, qualifier: Tree, args: List[Tree]): js.Expression = {
            lazy val processedQualifier = processExpressionTree(qualifier)
            if (method.isEqualityOperator) {
                val processedOperand2 = processExpressionTree(args.head)
                val equalityExpr = swatMethodCall(localIdentifier("equals"), processedQualifier, processedOperand2)
                method.nameString match {
                    case "==" | "equals" => equalityExpr
                    case "!=" => js.PrefixExpression("!", equalityExpr)
                    case o => js.InfixExpression(processedQualifier, processOperator(o), processedOperand2)
                }
            } else if (method.name.endsWith("InstanceOf") && args.nonEmpty && args.last.symbol.isRefinementClass) {
                // Refinement type conversions can be ignored.
                processedQualifier
            } else {
                val methodName = method.nameString.replace("##", "hashCode")
                val processedArgs = processedQualifier +: processExpressionTrees(args)
                swatMethodCall(localIdentifier(methodName), processedArgs: _*)
            }
        }

        def processFunctionMethodCall(method: Symbol, qualifier: Tree, args: List[Tree]): js.Expression = {
            if (method.isApplyMethod) {
                functionCall(qualifier, args)
            } else {
                dispatchCallToCompanion(method, qualifier, args)
            }
        }

        def processTypeApply(typeApply: TypeApply): js.Expression = {
            // The methods where the type actually matters (e.g. isInstanceOf) are processed earlier. In other cases
            // the type application may be omitted.
            processExpressionTree(typeApply.fun)
        }

        def processTyped(typed: Typed): js.Expression = {
            if (typed.expr.tpe.underlying <:< typed.tpt.tpe.underlying) {
                // No type cast is necessary since it's already proven that the expr is of the specified type.
                processExpressionTree(typed.expr)
            } else {
                error(s"Unexpected typed expression ($typed)")
                js.UndefinedLiteral
            }
        }

        def processAssign(assign: Assign): js.Statement = {
            js.AssignmentStatement(processExpressionTree(assign.lhs), processExpressionTree(assign.rhs))
        }

        def processLocalValDef(valDef: ValDef): js.Statement = valDef.symbol match {
            // A val definition associated with a lazy val can be omitted as the value will be stored in the
            // corresponding function (see processLocalDefDef method).
            case s if s.isLazy && s.name.endsWith("$lzy") => js.EmptyStatement

            // The val definition associated with a local object can be omitted.
            case s if s.isModuleVar => js.EmptyStatement

            case _ => js.VariableStatement(localJsIdentifier(valDef.name), Some(processExpressionTree(valDef.rhs)))
        }

        def processLazyVal(defDef: DefDef): js.Statement = defDef.rhs match {
            case Block(List(Assign(_, rhs)), _) => {
                js.VariableStatement(localJsIdentifier(defDef.name), Some(lazify(rhs)))
            }
            case _ => {
                error("Unexpected lazy val initializer (%s)".format(defDef.rhs))
                js.EmptyStatement
            }
        }

        def processLocalDefDef(defDef: DefDef): js.Statement = {
            if (defDef.symbol.isModule) {
                js.EmptyStatement
            } else if (defDef.symbol.isLazy) {
                processLazyVal(defDef)
            } else {
                // Check whether the function is nested in a local function with the same name which isn't supported.
                def checkNameDuplicity(symbol: Symbol) {
                    if (symbol.isLocal && symbol.isMethod) {
                        if (symbol.name == defDef.symbol.name) {
                            error(s"Nested local functions with same names aren't supported ($defDef).")
                        }
                        checkNameDuplicity(symbol.owner)
                    }
                }
                checkNameDuplicity(defDef.symbol.owner)

                js.VariableStatement(localJsIdentifier(defDef.name), Some(processDefDef(defDef, None)))
            }
        }

        def processLocalClassDef(classDef: ClassDef): js.Ast = {
            // Process the class def and transitively depend on its dependencies.
            val processedClassDef = processTypeDef(classDef)
            dependencies ++= processedClassDef.dependencies

            processedClassDef.ast match {
                case e: js.Expression => e
                case s: js.Statement => {
                    val declaration = js.VariableStatement(typeJsIdentifier(classDef.symbol), Some(js.ObjectLiteral()))
                    js.Block(List(declaration, s))
                }
            }
        }

        def processNew(apply: Apply, n: New): js.Expression = {
            val tpe = n.tpe.underlying
            addRuntimeDependency(tpe)

            val constructors = tpe.members.toList.filter(c => c.isConstructor && c.owner == tpe.typeSymbol)
            val args =
                if (constructors.length > 1) {
                    // If the created class has more than one constructor, then a type hint has to be added.
                    processMethodArgs(apply.fun.symbol, apply.args)
                } else {
                    processExpressionTrees(apply.args)
                }
            newObject(typeJsIdentifier(n.tpe.underlying), args)
        }

        def processIf(condition: If): js.Expression = scoped {
            js.IfStatement(
                processExpressionTree(condition.cond),
                unScoped(processReturnTree(condition.thenp)),
                unScoped(processReturnTree(condition.elsep)))
        }

        def processLabelDef(labelDef: LabelDef): js.Expression = {
            labelDef.toLoop match {
                case Some(l: Loop) => processLoop(l)
                case _ => {
                    error(s"Unexpected type of a label ($labelDef)")
                    js.UndefinedLiteral
                }
            }
        }

        def processLoop(loop: Loop): js.Expression = {
            // Because the whole loop is scoped, the stats may be unscoped (double scoping isn't necessary). As a
            // consequence, all top level return statements have to be omitted. Otherwise it'd terminate the loop.
            val processedStats = processStatementTrees(loop.stats).flatMap(unScoped).map {
                case js.ReturnStatement(Some(e)) => js.ExpressionStatement(e)
                case s => s
            }

            scoped {
                js.WhileStatement(processExpressionTree(loop.expr), processedStats, loop.isDoWhile)
            }
        }

        def processMatchBlock(matchBlock: MatchBlock): js.Expression = {
            val processedInit = processStatementTrees(matchBlock.init)
            val processedCases = matchBlock.cases.map { c =>
                val body = unScoped(processReturnTree(c.rhs))
                js.FunctionDeclaration(localJsIdentifier(c.name), c.params.map(i => localJsIdentifier(i.name)), body)
            }

            val firstCaseIdentifier = localJsIdentifier(matchBlock.cases.head.name)
            val matchResult = js.ReturnStatement(Some(js.CallExpression(firstCaseIdentifier, Nil)))

            scoped {
                processedInit ++ processedCases :+ matchResult
            }
        }

        def processMatch(m: Match): js.Expression = {
            val selectorAssignment = js.AssignmentStatement(selectorIdent, processExpressionTree(m.selector))
            val processedCases = m.cases.flatMap(c => processCaseDef(c, selectorIdent))
            val matchErrorThrow = throwNew(matchErrorIdent, List(selectorIdent))
            scoped {
                selectorAssignment +: processedCases :+ matchErrorThrow
            }
        }

        def processThrow(t: Throw): js.Expression = scoped {
            js.ThrowStatement(processExpressionTree(t.expr))
        }

        def processTry(t: Try): js.Expression = t match {
            case Try(b, Nil, EmptyTree) => processExpressionTree(b)
            case _ => {
                val processedBody = unScoped(processReturnTree(t.block))
                val processedCatches = t.catches match {
                    case Nil => None

                    // If the cases contain some more advanced patterns than simple type check, wildcard or bind, then
                    // the patmat transforms the cases the same way as it transforms the match expression and wraps the
                    // match into one case. Therefore we don't have take care of exception rethrowing (in case of
                    // unsuccessful match) since it's already done in the case produced by patmat.
                    case List(CaseDef(Bind(matcheeName, _), _, b: Block)) if b.toMatchBlock.nonEmpty => {
                        Some((localJsIdentifier(matcheeName), unScoped(processReturnTree(b))))
                    }
                    case catches => {
                        val exception = freshLocalJsIdentifier("e")
                        val body = catches.flatMap(processCaseDef(_, exception)) :+ js.ThrowStatement(exception)
                        Some((exception, body))
                    }
                }
                val processedFinalizer =
                    if (t.finalizer == EmptyTree) None else Some(unScoped(processStatementTree(t.finalizer)))

                scoped {
                    js.TryStatement(processedBody, processedCatches, processedFinalizer)
                }
            }
        }

        def processCaseDef(caseDef: CaseDef, matchee: js.Expression): List[js.Statement] = {
            // The body is terminated with the return statement, so even if the body doesn't return anything, the
            // matching process is terminated.
            val processedBody = unScoped(processReturnTree(caseDef.body)) :+ js.ReturnStatement(None)
            val guardedBody = caseDef.guard match {
                case EmptyTree => processedBody
                case guard => List(js.IfStatement(processExpressionTree(guard), processedBody, Nil))
            }

            processPattern(caseDef.pat, matchee, guardedBody)
        }

        def processPattern(p: Tree, matchee: js.Expression, body: List[js.Statement]): List[js.Statement] = p match {
            case i: Ident => processIdentifierPattern(i, matchee, body)
            case l: Literal => processLiteralPattern(l, matchee, body)
            case t: Typed => processTypedPattern(t, matchee, body)
            case b: Bind => processBindPattern(b, matchee, body)
            case pattern => {
                error(s"Unexpected type of a pattern ($pattern).")
                Nil
            }
        }

        def processIdentifierPattern(identifier: Ident, matchee: js.Expression, body: List[js.Statement]) = {
            if (identifier.name != nme.WILDCARD) {
                error(s"Unexpected type of an identifier pattern ($identifier).")
            }
            body
        }

        def processLiteralPattern(literal: Literal, matchee: js.Expression, body: List[js.Statement]) = {
            val condition = swatMethodCall("equals", matchee, processExpressionTree(literal))
            List(js.IfStatement(condition, body, Nil))
        }

        def processTypedPattern(typed: Typed, matchee: js.Expression, body: List[js.Statement]) = {
            addRuntimeDependency(typed.tpt.tpe)
            val processedTypedArgs = List(matchee, typeJsIdentifier(typed.tpt.tpe))
            val condition = swatMethodCall(localIdentifier("isInstanceOf"), processedTypedArgs: _*)
            List(js.IfStatement(condition, body, Nil))
        }

        def processBindPattern(bind: Bind, matchee: js.Expression, body: List[js.Statement]) = {
            val binding = js.VariableStatement(localJsIdentifier(bind.name), Some(matchee))
            processPattern(bind.body, matchee, binding +: body)
        }

        def processOperator(operator: String): String = {
            Map(
                "equals" -> "===",
                "==" -> "===",
                "!=" -> "!==",
                "eq" -> "===",
                "ne" -> "!=="
            ).withDefault(o => o)(operator)
        }

        def lazify(expr: Tree): js.Expression = {
            swatMethodCall("lazify", js.FunctionExpression(None, Nil, unScoped(processReturnTree(expr))))
        }

        def superCall(mixName: Option[String], methodName: String, args: List[js.Expression]): js.Expression = {
            val method = js.StringLiteral(methodName)
            val arguments = js.ArrayLiteral(args)
            val typeHints = thisTypeString :: mixName.map(js.StringLiteral).toList
            swatMethodCall("invokeSuper", (selfIdent :: method :: arguments :: typeHints): _*)
        }

        def fieldGet(field: Symbol): js.Expression = {
            if (field.isOuterAccessor) {
                if (nestingIsApplied) {
                    memberChain(selfIdent, outerIdent)
                } else {
                    selfIdent
                }
            } else if (field.isParametricField) {
                val name = js.StringLiteral(localIdentifier(field.name))
                swatMethodCall("getParameter", selfIdent, name, thisTypeString)
            } else {
                // Val, var or lazy val.
                val value = symbolToField(selfIdent, field)
                if (field.isLazy) js.CallExpression(value, Nil) else value
            }
        }

        def fieldSet(field: Symbol, value: Tree): js.Statement = {
            fieldSet(field, if (field.isLazy) lazify(value) else processExpressionTree(value))
        }

        def fieldSet(field: Symbol, value: js.Expression): js.Statement = {
            if (field.isOuterAccessor) {
                js.AssignmentStatement(memberChain(selfIdent, outerIdent), value)
            } else if (field.isParametricField) {
                val name = js.StringLiteral(localIdentifier(field.name))
                js.ExpressionStatement(swatMethodCall("setParameter", selfIdent, name, value, thisTypeString))
            } else {
                // Val, var or lazy val.
                js.AssignmentStatement(symbolToField(selfIdent, field), value)
            }
        }
    }

    private class ClassProcessor(c: ClassDef) extends TypeDefProcessor(c)

    private class TraitProcessor(c: ClassDef) extends TypeDefProcessor(c)

    private class ObjectProcessor(c: ClassDef) extends TypeDefProcessor(c) {
        override def processJsConstructor(superClasses: js.ArrayLiteral): js.Statement = {
            // A local object depends on the outer class so a reference to the outer class has to be passed to the
            // constructor.
            val commonArgs = List(thisTypeString, superClasses)
            val args = commonArgs ++ (if (classDef.symbol.isLocalOrAnonymous) List(selfIdent) else Nil)
            js.AssignmentStatement(thisTypeJsIdentifier, swatMethodCall("object", args: _*))
        }
    }

    private class PackageObjectProcessor(c: ClassDef) extends ObjectProcessor(c)

    private class AnonymousFunctionClassProcessor(c: ClassDef) extends TypeDefProcessor(c) {
        override val nestingIsApplied = false

        override def process: ProcessedTypeDef = {
            val applyDefDef = c.defDefs.filter(_.symbol.isApplyMethod).head
            val arity = applyDefDef.vparamss.flatten.length
            val processedApply = processDefDef(applyDefDef, None)
            val ast = swatMethodCall("func", js.NumericLiteral(arity), processedApply)
            addRuntimeDependency(s"scala.Function$arity")
            ProcessedTypeDef(dependencies.toList, ast)
        }
    }
}
