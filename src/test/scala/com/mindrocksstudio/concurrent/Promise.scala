package com.mindrocksstudio.concurrent

import PromiseImplicits._
import PromiseUnsafeImplicits._


import org.specs2.mutable._

class PromiseSpec extends Specification {

  "Promise or method" should {
    "respect ordering and failures" in {
            
	    def resultFor(v1 : Either[Throwable, String], v2 : Either[Throwable, String], invert : Boolean = false) = {
			val p1 = Promise[String]();
			val p2 = Promise[String]();    
			val res = p1.or(p2)
						
			if (invert) {
			    p2.setPromiseEither(v2)		    	  
			    p1.setPromiseEither(v1)
			} else {
			    p1.setPromiseEither(v1)
			    p2.setPromiseEither(v2)		    	  
			}
			res.waitAndGet()
	    }
	
	    val error = Left(new Exception("error"))
	    val valueA = Right("A")
	    val valueB = Right("B")
	    
	    resultFor(valueA, valueB) mustEqual Some("A")
	    resultFor(valueA, error) mustEqual Some("A")
	    resultFor(error, valueA) mustEqual Some("A")
	    resultFor(error, error) mustEqual None
	    
	    resultFor(valueA, valueB, true) mustEqual Some("B")
	    resultFor(valueA, error, true) mustEqual Some("A")
	    resultFor(error, valueA, true) mustEqual Some("A")
	    resultFor(error, error, true) mustEqual None
    }
    
  }
}



