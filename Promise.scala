package lib

/**
 * Add the notion of Fullfilled promise.
 */

import java.util.concurrent.atomic.AtomicBoolean
import scala.reflect.Manifest
import java.util.concurrent.{ Executors, ConcurrentLinkedQueue, ThreadFactory }
import scala.annotation.tailrec
import scala.annotation.unchecked._

object PromiseImplicits {

  implicit def promiseExtensions[T, S <: PState](p: Promise[T, S]) =
    new PromiseExtensions(p)

  implicit def toPromEither[T, S <: PState](p: Promise[Either[Throwable, T], S]) =
    new PromiseEitherExtensions(p)

  implicit def toPromProjState[T, S <: PState](p: Promise[T, S]) =
    new PromiseProjection(p)

  implicit def toPromUtil[T, S <: PState](p: Promise[T, S]) =
    new PromiseUtilExtensions(p)

  implicit def toPending[T](p: Promise[T, PPending]) =
    new PromiseExtensionsPending(p)

  implicit def toFulfilled[T](p: Promise[T, PFulfilled]) =
    new PromiseExtensionsFulfilled(p)

  type PromiseF[+T] = Promise[T, PFulfilled]
  type PromiseP[+T] = Promise[T, PPending]
  type PromiseS[+T] = Promise[T, PState]
}

import PromiseImplicits._

object Caching {
  def cached[T, U, S <: PState](keyToVal: T => Promise[U, S]): (T => Promise[U, PPending]) = {
    val cache = scala.collection.mutable.HashMap.empty[T, Promise[U, PPending]]
    val res = { key: T =>
      cache.synchronized {
        cache getOrElseUpdate (key, Promise.lazily { keyToVal(key) })
      }
    }
    res
  }

  def lasting[T, U, S <: PState](timeDelay: Long)(f: T => Promise[U, S]): (T => PromiseP[U]) = {
    var curFonc: T => PromiseP[U] = cached(f)
    var lastTime = System.currentTimeMillis()
    val res = { key: T =>
      val curTime = System.currentTimeMillis()
      if (curTime > (lastTime + timeDelay)) {
        curFonc = cached(f)
        lastTime = curTime
      }
      curFonc(key)
    }
    res
  }
}

object Implicits {
  implicit def richSplitList[U](l: List[U]) = new {
    def splitIn(nb: Int) =
      if (nb <= 0) Nil else splitEvery(scala.math.max(1, l.length / nb))

    def splitEvery(nb: Int): List[List[U]] = {
      @tailrec
      def splitEveryRec(l: List[U], nb: Int, acc: List[List[U]]): List[List[U]] =
        (l splitAt nb) match {
          case (bunch, Nil) => bunch :: acc
          case (bunch, rest) => splitEveryRec(rest, nb, bunch :: acc)
        }
      splitEveryRec(l, nb, Nil)
    }
  }
}

/**
 * Ensure non blocking behavior.
 * Investigate atomic usage.
 */

object Promise {

  val TIMEOUT = new Exception("promise timeout")
  val leftTIMEOUT = Left(TIMEOUT)
  val timeoutPromise = Promise.either(leftTIMEOUT)

  val undefined = new Exception("undefined Promise")
  val leftUndefined = Left(undefined)
  def notDefined = throw undefined
  
  val empty = Promise.failure(undefined)
  
  def failed[T] = empty.asInstanceOf[Promise[T, PFulfilled]]

  val emptyUpdating = { () => }

  def lazily[T](v: => Promise[T, _]): LazyPromise[T] = {
    lazy val prom: LazyPromise[T] = // lazy to solve initialisation order problem
      new LazyPromise[T](() => v foreachEither (prom setPromiseEither _))
    prom
  }

  def flat[T,S <: PState](p : => Promise[T, S]) : Promise[T, S#FulfilledAnd] = {
    val prom = Promise(p)
    prom.flatMapEither {
      case Left(err) => prom.asInstanceOf[Promise[T, S]]
      case Right(p) => p
    }
  }

  def apply[T](): MutablePromise[T, PPending] = new StrictPromise[T]
  def apply[T](v: => T): Promise[T, PFulfilled] = {
    Promise[T]().setPromise(v)
  }
  def async[T](): AsyncPromise[T] = new AsyncPromise[T]
  def async[T](v: => T): AsyncPromise[T] = {
    val prom = async[T]()
    Future.newSpawn {
      prom.setPromise(v)
    }
    prom
  }

