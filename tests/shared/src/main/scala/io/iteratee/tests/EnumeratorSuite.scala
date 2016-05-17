package io.iteratee.tests

import algebra.Eq
import algebra.laws.GroupLaws
import cats.Monad
import cats.laws.discipline.{ CartesianTests, MonadTests }
import io.iteratee.{ EnumerateeModule, Enumerator, EnumeratorModule, IterateeModule, Module }

abstract class EnumeratorSuite[F[_]: Monad] extends ModuleSuite[F] {
  this: Module[F] with EnumerateeModule[F] with EnumeratorModule[F] with IterateeModule[F] =>

  type EnumeratorF[E] = Enumerator[F, E]

  implicit val isomorphisms: CartesianTests.Isomorphisms[EnumeratorF] =
    CartesianTests.Isomorphisms.invariant[EnumeratorF]

  checkLaws(s"Enumerator[$monadName, Int]", GroupLaws[Enumerator[F, Int]].monoid)
  checkLaws(s"Enumerator[$monadName, Int]", MonadTests[EnumeratorF].monad[Int, Int, Int])

  "liftToEnumerator" should "lift a value in a context into an enumerator" in forAll { (i: Int) =>
    assert(liftToEnumerator(F.pure(i)).toVector === F.pure(Vector(i)))
  }

  "empty" should "not enumerate any values" in {
    assert(empty[Int].toVector === F.pure(Vector.empty))
  }

  "enumOne" should "enumerate a single value" in forAll { (i: Int) =>
    assert(enumOne(i).toVector === F.pure(Vector(i)))
  }

  "enumStream" should "enumerate values from a stream" in forAll { (xs: Stream[Int]) =>
    assert(enumStream(xs).toVector === F.pure(xs.toVector))
  }

  "enumList" should "enumerate values from a list" in forAll { (xs: List[Int]) =>
    assert(enumList(xs).toVector === F.pure(xs.toVector))
  }

  "enumVector" should "enumerate values from a vector" in forAll { (xs: Vector[Int]) =>
    assert(enumVector(xs).toVector === F.pure(xs))
  }

  it should "enumerate a vector with a single element" in forAll { (x: Int) =>
    assert(enumVector(Vector(x)).toVector === F.pure(Vector(x)))
  }

  "enumIndexedSeq" should "enumerate a slice of values from an indexed sequence" in {
    forAll { (xs: Vector[Int], start: Int, count: Int) =>
      assert(enumIndexedSeq(xs, start, start + count).toVector === F.pure(xs.slice(start, start + count)))
    }
  }

  it should "enumerate a slice of the first hundred values from an indexed sequence" in {
    forAll { (xs: Vector[Int]) =>
      assert(enumIndexedSeq(xs, 0, 100).toVector === F.pure(xs.slice(0, 100)))
    }
  }

  "repeat" should "repeat a value" in forAll { (i: Int, count: Short) =>
    assert(repeat(i).run(takeI(count.toInt)) === F.pure(Vector.fill(count.toInt)(i)))
  }

  "iterate" should "enumerate values by applying a function iteratively" in forAll { (n: Int, count: Short) =>
    assert(iterate(n)(_ + 1).run(takeI(count.toInt)) === F.pure(Vector.iterate(n, count.toInt)(_ + 1)))
  }

  "iterateM" should "enumerate values by applying a pure function iteratively" in {
    forAll { (n: Int, count: Short) =>
      assert(iterateM(n)(i => F.pure(i + 1)).run(takeI(count.toInt)) === F.pure(Vector.iterate(n, count.toInt)(_ + 1)))
    }
  }

  "iterateUntil" should "apply a function until it returns an empty result" in forAll { (n: Short) =>
    val count = math.abs(n.toInt)
    val enumerator = iterateUntil(0)(i => if (i == count) None else Some(i + 1))

    assert(enumerator.toVector === F.pure((0 to count).toVector))
  }

