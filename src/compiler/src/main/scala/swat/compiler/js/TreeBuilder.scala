package swat.compiler.js

trait TreeBuilder {

    def astToStatement(ast: Ast): Statement = ast match {
        case s: Statement => s
        case e: Expression => ExpressionStatement(e)
    }

    def memberChain(expr: Expression, identifiers: Identifier*): Expression = {
        require(identifiers.length > 0)
        identifiers.foldLeft[Expression](expr)(MemberExpression)
    }

    def iteratedMemberChain(expr: Expression, identifier: Identifier, count: Int): Expression = {
        (1 to count).foldLeft[Expression](expr)((z, _) => memberChain(z, identifier))
    }

    def methodCall(target: Expression, methodName: Identifier, args: Expression*): Expression = {
        CallExpression(memberChain(target, methodName), args.toList)
    }

    def newObject(tpe: Expression, args: List[Expression]): NewExpression = {
        NewExpression(CallExpression(tpe, args))
    }

    def scoped(body: Statement): Expression = scoped(List(body))

    def scoped(body: List[Statement]): Expression = CallExpression(FunctionExpression(None, Nil, body), Nil)

    def unScoped(expression: Expression): List[Statement] = expression match {
        case CallExpression(FunctionExpression(None, Nil, body), Nil) => body
        case e => List(ExpressionStatement(e))
    }

    def unScoped(statement: Statement): List[Statement] = statement match {
        case ExpressionStatement(e) => unScoped(e)
        case ReturnStatement(Some(CallExpression(FunctionExpression(None, Nil, body), Nil))) => body
        case s => List(s)
    }

    def throwNew(identifier: Identifier, args: List[Expression]): Statement = {
        ThrowStatement(NewExpression(CallExpression(identifier, args)))
    }
}
