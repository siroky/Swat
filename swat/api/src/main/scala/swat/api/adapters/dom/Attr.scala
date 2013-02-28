package swat.api.adapters.dom

trait Attr extends Node {
    val name: String
    val specified: Boolean
    var value: String
    val ownerElement: Element
    val schemaTypeInfo: TypeInfo
    val isId: Boolean
}
