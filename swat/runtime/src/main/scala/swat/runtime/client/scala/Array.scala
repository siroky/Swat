package swat.runtime.client.scala

import swat.api.js
import swat.api.native

object Array {
    def apply[T](jsArray: js.Array[T]): Array[T] = {
        val result = new Array[T](jsArray.length)
        result.jsArray = jsArray
        result
    }
}

class Array[T](_length: Int) extends java.io.Serializable with java.lang.Cloneable {

    var jsArray = new js.Array[T]()

    def length: Int = _length

    @native("return $self.$fields.jsArray[i];")
    def apply(i: Int): T = ???

    @native("$self.$fields.jsArray[i] = x;")
    def update(i: Int, x: T) { }

    override def clone(): Array[T] = {
        val c = new Array[T](jsArray.length)
        c.jsArray = jsArray.splice(0, jsArray.length)
        c
    }
}
