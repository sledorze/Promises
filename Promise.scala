import java.util.concurrent.atomic.AtomicBoolean
import scala.reflect.Manifest
import java.util.concurrent.{ Executors, ConcurrentLinkedQueue, ThreadFactory }
import scala.annotation.tailrec
import scala.annotation.unchecked._

object PromiseImplicits {

  implicit def toMonadPlus[T](p: Promise[T]) = new PromiseIsMonadPlus(p)

  implicit def toAsync[T](p: Promise[T]) = new PromiseIsAsynchronous(p)

  implicit def toAbstractFailure[T](p: Promise[Either[Throwable, T]]) = new PromiseCanAbstractFailure(p)
  implicit def toExposeFailure[T](p: Promise[T]) = new PromiseCanExposeFailure(p)

  implicit def toEitherMonadPlus[T](p: Promise[T]) = new PromiseIsEitherMonadPlus(p)
 
  implicit def toCombinators[T](p: Promise[T]) = new PromiseCombinators(p)
}

object PromiseUnsafeImplicits {
  implicit def toUnsafe[T](p: Promise[T]) = new PromiseCanBeUnsafe(p)
}

import PromiseImplicits._

object Promise {

  val TIMEOUT = new Exception("promise timeout")
  val leftTIMEOUT = Left(TIMEOUT)
  val timeoutPromise = Promise.either(leftTIMEOUT)

  val undefined = new Exception("undefined Promise")
  val leftUndefined = Left(undefined)
  def notDefined = throw undefined
  
  val empty = Promise.failure(undefined)
  
  def failed[T] = empty

  def lazily[T](v: => Promise[T]): LazyPromise[T] = {
    lazy val prom: LazyPromise[T] = // lazy to solve initialisation order problem
      new LazyPromise[T](() => v foreachEither (prom setPromiseEither _))
    prom
  }

  def flat[T](p : => Promise[T]) : Promise[T] = {
    val prom = Promise(p)
    prom.flatMapEither {
      case Left(err) => prom.asInstanceOf[Promise[T]] // safe abuse for speed here: a Left(err) is a Left(err) even if its real type is Promise[Promise[T]] or Promise[T]
      case Right(p) => p
    }
  }

  def apply[T](): MutablePromise[T] = new MutablePromise[T]
  def apply[T](v: => T): Promise[T] = Promise[T]().set(v)
  
  def async[T](): AsyncPromise[T] = new AsyncPromise[T]
  def async[T](v: => T): AsyncPromise[T] = {
    val prom = async[T]()
    PromiseExec.newSpawn {
      prom.set(v)
    }
    prom
  }

  def asyncFlat[T](f: => Promise[T]): AsyncPromise[T] = {
    val prom = async[T]()
    PromiseExec.newSpawn {
      try {
        f foreachEither (prom setPromiseEither _)
      } catch {
        case err => prom.setPromiseEither(Left(err))
      }
    }
    prom
  }

  def either[T](ei: => Either[Throwable, T]): Promise[T] =
    Promise[T]() setPromiseEither ei

  final def failure[T](e: => Throwable): Promise[T] =
    Promise[T]() fail e
}


// final class StrictPromise[T] extends MutablePromise[T]

final class AsyncPromise[T] extends MutablePromise[T] {
  override def foreachEither(f: Either[Throwable, T] => Unit): Unit =
    super.foreachEither { PromiseExec newSpawn f(_) }
}


final class LazyPromise[T](var lazyAction: () => Unit) extends MutablePromise[T] {

  final def doLazyActionIfNeeded() = {
    if (lazyAction != null) synchronized { // narrowing test
      if (lazyAction != null) {
        val toEvaluate = lazyAction
        lazyAction = null // we free the lazy thunk memory
        try {
          toEvaluate()
        } catch {
          case e => _value = Some(Left(e))
        }
      }
    }
  }

  override def nowEither(): Option[Either[Throwable, T]] = {
    doLazyActionIfNeeded() // the reason we've overrided nowEither; triggering lazy computation.
    super.nowEither()
  }
}

final case class DependencyCallBack[T](f: Either[Throwable, T] => _)

class MutablePromise[T] extends Promise[T] {
  private val _hookOnSet = new ConcurrentLinkedQueue[DependencyCallBack[T]]
  
