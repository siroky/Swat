package swat.compiler

class ClassDefinitionTests extends CompilerSuite {

    test("Adapter classes and ignored classes aren't compiled") {
        """
            import swat._

            @ignored class A1
            @ignored trait T1
            @ignored object O1

            @adapter class A2
            @adapter trait T2
            @adapter object O2
        """ shouldCompileToPrograms Map.empty
    }

    test("Definitions are properly qualified with respect to packages and outer classes") {
        """
            import swat._

            class A

            class ::<>

            package foo
            {
                class B

                package object bar

                package bar.baz
                {
                    class C
                    {
                        class D
                        trait E
                    }
                }
            }
        """ shouldCompileTo Map(
            "A" ->
                """
                    swat.provide('A');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    A.$init$ = (function() {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'A');
                    });
                    A = swat.type('A', [A, java.lang.Object, scala.Any]);
                """,

            "$colon$colon$less$greater" ->
                """
                    swat.provide('$colon$colon$less$greater');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    $colon$colon$less$greater.$init$ = (function() {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], '$colon$colon$less$greater');
                    });
                    $colon$colon$less$greater = swat.type('$colon$colon$less$greater', [$colon$colon$less$greater, java.lang.Object, scala.Any]);
                """,

            "foo.B" ->
                """
                    swat.provide('foo.B');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    foo.B.$init$ = (function() {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'foo.B');
                    });
                    foo.B = swat.type('foo.B', [foo.B, java.lang.Object, scala.Any]);
                """,

            "foo.bar$" ->
                """
                    swat.provide('foo.bar$');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    foo.bar$.$init$ = (function() {
                        var $self = this;
                       swat.invokeSuper($self, '$init$', [], 'foo.bar$');
                    });
                    foo.bar$ = swat.object('foo.bar$', [foo.bar$, java.lang.Object, scala.Any]);
                """,

            "foo.bar.baz.C" ->
                """
                    swat.provide('foo.bar.baz.C');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    foo.bar.baz.C.$init$ = (function() {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'foo.bar.baz.C');
                    });
                    foo.bar.baz.C = swat.type('foo.bar.baz.C', [foo.bar.baz.C, java.lang.Object, scala.Any]);
                """,

            "foo.bar.baz.C$D" ->
                """
                    swat.provide('foo.bar.baz.C$D');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    foo.bar.baz.C$D.$init$ = (function($outer) {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'foo.bar.baz.C$D');
                        $self.$outer = $outer;
                    });
                    foo.bar.baz.C$D = swat.type('foo.bar.baz.C$D', [foo.bar.baz.C$D, java.lang.Object, scala.Any]);
                """,

            "foo.bar.baz.C$E" ->
                """
                    swat.provide('foo.bar.baz.C$E');
                    swat.require('java.lang.Object', true); swat.require('scala.Any', true);

                    foo.bar.baz.C$E = swat.type('foo.bar.baz.C$E', [foo.bar.baz.C$E, java.lang.Object, scala.Any]);
                """
        )
    }

    test("Inner classes") {
        """
            class A {
                def a = new A
                def b = new B
                def c = new C

                class B {
                    def a = new A
                    def b = new B
                    def c = new C
                }

                class C
            }

            object o {
                val a = new A
                val b = new a.B
                val c = new a.C

                def x() {
                    val a = new A
                    new a.B
                    new o.a.B
                }
            }
        """ shouldCompileTo Map(
            "A" ->
                """
                    swat.provide('A');
                    swat.require('A$B', false);
                    swat.require('A$C', false);
                    swat.require('java.lang.Object', true);
                    swat.require('scala.Any', true);

                    A.$init$ = (function() { var $self = this; swat.invokeSuper($self, '$init$', [], 'A'); });
                    A.a = swat.method('A.a', '', (function() { var $self = this; return new A(); }));
                    A.b = swat.method('A.b', '', (function() { var $self = this; return new A$B($self); }));
                    A.c = swat.method('A.c', '', (function() { var $self = this; return new A$C($self); }));
                    A = swat.type('A', [A, java.lang.Object, scala.Any]);
                """,

            "A$B" ->
                """
                    swat.provide('A$B');
                    swat.require('A', false);
                    swat.require('A$C', false);
                    swat.require('java.lang.Object', true);
                    swat.require('scala.Any', true);

                    A$B.$init$ = (function($outer) {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'A$B');
                        $self.$outer = $outer;
                    });
                    A$B.a = swat.method('A$B.a', '', (function() { var $self = this; return new A(); }));
                    A$B.b = swat.method('A$B.b', '', (function() { var $self = this; return new A$B($self.$outer); }));
                    A$B.c = swat.method('A$B.c', '', (function() { var $self = this; return new A$C($self.$outer); }));
                    A$B = swat.type('A$B', [A$B, java.lang.Object, scala.Any]);
                """,

            "A$C" ->
                """
                    swat.provide('A$C');
                    swat.require('java.lang.Object', true);
                    swat.require('scala.Any', true);

                    A$C.$init$ = (function($outer) {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'A$C');
                        $self.$outer = $outer;
                    });
                    A$C = swat.type('A$C', [A$C, java.lang.Object, scala.Any]);
                """,

            "o$" ->
                """
                    swat.provide('o$');
                    swat.require('A', false);
                    swat.require('A$B', false);
                    swat.require('A$C', false);
                    swat.require('java.lang.Object', true);
                    swat.require('scala.Any', true);

                    o$.$init$ = (function() {
                        var $self = this;
                        swat.invokeSuper($self, '$init$', [], 'o$');
                        $self.$fields.a = new A();
                        $self.$fields.b = new A$B($self.a());
                        $self.$fields.c = new A$C($self.a());
                    });
                    o$.a = swat.method('o$.a', '', (function() { var $self = this; return $self.$fields.a; }));
                    o$.b = swat.method('o$.b', '', (function() { var $self = this; return $self.$fields.b; }));
                    o$.c = swat.method('o$.c', '', (function() { var $self = this; return $self.$fields.c; }));
                    o$.x = swat.method('o$.x', '', (function() {
                        var $self = this;
                        var a = new A();
                        new A$B(a);
                        new A$B(o$().a());
                    }));
                    o$ = swat.object('o$', [o$, java.lang.Object, scala.Any]);
                """
        )
    }
}