  def asyncFlat[T, S](f: => Promise[T, _]): AsyncPromise[T] = {
    val prom = async[T]()
    Future.newSpawn {
      try {
        f foreachEither (prom setPromiseEither _)
      } catch {
        case err => prom.setPromiseEither(Left(err))
      }
    }
    prom
  }

  def either[T](ei: => Either[Throwable, T]): Promise[T, PFulfilled] = {
    Promise[T]() setPromiseEither ei
  }

  final def failure[T](e: => Throwable): Promise[T, PFulfilled] = {
    Promise[T]() fail e
  }

  def join[U](promises: List[Promise[U, PState]]): Promise[List[U], _ <: PState] = {
    def recJoin(l: List[Promise[U, PState]], acc: List[U]): Promise[List[U], _ <: PState] =
      l match {
        case Nil => Promise(acc.reverse)
        case p :: rest => p.async.flatMap { x => recJoin(rest, x :: acc) } // async acts as a trampoline
      }
    val proms = promises.distinct
    Promise async { proms.foreach { _.isSet } } // make sure all lazy promises are triggered upfront..
    recJoin(proms, Nil)
  }

  /**
   * Delays a promise by a certain amount of time.
   */
  /*
  implicit def toDelayed(thiz : Promise[T]) = new {
    def delayed(ms: Long) : Promise[T] = {
      val prom = Promise[T]()
      Scheduler.schedule({
        thiz.foreach { x => prom.setPromiseEither(x) }
      }, ms)
      prom
    }
  }
  */

}

final class StrictPromise[T] extends MutablePromise[T, PPending] {
  private var _value: Option[Either[Throwable, T]] = None

  final def setValueEither(ev: => Either[Throwable, _ <: T]): Promise[T, PFulfilled] = {
    try {
      _value = Some(ev)
    } catch {
      case e: Throwable => _value = Some(Left(e)) // even the evaluation of a failure can lead to another one.
    }
    afterValueChanged
  }
  final def nowEither(): Option[Either[Throwable, T]] = _value
  final def isSetEither: Boolean = _value.isDefined
}

final class AsyncPromise[T] extends MutablePromise[T, PPending] {

  override def foreachEither(f: Either[Throwable, T] => Unit): Unit =
    super.foreachEither { e => Future newSpawn f(e) }

  // Same as strict, just here for the sake of keeping stuff flat and the class final
  private var _value: Option[Either[Throwable, T]] = None
  final def setValueEither(ev: => Either[Throwable, _ <: T]) = {
    try {
      _value = Some(ev)
    } catch {
      case e: Throwable => _value = Some(Left(e)) // even the evaluation of a failure can lead to another one.
    }
    afterValueChanged
  }
  final def nowEither(): Option[Either[Throwable, T]] = _value
  final def isSetEither: Boolean = _value.isDefined
}

final class LazyPromise[T](var lazyAction: () => Unit) extends MutablePromise[T, PPending] {
  @volatile
  private var _value: Option[Either[Throwable, T]] = None

  // It set from lazily method so there's no need to worry.. ev thunk is wrapping an evaluated, pure, value (no exception will be thrown).
  final def setValueEither(ev: => Either[Throwable, _ <: T]) = {
    try {
      _value = Some(ev)
    } catch {
      case e: Throwable =>
        _value = Some(Left(e)) // even the evaluation of a failure can lead to another one (improbable base on upper comment).
    }
    afterValueChanged
  }

  final def doLazyActionIfNeeded() = {
    if (lazyAction != null) synchronized { // narrowing test
      if (lazyAction != null) {
        val toEvaluate = lazyAction
        lazyAction = null // we free the lazy thunk memory and prevent reentrant evaluation
        try {
          toEvaluate()
        } catch {
          case e => _value = Some(Left(e))
        }
      }
    }
  }

  final def nowEither(): Option[Either[Throwable, T]] = {
    doLazyActionIfNeeded()
    _value
  }

  final def isSetEither: Boolean = {
    doLazyActionIfNeeded()
    (lazyAction == null) || _value.isDefined
  }
}

final case class DependencyCallBack[T](f: Either[Throwable, T] => _)

abstract class MutablePromise[T, S <: PState] extends Promise[T, S] { self =>

  private val _hookOnSet = new ConcurrentLinkedQueue[DependencyCallBack[T]]

