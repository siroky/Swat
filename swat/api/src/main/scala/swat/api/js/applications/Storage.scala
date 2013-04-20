package swat.api.js.applications

trait Storage {
    val length: Long

    def key(index: Long): String
    def getItem(key: String): String
    def setItem(key: String, value: String)
    def removeItem(key: String)
    def clear()
}
