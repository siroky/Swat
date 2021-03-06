package swat.js.html.elements

import swat.js.events._
import swat.js.html.Element

trait InputLike extends EventTarget with Element {
    var disabled: Boolean
    val form: Form
    var name: String
    var value: String
    var onchange: Event[this.type] => Unit

    def focus()
}

trait TextInputLike extends InputLike {
    var defaultValue: String
    var readOnly: Boolean
    var value: String

    def select()
}