  @volatile // may be accessed from different threads.
  var _value: Option[Either[Throwable, T]] = None
  
  final def set(x: => T) = setPromiseEither(Right(x))
  final def fail(ex: => Throwable) = setPromiseEither(Left(ex))


  // Promise methods, may be overloaded in implementations to provide asynchronicity and lazyness 
  def nowEither(): Option[Either[Throwable, T]] = _value
  def foreachEither(f: Either[Throwable, T] => Unit): Unit = {
    _hookOnSet offer DependencyCallBack(f)
    propagate()
  }

  final def setPromiseEither(ev: => Either[Throwable, _ <: T]) = {
    try {  
      _value = Some(ev) // evaluation could throw
    } catch {
      case e: Throwable => _value = Some(Left(e))
    }
    synchronized {
      notifyAll()
    }
    propagate()
    this
  }

  final def propagate(): Unit =
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
            case e => e.printStackTrace
          }
          if (!finished)
            internPropagate // continue to consume the poll
        }
        internPropagate
      case _ =>
    }
 
}


final class PromiseIsMonadPlus[+T](private val outer: Promise[T]) {

  final def foreach(f: T => Unit): Unit =
    outer.foreachEither {
      case Right(x) => f(x)
      case _ =>
    }

  final def map[U](f: T => U): Promise[U] =
    outer.mapEither {
      case Right(x) => Right(f(x))
      case Left(err) => Left(err)
    }

  final def flatMap[U](f: T => Promise[U]): Promise[U] =
    outer.flatMapEither {
      case Right(v) => f(v)
      case _ => outer.asInstanceOf[Promise[U]] // we're abusing the compiler but this is safe
    }

  final def filter(pred: T => Boolean): Promise[T] =
    outer.filterEither {
      case Right(x) => pred(x)
      case _ => false
    }

  final def failure(): Promise[Throwable] =
    outer.mapEither {
      case Left(err) => Right(err)
      case _ => Promise.leftUndefined
    }

}

final class PromiseIsAsynchronous[+T](outer: Promise[T]) {

  def async: Promise[T] = {
    val prom = Promise.async[T]()
    outer.foreachEither { prom setPromiseEither _ }
    prom
  }
 
  final def now(): Option[T] =
    outer.nowEither() match {
      case Some(Right(v)) => Some(v)
      case _ => None
    }
}


final class PromiseCanAbstractFailure[+T](outer: Promise[Either[Throwable, T]]) {
  /**
   * unlift Left(err) to exception, and right value to value
   */
  def unliftEither: Promise[T] = {
    val prom = Promise[T]()
    outer.foreachEither {
      case Right(v) => prom setPromiseEither v
      case Left(err) => prom setPromiseEither Left(err)
    }
    prom
  }
}

final class PromiseCanExposeFailure[+T](outer: Promise[T]) {
  /**
   * lift exception to Left value, if any
   */
  def liftEither: Promise[Either[Throwable, T]] = {
    val prom = Promise[Either[Throwable, T]]()
    outer.foreachEither { prom set _ }
    prom
  }
}


final class PromiseCombinators[+T](outer: Promise[T]) {

  def orElse[U >: T](other: => Promise[U]): Promise[U] = {
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
    prom
  }

  def &&[U](other: => Promise[U]): Promise[(T, U)] = {
    val prom = Promise[(T, U)]()
    outer.foreachEither {
      case Right(t) =>
        try {
          other.foreachEither {
            case Right(u) => prom.set((t, u))
            case Left(err) => prom.setPromiseEither(Left(err)) // we're abusing the compiler here to do not recreate a Left exception value
          }
        } catch {
          case err => prom.setPromiseEither(Left(err)) // caused by 'other' evaluation
        }
      case Left(err) => prom.setPromiseEither(Left(err))
    }
    prom
  }

  /**
   * Returns a promise whose value equals to the first promise fulfilled exactly when it is fulfilled.
   * Returns a failure if both fail (with the last one failure; TODO provide a mean to combine Failures)
   * ex:
   *   connector.connect(inetAddr).toProm() or (Promise.failed[IoSession].delayed(timeout))
   */