  final protected def propagate(): Unit =
    nowEither() match {
      case Some(finalValue) =>
        @tailrec
        def internPropagate: Unit = {
          var finished = false;
          try {
            var elem = _hookOnSet.poll()
            while (elem != null) {
              elem f finalValue
              elem = _hookOnSet.poll()
            }
            finished = true;
          } catch {
            case e =>
              println("exception in promise propagation")
              e.printStackTrace
          }
          if (!finished)
            internPropagate // continue
        }
        internPropagate
      case _ =>
    }

  /**
   * exclusively by default
   */
  protected def setValueEither(ev: => Either[Throwable, _ <: T]): Promise[_ <: T, PFulfilled]

  final def setPromiseEither(ei: => Either[Throwable, _ <: T]) = setValueEither(ei)
  final def fail(ex: => Throwable) = setValueEither(Left(ex))
  final def setPromise(x: => T) = setValueEither(Right(x))

  def foreachEither(f: Either[Throwable, T] => Unit): Unit = {
    _hookOnSet offer DependencyCallBack(f)
    propagate()
  }

  /**
   * Change the current value and return the function to call to propagates (make sure to call it!)
   */
  final protected def afterValueChanged() = {
    synchronized {
      notifyAll()
    }
    propagate()
    this.asInstanceOf[Promise[T, PFulfilled]]
  }

  final def waitUntil(delay: Long) = {
    isSetEither // to trigger lazyPromises
    synchronized {
      if (nowEither().isEmpty) {
        try {
          wait(delay)
        } catch {
          case e: InterruptedException => this.fail(e)
        }
      }
    }
    self
  }

}

abstract class PromiseOp[+T, S <: PState] {
  type Param[+_]
  type FinalRes[+_]
  type CurRes[+_]

  def foreach(f: Param[T] => Unit): Unit

  def map[U](f: Param[T] => Param[U]): Promise[U, S]

  def flatMap[U, S2 <: PState](f: Param[T] => Promise[U, S2]): Promise[U, S#And[S2]]

  def filter(pred: Param[T] => Boolean): Promise[T, S]

  def isSet: Boolean
}

final class PromiseProjection[+T, S <: PState](private val outer: Promise[T, S]) extends PromiseOp[T, S] {

  type Param[+a] = a
  type FinalRes[+a] = Option[a]
  type CurRes[+a] = Option[a]

  final def foreach(f: T => Unit): Unit =
    outer.foreachEither {
      case Right(x) => f(x)
      case _ =>
    }

  final def map[U](f: T => U): Promise[U, S] =
    outer.mapEither {
      case Right(x) => Right(f(x))
      case left: Left[_, _] => left.asInstanceOf[Left[Throwable, U]] // we're abusing the compiler here to do not recreate a Left exception value
    }

  final def flatMap[U, S2 <: PState](f: T => Promise[U, S2]): Promise[U, S#And[S2]] =
    outer.flatMapEither {
      case Right(v) => f(v)
      case _ => outer.asInstanceOf[Promise[U, S2]] // we're abusing the compiler but this is safe
    }

  final def filter(pred: T => Boolean): Promise[T, S] =
    outer.filterEither {
      case Right(x) => pred(x)
      case _ => false
    }

  final def isSet: Boolean = outer.isSetEither

}

final class PromiseExtensionsPending[+T](outer: Promise[T, PPending]) {

  final def now(): Option[T] =
    outer.nowEither() match {
      case Some(Right(v)) => Some(v)
      case _ => None
    }
  final def nowEither() = outer.nowEither()

  final def waitOk(): Promise[T, PFulfilled] =
    waitUntil(0).asInstanceOf[Promise[T, PFulfilled]]

  final def waitUntil(delay: Long): Promise[T, PPending] =
    outer.waitUntil(delay)

  final def waitAndGetUntil(timeOut: Long): PromiseProjection[T, PPending]#CurRes[T] = {
    waitUntil(timeOut)
    now()
  }

  final def waitAndGet() = waitOk().get()

  def before(delay: Long): Promise[T, PFulfilled] = {
    outer.waitUntil(delay)
    outer.now() match {
      case Some(_) => outer.asInstanceOf[Promise[T, PFulfilled]] // promise is fulfilled
      case None => Promise.timeoutPromise // promise is not ready yet
    }
  }
}

final class PromiseExtensionsFulfilled[+T](outer: Promise[T, PFulfilled]) {

  final def get(): Option[T] =
    outer.nowEither() match {
      case Some(Right(v)) => Some(v)
      case _ => None
    }

