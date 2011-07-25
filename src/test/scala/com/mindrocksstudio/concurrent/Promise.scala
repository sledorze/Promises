package com.mindrocksstudio.concurrent

import PromiseImplicits._
import PromiseUnsafeImplicits._


import org.specs2.mutable._

class PromiseSpec extends Specification {

  "Promise" should {
    
    "throw when unsafely accessed empty" in {
      val prom = Promise[Int]()
      prom.getOrThrow() must throwA[Exception]
    }
    
    "return the value it was initialized with" in {
      val prom = Promise(5)
      prom.getOrThrow() mustEqual 5
    }

    "return the value it was binded to" in {
      val prom = Promise[Int]()
      prom.set(5)
      prom.getOrThrow() mustEqual 5
    }


    
    "have a working foreachEither when binded before being set" in {
      val prom = Promise[Int]()
      var evidence = 0
      val res = prom.foreachEither{
        case Left(err) => evidence = 1
        case Right(v) => evidence = v
      }
      prom.set(5)
      evidence mustEqual 5
    }

    "have a working foreachEither when binded after being set" in {
      val prom = Promise[Int]()
      var evidence = 0
      prom.set(5)
      val res = prom.foreachEither{
        case Left(err) => evidence = 1
        case Right(v) => evidence = v
      }
      evidence mustEqual 5
    }

    "have a working foreachEither when binded after having failed" in {
      val prom = Promise[Int]()
      var evidence = 0
      val res = prom.foreachEither{
        case Left(err) => evidence = 1
        case Right(v) => evidence = v
      }
      prom.fail(new Exception("error"))
      evidence mustEqual 1
    }

    "have a working foreachEither when binded after having failed" in {
      val prom = Promise[Int]()
      var evidence = 0
      prom.fail(new Exception("error"))
      val res = prom.foreachEither{
        case Left(err) => evidence = 1
        case Right(v) => evidence = v
      }
      evidence mustEqual 1
    }

    "have a working mapEither when binded after being set" in {
      val prom = Promise[Int]()
      prom.set(5)
      val res = prom.mapEither{
        case Left(err) => Left(err)
        case Right(v) => Right(v+1)
      }
      res.getOrThrow() mustEqual 6
    }

    "have a working mapEither when binded before being set" in {
      val prom = Promise[Int]()
      val res = prom.mapEither{
        case Left(err) => Left(err)
        case Right(v) => Right(v+1)
      }
      prom.set(5)
      res.getOrThrow() mustEqual 6
    }

    "have a working mapEither when binded after having failed" in {
      val prom = Promise[Int]()
      val res = prom.mapEither{
        case Left(err) => Left(err)
        case Right(v) => Right(v+1)
      }
      prom.fail(new Exception("error"))
      res.getOrThrow() must throwA[Exception]
    }

    "have a working mapEither when binded after having failed" in {
      val prom = Promise[Int]()
      prom.fail(new Exception("error"))
      val res = prom.mapEither{
        case Left(err) => Left(err)
        case Right(v) => Right(v+1)
      }
      res.getOrThrow() must throwA[Exception]
    }

    "have a working flatMapEither when binded before being set" in {
      val prom = Promise[Int]()
      prom.set(5)
      val res = prom.flatMapEither{
        case Left(err) => Promise.failure(err)
        case Right(v) => Promise(v+1)
      }
      res.getOrThrow() mustEqual 6
    }

    "have a working flatMapEither when binded after being set" in {
      val prom = Promise[Int]()
      val res = prom.flatMapEither{
        case Left(err) => Promise.failure(err)
        case Right(v) => Promise(v+1)
      }
      prom.set(5)
      res.getOrThrow() mustEqual 6
    }

    "have a working flatMapEither when binded after having failed" in {
      val prom = Promise[Int]()
      val res = prom.mapEither{
        case Left(err) => Left(err)
        case Right(v) => Right(v+1)
      }
      prom.fail(new Exception("error"))
      res.getOrThrow() must throwA[Exception]
    }

    "have a working flatMapEither when binded before having failed" in {
      val prom = Promise[Int]()
      prom.fail(new Exception("error"))
      val res = prom.mapEither{
        case Left(err) => Left(err)
        case Right(v) => Right(v+1)
      }
      res.getOrThrow() must throwA[Exception]
    }
    
    "have a working foreach when binded before being set" in {
      val prom = Promise[Int]()
      var evidence = 0
      val res = prom.foreach{x => evidence = x + 1}
      prom.set(5)
      evidence mustEqual 6
    }

    "have a working foreach when binded after being set" in {
      val prom = Promise[Int]()
      var evidence = 0
      prom.set(5)
      val res = prom.foreach{x => evidence = x + 1}
      evidence mustEqual 6
    }

    "have a working foreach when binded after having failed" in {
      val prom = Promise[Int]()
      var evidence = 0
      prom.fail(new Exception("error"))
      val res = prom.foreach{x => evidence = x + 1}
      evidence mustEqual 0
    }

    "have a working foreach when binded after having failed" in {
      val prom = Promise[Int]()
      var evidence = 0
      val res = prom.foreach{x => evidence = x + 1}
      prom.fail(new Exception("error"))
      evidence mustEqual 0
    }

    "have a working map when binded after being set" in {
      val prom = Promise[Int]()
      prom.set(5)
      val res = prom.map{x => x + 1}
      res.getOrThrow() mustEqual 6
    }

    "have a working map when binded before being set" in {
      val prom = Promise[Int]()
      val res = prom.map{x => x + 1}
      prom.set(5)
      res.getOrThrow() mustEqual 6
    }

    "have a working map when binded after having failed" in {
      val prom = Promise[Int]()
      prom.fail(new Exception("error"))
      val res = prom.map{x => x + 1}
      res.getOrThrow() must throwA[Exception]
    }

    "have a working map when binded after having failed" in {
      val prom = Promise[Int]()
      val res = prom.map{x => x + 1}
      prom.fail(new Exception("error"))
      res.getOrThrow() must throwA[Exception]
    }

    "have a working flatMap when binded before being set" in {
      val prom = Promise[Int]()
      val res = prom.flatMap{x => Promise(x + 1)}
      prom.set(5)
      res.getOrThrow() mustEqual 6
    }

    "have a working flatMap when binded after being set" in {
      val prom = Promise[Int]()
      prom.set(5)
      val res = prom.flatMap{x => Promise(x + 1)}
      res.getOrThrow() mustEqual 6
    }

    "have a working flatMap when binded after having failed" in {
      val prom = Promise[Int]()
      prom.fail(new Exception("error"))
      val res = prom.flatMap{x => Promise(x + 1)}
      res.getOrThrow() must throwA[Exception]
    }

    "have a working flatMap when binded before having failed" in {
      val prom = Promise[Int]()
      val res = prom.flatMap{x => Promise(x + 1)}
      prom.fail(new Exception("error"))
      res.getOrThrow() must throwA[Exception]
    }
    
    
    def testCombinator[T](v1 : Either[Throwable, String], v2 : Either[Throwable, String], comb : (Promise[String], Promise[String]) => Promise[T], expected : Option[Either[Throwable, T]], invert : Boolean = false) = {
		val p1 = Promise[String]();
		val p2 = Promise[String]();    
		val res = comb(p1, p2)
					
		if (invert) {
		    p2.setPromiseEither(v2)		    	  
		    p1.setPromiseEither(v1)
		} else {
		    p1.setPromiseEither(v1)
		    p2.setPromiseEither(v2)		    	  
		}
		res.waitUntil(1)
		res.nowEither() mustEqual expected
    }    

    "'&&' method return the right pair" in {
    	val errorA = Left(new Exception("errorA"))
	    val errorB = Left(new Exception("errorB"))
	    val valueA = Right("A")
	    val valueB = Right("B")	    
	    val valueAAndB = Right("A" -> "B")
	    
	    val combineAnd = {(a : Promise[String], b : Promise[String]) => a.&&(b) }
	    
	    testCombinator(valueA, valueB, combineAnd, Some(valueAAndB))
	    testCombinator(valueA, errorB, combineAnd, Some(errorB))
	    testCombinator(errorA, valueB, combineAnd, Some(errorA))
	    testCombinator(errorA, errorB, combineAnd, Some(errorA))

	    testCombinator(valueA, valueB, combineAnd, Some(valueAAndB), true)
	    testCombinator(valueA, errorB, combineAnd, Some(errorB), true)
	    testCombinator(errorA, valueB, combineAnd, Some(errorA), true)
	    testCombinator(errorA, errorB, combineAnd, Some(errorA), true)
    }

    "'getOrElse' returns the first element or the second if the first fails" in {           	    
	    val errorA = Left(new Exception("errorA"))
	    val errorB = Left(new Exception("errorB"))
	    val valueA = Right("A")
	    val valueB = Right("B")

	    val combineOrElse = {(a : Promise[String], b : Promise[String]) => a.orElse(b) }

	    testCombinator(valueA, valueB, combineOrElse, Some(valueA))
	    testCombinator(valueA, errorB, combineOrElse, Some(valueA))
	    testCombinator(errorA, valueB, combineOrElse, Some(valueB))
	    testCombinator(errorA, errorB, combineOrElse, Some(errorB))
	    
	    testCombinator(valueA, valueB, combineOrElse, Some(valueA), true)
	    testCombinator(valueA, errorB, combineOrElse, Some(valueA), true)
	    testCombinator(errorA, valueB, combineOrElse, Some(valueB), true)
	    testCombinator(errorA, errorB, combineOrElse, Some(errorB), true)
    }
    
    "'or' method respect ordering and failures" in {
	    val errorA = Left(new Exception("errorA"))
	    val errorB = Left(new Exception("errorB"))
	    val valueA = Right("A")
	    val valueB = Right("B")

	    val combineOr = {(a : Promise[String], b : Promise[String]) => a.or(b) }
	    
	    testCombinator(valueA, valueB, combineOr, Some(valueA))
	    testCombinator(valueA, errorB, combineOr, Some(valueA))
	    testCombinator(errorA, valueB, combineOr, Some(valueB))
	    testCombinator(errorA, errorB, combineOr, Some(errorB))
	    
	    testCombinator(valueA, valueB, combineOr, Some(valueB), true)
	    testCombinator(valueA, errorB, combineOr, Some(valueA), true)
	    testCombinator(errorA, valueB, combineOr, Some(valueB), true)
	    testCombinator(errorA, errorB, combineOr, Some(errorA), true)
    }
    
  }
}



