package swat.compiler.frontend

import swat.compiler.{TypeOutput, SwatCompilerPlugin, js}

/**
 * Swat compiler component responsible for compilation of top level Scala ASTs (compilation units, packages, classes).
 */
trait ScalaAstProcessor extends js.TreeBuilder with RichTrees with TypeDefProcessors {
    self: SwatCompilerPlugin =>
    import global._

    /**
     * A set of packags that are either stripped from the compiled JavaScript code or renamed. For example a class
     * swat.client.foo.bar.A is compiled to foo.bar.A. The main purpose is an easy integration of existing libraries
     * into the client code without naming collisions. This can be seen on the swat.scala package where altered
     * versions of Scala Library classes may be defined. Advantage is, that in the compiled code, they seem like they
     * were declared in the scala package.
     */
    val packageAliases = Map(
        "<root>" -> "",
        "<empty>" -> "",
        "swat.java" -> "java",
        "swat.scala" -> "scala",
        "swat.client" -> ""
    )

    /**
     * A set of packages whose classes and objects are considered to be adapters even though they don't necessarily
     * need to be annotated with the [[swat.adapter]] annotation.
     */
    val adapterPackages = Set("swat.js")

    /** Processes a body of a compilation unit. Entry point of the Swat frontend. */
    def processUnitBody(body: Tree): List[TypeOutput] = body match {
        case p: PackageDef => {
            extractTypeDefs(p).map { typeDef =>
                val typeSymbol = typeDef.symbol
                val typeDefType = typeSymbol.tpe.underlying
                val classIdent = typeIdentifier(typeSymbol)
                val processedTypeDef = processTypeDef(typeDef)
                val processedProvide = processProvide(typeDefType)
                val processedDependencies = processDependencies(processedTypeDef.dependencies, classIdent)
                val processedAst = astToStatement(processedTypeDef.ast)
                val program = js.Program(processedProvide +: processedDependencies :+ processedAst)
                val code = backend.astToCode(program)
                TypeOutput(classIdent, code, program)
            }
        }
        case _ => Nil
    }

    /** Returns all type definitions inside the specified AST. */
    def extractTypeDefs(tree: Tree): List[ClassDef] = tree match {
        case p: PackageDef => p.stats.flatMap(extractTypeDefs)
        case c: ClassDef if c.symbol.isCompiled => c :: c.impl.body.flatMap(extractTypeDefs)
        case _ => Nil
    }

    /** Processes a type definition. */
    def processTypeDef(classDef: ClassDef): ProcessedTypeDef = {
        TypeDefProcessor(classDef).process
    }

    def processProvide(dependencyType: Type): js.Statement = {
        js.ExpressionStatement(swatMethodCall("provide", js.StringLiteral(typeIdentifier(dependencyType))))
    }

    def processDependencies(dependencies: Seq[Dependency], excludedTypeIdent: String): List[js.Statement] = {
        // Filter the dependencies so only relevant are included and map the first components to type identifiers.
        val filtered = dependencies.flatMap {
            case Dependency(Left(identifier), isHard) => Some(identifier, isHard)
            case Dependency(Right(tpe), isHard) => {
                val symbol = tpe.typeSymbol
                if (!symbol.isAdapter && !symbol.isAnonymousClass && !symbol.isRefinementClass) {
                    Some(typeIdentifier(tpe), isHard)
                } else {
                    None
                }
            }
        }

        // Group them by type identifiers and for each dependent type, use the strongest dependency
        // (i.e. declaration dependency).
        val grouped = filtered.groupBy(_._1) - excludedTypeIdent
        val strongest = grouped.mapValues(_.map(_._2).reduce(_ || _)).toList.sortBy(_._1)

        // Produce the swat.require statements.
        strongest.map { case (typeIdentifier, isHard) =>
            val expr = swatMethodCall("require", js.StringLiteral(typeIdentifier), js.BooleanLiteral(isHard))
            js.ExpressionStatement(expr)
        }
    }

    def objectAccessor(objectSymbol: Symbol): js.Expression = {
        if (objectSymbol.isAdapter) {
            typeJsIdentifier(objectSymbol)
        } else {
            js.CallExpression(typeJsIdentifier(objectSymbol), Nil)
        }
    }

    def swatMethodCall(methodName: String, args: js.Expression*): js.Expression = {
        methodCall(localJsIdentifier("swat"), localJsIdentifier(methodName), args: _*)
    }

    /** Returns an identifier corresponding to the specified local variable. */
    def localIdentifier(name: String): String = {
        val cleanName = name.replace(" ", "").replace("<", "$").replace(">", "$")
        (if (js.Language.keywords(cleanName)) "$" else "") + cleanName
    }
    def localIdentifier(name: Name): String = localIdentifier(name.toString)
    def localJsIdentifier(name: Name): js.Identifier = localJsIdentifier(name.toString)
    def localJsIdentifier(name: String): js.Identifier = js.Identifier(localIdentifier(name))

    private var counter = 0
    def freshLocalJsIdentifier(prefix: String) = {
        counter += 1
        js.Identifier(prefix + "$" + counter)
    }

    /** Returns an identifier corresponding to the specified package. */
    def packageIdentifier(packageSymbol: Symbol): String = {
        val name = packageSymbol.fullName
        if (adapterPackages(name)) {
            ""
        } else if (packageAliases.contains(name)) {
            packageAliases(name)
        } else {
            separateNonEmptyPrefix(packageIdentifier(packageSymbol.owner), localIdentifier(packageSymbol.name))
        }
    }
    def packageJsIdentifier(packageSymbol: Symbol) = js.Identifier(packageIdentifier(packageSymbol))

    /** Returns an identifier corresponding to the specified symbol. */
    def typeIdentifier(symbol: Symbol): String = {
        val identifier =
            if (symbol == NoSymbol) {
                ""
            } else if (symbol.isTypeParameterOrSkolem || symbol.isLocalOrAnonymous) {
                localIdentifier(symbol.name)
            } else if (symbol.isAdapter) {
                val isScope = symbol.tpe <:< typeOf[swat.js.Scope]
                val isAdapterPackageObject = symbol.isPackageObjectOrClass && adapterPackages(symbol.owner.fullName)
                val stripPackage = symbol.adapterAnnotation.getOrElse(true)
                if (isScope || isAdapterPackageObject && stripPackage) {
                    ""
                } else {
                    val prefix = if (stripPackage) "" else packageIdentifier(symbol.owner)
                    separateNonEmptyPrefix(prefix, localIdentifier(symbol.name))
                }
            } else if (symbol.isPackageObjectOrClass) {
                packageIdentifier(symbol.owner)
            } else if (symbol.owner.isPackageClass) {
                separateNonEmptyPrefix(packageIdentifier(symbol.owner), localIdentifier(symbol.name))
            } else {
                typeIdentifier(symbol.owner.tpe) + "$" + symbol.name.toString
            }
        val suffix = if (symbol.isObject && !symbol.isAdapter) "$" else ""

        identifier + suffix
    }
    def typeIdentifier(tpe: Type): String = typeIdentifier(tpe.underlying.typeSymbol)
    def typeJsIdentifier(tpe: Type): js.Identifier = typeJsIdentifier(tpe.typeSymbol)
    def typeJsIdentifier(symbol: Symbol): js.Identifier = js.Identifier(typeIdentifier(symbol))

    def separateNonEmptyPrefix(prefix: String, suffix: String, separator: String = ".") = prefix match {
        case "" => suffix
        case _ => prefix + separator + suffix
    }
}