  final def getOrThrow(): T =
    outer.nowEither() match {
      case Some(Right(v)) => v
      case Some(Left(err)) => throw err
      case _ => throw Promise.undefined
    }

  final def getEither() = outer.nowEither().get // statically enforced
}

final class PromiseEitherExtensions[+T, S <: PState](outer: Promise[Either[Throwable, T], S]) {

  /**
   * unlift Left(err) to exception, and right value to value
   */
  def fromEither: Promise[T, S] = {
    val prom = Promise[T]()
    outer.foreachEither {
      case Right(v) => prom setPromiseEither v
      case l@Left(_) => prom setPromiseEither (l.asInstanceOf[Either[Throwable, T]])
    }
    prom.asInstanceOf[Promise[T, S]]
  }

  /**
   * lift exception to Left value, if any
   */
  def unthrow: Promise[Either[Throwable, T], S] = {
    val prom = Promise[Either[Throwable, T]]()
    outer.foreachEither {
      case r@Right(_) => prom setPromiseEither r
      case l@Left(_) => prom setPromiseEither Right(l.asInstanceOf[Either[Throwable, T]])
    }
    prom.asInstanceOf[Promise[Either[Throwable, T], S]]
  }
}

final class PromiseExtensions[+T, S <: PState](outer: Promise[T, S]) {

  /**
   * lift exception to left(err) and value to right(value)
   */
  def toEither: Promise[Either[Throwable, T], S] = {
    val prom = Promise[Either[Throwable, T]]()
    outer.foreachEither { prom setPromise _ }
    prom.asInstanceOf[Promise[Either[Throwable, T], S]]
  }

  def async: Promise[T, PPending] = {
    val prom = Promise.async[T]()
    outer.foreachEither { prom setPromiseEither _ }
    prom
  }

  def failure(): Promise[Throwable, S] =
    outer.mapEither {
      case Left(err) => Right(err)
      case _ => Promise.leftUndefined
    }

