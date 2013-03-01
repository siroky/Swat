package swat.api.js.events

trait EventTarget {
    def addEventListener(`type`: String, listener: EventListener, useCapture: Boolean = false) {}
    def removeEventListener(`type`: String, listener: EventListener, useCapture: Boolean = false) {}
    def dispatchEvent(event: Event[this.type]): Boolean = ???
}