  def or[U >: T](other: Promise[U]): Promise[U] = {
    val prom = Promise[U]()
    val status = new java.util.concurrent.atomic.AtomicInteger(0) // 0 -> not set, 1 -> value set (right), 2 -> one error (left)

    val updateAsNeeded: Either[Throwable, U] => Unit = {
      case r: Right[_, _] =>
        if (!status.compareAndSet(1, 1)) {
          prom.setPromiseEither(r)
        }
      case l: Left[_, _] =>
        if (status.compareAndSet(2, 2)) { // we only want to write if we are the second error
          prom.setPromiseEither(l)
        }
    }

    outer.foreachEither(updateAsNeeded)
    other.foreachEither(updateAsNeeded)

    prom
  }

}

// mainly for internal purpose
final class PromiseIsEitherMonadPlus[+T](outer: Promise[T]) {

  final def mapEither[U](f: Either[Throwable, T] => Either[Throwable, U]): Promise[U] = {
    val prom = Promise[U]()
    outer.foreachEither { x => prom setPromiseEither f(x) }
    prom
  }

  final def flatMapEither[U](f: Either[Throwable, T] => Promise[U]): Promise[U] = {
    val prom = Promise[U]()
    outer.foreachEither { eith =>
      try {
        f(eith) foreachEither { prom setPromiseEither _ }
      } catch {
        case e => prom fail e
      }
    }
    prom
  }

  final def filterEither(pred: Either[Throwable, T] => Boolean): Promise[T] = {
    val prom = Promise[T]()
    outer.foreachEither { x => prom.setPromiseEither(if (pred(x)) x else Promise.leftUndefined) }
    prom
  }

}

final class PromiseCanBeUnsafe[+T](outer: Promise[T]) {
  
  final def getOrThrow(): T =
    outer.nowEither() match {
      case Some(Right(v)) => v
      case Some(Left(err)) => throw err
      case _ => throw Promise.undefined
    }  
  
  final def waitUntil(delay: Long) = {
    outer.nowEither(); // to trigger lazyPromises before synchronizing
    synchronized {
      if (outer.nowEither().isEmpty) {
        wait(delay) // could throw an InterruptedException, we're in unsafe land..
      }
    }
    outer
  }
  
  final def waitBefore(delay: Long): Promise[T] = 
    waitUntil(delay).now() match {
      case Some(_) => outer
      case None => Promise.timeoutPromise // not ready yet
    }

  final def waitFulfilled(): Promise[T] =
    waitUntil(0)

  final def waitAndGetUntil(timeOut: Long): Option[T] = {
    waitUntil(timeOut).now()
  }

  final def waitAndGet() = waitFulfilled().now()
}


abstract class Promise[+T] {
  def foreachEither(f: Either[Throwable, T] => Unit): Unit
  def nowEither(): Option[Either[Throwable, T]]
}


class PromiseThreadFactory(threadGroupName: String) extends ThreadFactory {
  import java.util.concurrent.atomic.AtomicInteger

  private val _tg = new ThreadGroup(threadGroupName)
  private var _nb = new AtomicInteger()

  def newThread(r: Runnable) = {
    val thread = new Thread(_tg, r, _tg.getName + "-" + _nb.incrementAndGet())
    thread.setPriority(Thread.MAX_PRIORITY)
    thread.setDaemon(true)
    thread
  }
}

abstract class PromiseThreadPool(_name: String, _threadBaseName: String, _threadPoolSize: Int) {
  import scala.collection._
  import java.util.concurrent.Executors

  val _executor = Executors.newFixedThreadPool(_threadPoolSize, new PromiseThreadFactory(_threadBaseName)).asInstanceOf[java.util.concurrent.ThreadPoolExecutor]

  def newSpawn[T](_function: => T) {
    _executor.execute(
       new Runnable {
        def run() = try {
          _function
        } catch { case e => /*put logging here*/ }
      }    
    )
  }

  def shutdown = _executor.shutdown
}

object PromiseExec extends PromiseThreadPool("PromiseThreadPool", "promise", {
  val threadPoolRatio = 1.0
  scala.math.max(3, threadPoolRatio * Runtime.getRuntime.availableProcessors).toInt
}) {
  val _IOexecutor = Executors.newFixedThreadPool(1, new PromiseThreadFactory("io")).asInstanceOf[java.util.concurrent.ThreadPoolExecutor]
}