  def orElse[U >: T, S2 <: PState](other: => Promise[U, S2]): Promise[U, S#OrElse[S2]] = {
    val prom = Promise[U]()
    outer.foreachEither {
      case r@Right(_) => prom.setPromiseEither(r)
      case _ =>
        try {
          other.foreachEither { prom setPromiseEither _ }
        } catch {
          case err: Throwable => prom.setPromiseEither(Left(err))
        }
    }
    prom.asInstanceOf[Promise[U, S#OrElse[S2]]]
  }

  /**
   * Returns a promise whose value equals to the first promise fulfilled exactly when it is fulfilled.
   * Returns a faillure if both fail (with the last one faillure)
   * ex:
   *   connector.connect(inetAddr).toProm() or (Promise.failed[IoSession].delayed(timeout))
   */

  def or[U >: T, S2 <: PState](other: Promise[U, S2]): Promise[U, S#Or[S2]] = {
    val prom = Promise[U]()
    val status = new java.util.concurrent.atomic.AtomicInteger(0) // 0 -> not set, 1 -> value set (right), 2 -> one error (left)

    val updateAsNeeded: Either[Throwable, U] => Unit =
      { x: Either[Throwable, U] =>
        x match {
          case r: Right[_, _] =>
            if (!status.compareAndSet(1, 1)) {
              prom.setPromiseEither(r)
            }
          case l: Left[_, _] =>
            if (status.compareAndSet(2, 2)) { // we only want to write if we are the second error
              prom.setPromiseEither(l)
            }
        }
      }

    outer.foreachEither(updateAsNeeded)
    other.foreachEither(updateAsNeeded)

    prom.asInstanceOf[Promise[U, S#Or[S2]]]
  }

  def &&[U, S2 <: PState](other: => Promise[U, S2]): Promise[(T, U), S#And[S2]] = {
    val prom = Promise[(T, U)]()
    outer.foreachEither {
      case Right(t) =>
        try {
          other.foreachEither {
            case Right(u) => prom.setPromise((t, u))
            case left: Left[_, _] => prom.setPromiseEither(left.asInstanceOf[Left[Throwable, (T, U)]]) // we're abusing the compiler here to do not recreate a Left exception value
          }
        } catch {
          case err => prom.setPromiseEither(Left(err)) // caused by 'other' evaluation
        }
      case l@Left(err) => prom.setPromiseEither(l.asInstanceOf[Left[Throwable, (T, U)]])
    }
    prom.asInstanceOf[Promise[(T, U), S#And[S2]]]
  }

}

// mainly for internal purpose
final class PromiseUtilExtensions[+T, S <: PState](outer: Promise[T, S]) {

  final def mapEither[U](f: Either[Throwable, T] => Either[Throwable, U]): Promise[U, S] = {
    val prom = Promise[U]()
    outer.foreachEither { x => prom setPromiseEither f(x) }
    prom.asInstanceOf[Promise[U, S]]
  }

  final def flatMapEither[U, S2 <: PState](f: Either[Throwable, T] => Promise[U, S2]): Promise[U, S#And[S2]] = {
    val prom = Promise[U]()
    outer.foreachEither { eith =>
      try {
        f(eith) foreachEither { prom setPromiseEither _ }
      } catch {
        case e => prom fail e
      }
    }
    prom.asInstanceOf[Promise[U, S#And[S2]]]
  }

  final def filterEither(pred: Either[Throwable, T] => Boolean): Promise[T, S] = {
    val prom = Promise[T]()
    outer.foreachEither { x => prom.setPromiseEither(if (pred(x)) x else Promise.leftUndefined) }
    prom.asInstanceOf[Promise[T, S]]
  }

}

abstract class Promise[+T, +S <: PState] {

  def waitUntil(delay: Long): Promise[T, S]

  def foreachEither(f: Either[Throwable, T] => Unit): Unit
  def nowEither(): Option[Either[Throwable, T]]
  def isSetEither: Boolean

}

// Based on boolean logic (type level encoding)
sealed trait PState {
  type And[T <: PState] <: PState
  type Or[T <: PState] <: PState
  type OrElse[T <: PState] <: PState

  type PendingAnd <: PState
  type FulfilledAnd <: PState

  type PendingOr <: PState
  type FulfilledOr <: PState

  type PendingOrElse <: PState
  type FulfilledOrElse <: PState
}

case class PPending() extends PState {
  type And[T <: PState] = T#PendingAnd
  type Or[T <: PState] = T#PendingOr
  type OrElse[T <: PState] = T#PendingOrElse

  type FulfilledAnd = PPending
  type PendingAnd = PPending

  type FulfilledOr = PPending
  type PendingOr = PPending

  type FulfilledOrElse = PFulfilled
  type PendingOrElse = PPending
}

case class PFulfilled() extends PState {
  type And[T <: PState] = T#FulfilledAnd
  type Or[T <: PState] = T#FulfilledOr
  type OrElse[T <: PState] = T#FulfilledOrElse

  type FulfilledAnd = PFulfilled
  type PendingAnd = PPending

  type FulfilledOr = PFulfilled
  type PendingOr = PPending

  type FulfilledOrElse = PFulfilled
  type PendingOrElse = PPending
}

abstract class Future[T] {
  def function: T
  override def toString = "Spawned Future"
}

class FutureThreadFactory(tgName: String) extends ThreadFactory {
  import java.util.concurrent.atomic.AtomicInteger

  private val _tg = new ThreadGroup(tgName)
  private var _nb = new AtomicInteger()

  def newThread(r: Runnable) = {
    val thread = new Thread(_tg, r, _tg.getName + "-" + _nb.incrementAndGet())
    thread.setPriority(Thread.MAX_PRIORITY)
    thread.setDaemon(true)
    thread
  }
}

abstract class FutureThreadPool(_name: String, _threadBaseName: String, _threadPoolSize: Int) /*extends LogEnabled*/ {
  import scala.collection._
  import java.util.concurrent.Executors

  val _executor = Executors.newFixedThreadPool(_threadPoolSize, new FutureThreadFactory(_threadBaseName)).asInstanceOf[java.util.concurrent.ThreadPoolExecutor]

  def isInFutureThread() = (Thread.currentThread.getName indexOf _threadBaseName) != -1

  def newSpawn[T](_function: => T): Future[T] = {
    val b =
      new Future[T] with Runnable { self =>
        def function = _function
        def run() = try {
          function
        } catch { case e => /*log.forwardException(e)*/ }
      }
    _executor.execute(b)
    b
  }

  def shutdown = _executor.shutdown
}

object Future extends FutureThreadPool("FutureThreadPool", "future", {
  val futureThreadPoolRatio = 1.0
  scala.math.max(3, futureThreadPoolRatio * Runtime.getRuntime.availableProcessors).toInt
}) {
  val _IOexecutor = Executors.newFixedThreadPool(1, new FutureThreadFactory("io")).asInstanceOf[java.util.concurrent.ThreadPoolExecutor]
}
