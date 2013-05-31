/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package swat.scala
package concurrent
package impl

import swat.scala.util.control.NonFatal
import swat.scala.util.{ Try, Success, Failure }

private[concurrent] trait Promise[T] extends swat.scala.concurrent.Promise[T] with swat.scala.concurrent.Future[T] {
  def future: this.type = this
}

/* Swat excluded
/* Precondition: `executor` is prepared, i.e., `executor` has been returned from invocation of `prepare` on some other `ExecutionContext`.
 */
private class CallbackRunnable[T](val executor: ExecutionContext, val onComplete: Try[T] => Any) extends Runnable with OnCompleteRunnable {
  // must be filled in before running it
  var value: Try[T] = null

  override def run() = {
    require(value ne null) // must set value to non-null before running!
    try onComplete(value) catch { case NonFatal(e) => executor reportFailure e }
  }

  def executeWithValue(v: Try[T]): Unit = {
    require(value eq null) // can't complete it twice
    value = v
    // Note that we cannot prepare the ExecutionContext at this point, since we might
    // already be running on a different thread!
    try executor.execute(this) catch { case NonFatal(t) => executor reportFailure t }
  }
}*/

private[concurrent] object Promise {

  private def resolveTry[T](source: Try[T]): Try[T] = source match {
    case Failure(t) => resolver(t)
    case _          => source
  }

  private def resolver[T](throwable: Throwable): Try[T] = throwable match {
    case t: scala.runtime.NonLocalReturnControl[_] => Success(t.value.asInstanceOf[T])
    case t: scala.util.control.ControlThrowable    => Failure(new ExecutionException("Boxed ControlThrowable", t))
    // Swat excluded case t: InterruptedException                   => Failure(new ExecutionException("Boxed InterruptedException", t))
    case e: Error                                  => Failure(new ExecutionException("Boxed Error", e))
    case t                                         => Failure(t)
  }

  /** Default promise implementation.
   */
  class DefaultPromise[T] /* Swat excluded extends AbstractPromise */ extends Promise[T] { self =>
    /* Swat excluded
    updateState(null, Nil) // Start at "No callbacks"

    protected final def tryAwait(atMost: Duration): Boolean = {
      @tailrec
      def awaitUnsafe(deadline: Deadline, nextWait: FiniteDuration): Boolean = {
        if (!isCompleted && nextWait > Duration.Zero) {
          val ms = nextWait.toMillis
          val ns = (nextWait.toNanos % 1000000l).toInt // as per object.wait spec

          synchronized { if (!isCompleted) wait(ms, ns) }

          awaitUnsafe(deadline, deadline.timeLeft)
        } else
          isCompleted
      }
      @tailrec
      def awaitUnbounded(): Boolean = {
        if (isCompleted) true
        else {
          synchronized { if (!isCompleted) wait() }
          awaitUnbounded()
        }
      }

      import Duration.Undefined
      atMost match {
        case u if u eq Undefined => throw new IllegalArgumentException("cannot wait for Undefined period")
        case Duration.Inf        => awaitUnbounded()
        case Duration.MinusInf   => isCompleted
        case f: FiniteDuration   => if (f > Duration.Zero) awaitUnsafe(f.fromNow, f) else isCompleted
      }
    }

    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
      if (isCompleted || tryAwait(atMost)) this
      else throw new TimeoutException("Futures timed out after [" + atMost + "]")

    @throws(classOf[Exception])
    def result(atMost: Duration)(implicit permit: CanAwait): T =
      ready(atMost).value.get match {
        case Failure(e)  => throw e
        case Success(r) => r
      }
    */

    var result: Try[T] = null
    var listeners: List[Try[T] => Any] = Nil
    def value: Option[Try[T]] = Option(result)

    override def isCompleted: Boolean = result != null

    def tryComplete(value: Try[T]): Boolean = {
      /* Swat modified state = resolveTry(value)
      (try {
        @tailrec
        def tryComplete(v: Try[T]): List[CallbackRunnable[T]] = {
          getState match {
            case raw: List[_] =>
              val cur = raw.asInstanceOf[List[CallbackRunnable[T]]]
              if (updateState(cur, v)) cur else tryComplete(v)
            case _ => null
          }
        }
        tryComplete(resolved)
      } finally {
        synchronized { notifyAll() } //Notify any evil blockers
      }) match {
        case null             => false
        case rs if rs.isEmpty => true
        case rs               => rs.foreach(r => r.executeWithValue(resolved)); true
      }*/
      if (isCompleted) {
        false
      } else {
        result = value
        listeners.foreach(l => try l(result) catch { case NonFatal(e) => /* Swat TODO executor reportFailure e */ })
        true
      }
    }

    def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit = {
      /* Swat modified
      val preparedEC = executor.prepare()
      val runnable = new CallbackRunnable[T](preparedEC, func)

      @tailrec //Tries to add the callback, if already completed, it dispatches the callback to be executed
      def dispatchOrAddCallback(): Unit =
        getState match {
          case r: Try[_]          => runnable.executeWithValue(r.asInstanceOf[Try[T]])
          case listeners: List[_] => if (updateState(listeners, runnable :: listeners)) () else dispatchOrAddCallback()
        }
      dispatchOrAddCallback()*/
      if (isCompleted) {
        try func(value.get) catch { case NonFatal(e) => /* Swat TODO executor reportFailure e */ }
      } else {
        listeners = func :: listeners
      }
    }
  }

  /** An already completed Future is given its result at creation.
   *
   *  Useful in Future-composition when a value to contribute is already available.
   */
  final class KeptPromise[T](suppliedValue: Try[T]) extends Promise[T] {

    val value = Some(resolveTry(suppliedValue))

    override def isCompleted: Boolean = true

    def tryComplete(value: Try[T]): Boolean = false

    def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit = {
      /* Swat modified
      val completedAs = value.get
      val preparedEC = executor.prepare()
      (new CallbackRunnable(preparedEC, func)).executeWithValue(completedAs)*/
      try func(value.get) catch { case NonFatal(e) => /* Swat TODO executor reportFailure e */ }
    }
    /* Swat excluded
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this

    def result(atMost: Duration)(implicit permit: CanAwait): T = value.get.get
    */
  }

}
