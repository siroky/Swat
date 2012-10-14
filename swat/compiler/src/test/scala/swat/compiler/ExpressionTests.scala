package swat.compiler

class ExpressionTests extends CompilerSuite
{
    test("Operators on Any") {
        """
            val x: Any = "x"
            val y: Any = 123
            x == y
            x != y
            x equals y
        """ fragmentShouldCompileTo """
            var x = 'x';
            var y = 123;
            swat.equals(x, y);
            !swat.equals(x, y);
            swat.equals(x, y);
        """
    }

    test("Methods on Any") {
        """
            val x: Any = "x"
            x.toString
            x.hashCode
            x.##
            x.getClass
        """ fragmentShouldCompileTo """
            var x = 'x';
            swat.toString(x);
            swat.hashCode(x);
            swat.hashCode(x);
            swat.getClass(x);
        """
    }

    test("Operators on AnyVal") {
        """
            val i: Int = 1
            val j: Int = 2
            +i
            -i
            i + j
            i - j
            i * j
            i / j
            i % j
            ~i
            i & j
            i | j
            i ^ j
            i << j
            i >> j
            i >>> j
            i < j
            i > j
            i <= j
            i >= j
            i == j
            i != j
            i equals j

            val b: Double = 1.0
            val c: Double = 2.0
            -b
            b / c
            b < c
            b != c
            b equals c

            val m: Char = 'm'
            val n: Char = 'n'
            -m
            m * n
            ~m
            m & n
            m << n
            m < n
            m >= n
            m == n
            m != n
            m equals n

            val x: Boolean = true
            val y: Boolean = false
            !x
            x && y
            x || y
            x & y
            x | y
            x ^ y
        """ fragmentShouldCompileTo """
            var i = 1;
            var j = 2;
            +i;
            -i;
            (i + j);
            (i - j);
            (i * j);
            Math.floor((i / j));
            (i % j);
            ~i;
            (i & j);
            (i | j);
            (i ^ j);
            (i << j);
            (i >> j);
            (i >>> j);
            (i < j);
            (i > j);
            (i <= j);
            (i >= j);
            (i == j);
            (i != j);
            (i == j);

            var b = 1.0;
            var c = 2.0;
            -b;
            (b / c);
            (b < c);
            (b != c);
            (b == c);

            var m = 'm';
            var n = 'n';
            -scala.Char.toInt(m);
            (scala.Char.toInt(m) * scala.Char.toInt(n));
            ~scala.Char.toInt(m);
            (scala.Char.toInt(m) & scala.Char.toInt(n));
            (scala.Char.toInt(m) << scala.Char.toInt(n));
            (scala.Char.toInt(m) < scala.Char.toInt(n));
            (scala.Char.toInt(m) >= scala.Char.toInt(n));
            (m == n);
            (m != n);
            (m == n);

            var x = true;
            var y = false;
            !x;
            (x && y);
            (x || y);
            Boolean((x & y));
            Boolean((x | y));
            Boolean((x ^ y));
        """
    }

    test("Methods on AnyVal") {
        """
            val x: Int = 1
            x.hashCode
            x.toByte
            x.toDouble
        """ fragmentShouldCompileTo """
            var x = 1;
            swat.hashCode(x);
            scala.Int.toByte(x);
            scala.Int.toDouble(x);
        """
    }

    test("Operators on AnyRef") {
        """
            val x: AnyRef = null
            val y: AnyRef = null
            x == y
            x != y
            x equals y
            x eq y
            x ne y
        """ fragmentShouldCompileTo """
            var x = null;
            var y = null;
            swat.equals(x, y);
            !swat.equals(x, y);
            swat.equals(x, y);
            (x === y);
            (x !== y);
        """
    }

    test("Methods on AnyRef") {
        """
            val x: AnyRef = null
            x.hashCode
            x.isInstanceOf[String]
            x.asInstanceOf[String]
        """ fragmentShouldCompileTo """
            var x = null;
            swat.hashCode(x);
            swat.isInstanceOf(x, java.lang.String);
            swat.asInstanceOf(x, java.lang.String);
        """
    }

    test("Operators on String") {
        """
            val a: String = "a"
            val b: String = "b"
            a + b
            a == b
            a != b
            a equals b
            a eq b
            a ne b
        """ fragmentShouldCompileTo """
            var a = 'a';
            var b = 'b';
            (a + b);
            (a == b);
            (a != b);
            (a == b);
            (a === b);
            (a !== b);
        """
    }

    test("Methods on String") {
        """
            val x: String = "a"
            x.hashCode
            x.length
            x.substring(3)
        """ fragmentShouldCompileTo """
            var x = 'a';
            swat.hashCode(x);
            java.lang.String.length(x);
            java.lang.String.substring(x, 3);
        """
    }

    test("Anonymous functions, cyrrying, partial application") {
        """
            val f = (x: Int, y: Int) => x * y

            val g = (x: Int) => (y: Int) => x + y

            val h = f(_, _)
            val i = h(_, _)
            val j = f(1, _: Int)
            val k = f(_: Int, 1)

        """ fragmentShouldCompileTo """
            var f = (function(x, y) {
                return (x * y);
            });

            var g = (function(x) {
                return (function(y) {
                    return (x + y);
                });
            });

            var h = (function(x$1, x$2) {
                return f(x$1, x$2);
            });
            var i = (function(x$3, x$4) {
                return h(x$3, x$4);
            });
            var j = (function(x$5) {
                return f(1, x$5);
            });
            var k = (function(x$6) {
                return f(x$6, 1);
            });
        """
    }
}