  it should "work with finished iteratee (#71)" in forAll { (n: Short, fewer: Byte) =>
    val count = math.abs(n.toInt)
    val taken = n - math.abs(fewer.toInt)
    val enumerator = iterateUntil(0)(i => if (i == count) None else Some(i + 1))

    assert(enumerator.run(takeI(taken)) === F.pure((0 to count).toVector.take(taken)))
  }

  "iterateUntilM" should "apply a pure function until it returns an empty result" in forAll { (n: Short) =>
    val count = math.abs(n.toInt)
    val enumerator = iterateUntilM(0)(i => F.pure(if (i == count) None else Some(i + 1)))

    assert(enumerator.toVector === F.pure((0 to count).toVector))
  }

  it should "work with finished iteratee (#71)" in forAll { (n: Short, fewer: Byte) =>
    val count = math.abs(n.toInt)
    val taken = n - math.abs(fewer.toInt)
    val enumerator = iterateUntilM(0)(i => F.pure(if (i == count) None else Some(i + 1)))

    assert(enumerator.run(takeI(taken)) === F.pure((0 to count).toVector.take(taken)))
  }

  "toVector" should "collect all the values in the stream" in forAll { (eav: EnumeratorAndValues[Int]) =>
    assert(eav.enumerator.toVector === F.pure(eav.values))
  }

  "prepend" should "prepend a value to a stream" in forAll { (eav: EnumeratorAndValues[Int], v: Int) =>
    assert(eav.enumerator.prepend(v).toVector === F.pure(v +: eav.values))
  }

  it should "work with a done iteratee" in {
    assert(enumOne(0).append(enumOne(2).prepend(1)).run(head) === F.pure((Some(0))))
  }

  "bindM" should "bind through Option" in forAll { (eav: EnumeratorAndValues[Int]) =>
    /**
     * Workaround for divergence during resolution on 2.10.
     */
    val E: Eq[F[Option[F[Vector[String]]]]] = eqF(eqOption(eqF(eqVector(stringOrder))))
    val enumeratorF: F[Option[Enumerator[F, String]]] = eav.enumerator.bindM(v => Option(enumOne(v.toString)))

    assert(E.eqv(enumeratorF.map(_.map(_.toVector)), F.pure(Option(F.pure(eav.values.map(_.toString))))))
  }

  "intoEnumerator" should "be available on values in a context" in forAll { (i: Int) =>
    import syntax._

    assert(F.pure(i).intoEnumerator.toVector === F.pure(Vector(i)))
  }

  "flatten" should "collapse enumerated values in the context" in forAll { (v: Int) =>
    assert(enumOne(F.pure(v)).flatten[Int].toVector === F.pure(Vector(v)))
  }

  "reduced" should "reduce the stream with a function" in forAll { (eav: EnumeratorAndValues[Int]) =>
    assert(eav.enumerator.reduced(Vector.empty[Int])(_ :+ _).toVector === F.pure(Vector(eav.values)))
  }

  it should "reduce the stream with a pure function" in forAll { (eav: EnumeratorAndValues[Int]) =>
    assert(eav.enumerator.reducedM(Vector.empty[Int])((i, s) => F.pure(i :+ s)).toVector === F.pure(Vector(eav.values)))
  }

  "map" should "transform the stream" in forAll { (eav: EnumeratorAndValues[Int]) =>
    assert(eav.enumerator.map(_ + 1).toVector === F.pure(eav.values.map(_ + 1)))
  }

  "flatMapM" should "transform the stream with a pure effectful function" in forAll { (eav: EnumeratorAndValues[Int]) =>
    assert(eav.enumerator.flatMapM(i => F.pure(i + 1)).toVector === F.pure(eav.values.map(_ + 1)))
  }

  "flatMap" should "transform the stream with a function into enumerators" in {
    forAll { (eav: EnumeratorAndValues[Int]) =>
      val enumerator = eav.enumerator.flatMap(v => enumVector(Vector(v, v)))

      assert(enumerator.toVector === F.pure(eav.values.flatMap(v => Vector(v, v))))
    }
  }
}