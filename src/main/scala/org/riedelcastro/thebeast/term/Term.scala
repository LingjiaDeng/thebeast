package org.riedelcastro.thebeast.term


import semiring.{TropicalSemiring, RealSemiring}
import scorer.{Sum, TermEq, ScorerPredef, Weight}

/**
 * @author Sebastian Riedel
 */

trait Values[+T] extends Iterable[T]

object Values {
  def apply[T](values: T*) =
    new ValuesProxy(values.foldLeft(Set.empty[T]){(result, v) => result ++ Set(v)})
}

class ValuesProxy[+T](override val self: Iterable[T]) extends Values[T] with IterableProxy[T]

case class FunctionValues[T, +R](val domain: Values[T], val range: Values[R]) extends Values[T => R] {
  def elements = AllFunctions(domain.toStream, range.toStream).elements

}

sealed trait Term[+T] {
  /**
   * A puppet of a term t is a EnvVar p so that there exists at least one Env e so that for every possible value
   * v in values(t) holds:      { e + (p->v) }.eval(t) = v
   */
  def puppets: Iterable[EnvVar[T]]

  /**
   * The domain of a term is the set of all (most atomic) puppets that can affect the evaluation of the term
   */
  def domain: Iterable[EnvVar[Any]]

  /**
   * The values of a term are all objects the term can be evaluated to
   */
  def values: Values[T]

  /**
   * Returns a term where each function application of a constant function with a constant argument
   * is replaced by a constant representing the application result 
   */
  def simplify: Term[T]

}

case class Constant[+T](val value: T) extends Term[T] {
  def puppets = Set.empty


  def domain = Set.empty

  def values = Values(value)


  def simplify = this
}

case class Var[+T](val name: String, override val values: Values[T]) extends Term[T] with EnvVar[T] {
  def puppets: Iterable[EnvVar[T]] = Set(this)

  def domain: Iterable[EnvVar[T]] = Set(this)


  def simplify = this
}

case class FunApp[T, +R](val function: Term[T => R], val arg: Term[T]) extends Term[R] {
  def domain = puppets ++ arg.domain

  def puppets = function.puppets.flatMap(f => arg.values.map(v => FunAppVar(f, v)))

  def values =
    function.values match {
      case functions: FunctionValues[_, _] => functions.range
      case _ => new ValuesProxy(function.values.flatMap(f => arg.values.map(v => f(v))))
    }


  def simplify =
    function.simplify match {
      case Constant(f) => arg.simplify match {
        case Constant(x) => Constant(f(x));
        case x => FunApp(Constant(f), x)
      }
      case f => FunApp(f, arg.simplify)
    }

}

sealed trait EnvVar[+T] {
}

case class FunAppVar[T, +R](val funVar: EnvVar[T => R], val arg: T) extends EnvVar[R] {
  def of[U](arg: U) = FunAppVar(this.asInstanceOf[EnvVar[U => Any]], arg)
}

trait Env {
  def apply[T](term: Term[T]): T = eval(term).get

  def eval[T](term: Term[T]): Option[T] = {
    term match {
      case Constant(x) => Some(x)
      case v: Var[_] => resolveVar[T](v)
      case FunApp(funTerm, argTerm) =>
        {
          val fun = eval(funTerm);
          val arg = eval(argTerm);
          if (fun.isDefined && arg.isDefined) Some(fun.get(arg.get)) else None
        }
    }
  }

  def ground[T](term: Term[T]): Term[T] = {
    term match {
      case FunApp(f, arg) => FunApp(ground(f), ground(arg))
      case c: Constant[_] => c
      case v: Var[_] => {val x = eval(v); if (x.isDefined) Constant(x.get) else v}
    }
  }

  def resolveVar[T](variable: Var[T]): Option[T]
}

class MutableEnv extends Env {
  private[this] type MutableMap = scala.collection.mutable.HashMap[Any, Any]
  private[this] val values = new MutableMap

  def resolveVar[T](variable: Var[T]) = values.get(variable).asInstanceOf[Option[T]]

  private[this] def getMap(variable: EnvVar[Any]): MutableMap = {
    variable match {
      case v: Var[_] => values.getOrElseUpdate(v, new MutableMap()).asInstanceOf[MutableMap]
      case FunAppVar(funVar, arg) => getMap(funVar).getOrElseUpdate(arg, new MutableMap()).asInstanceOf[MutableMap]
    }
  }

  def set[T](variable: EnvVar[T], value: T) {
    variable match {
      case v: Var[_] => values += Tuple2[Any, Any](v, value)
      case FunAppVar(funVar, arg) => getMap(funVar) += Tuple2[Any, Any](arg, value)
    }
  }

  def +=[T](mapping: Tuple2[EnvVar[T], T]) = set(mapping._1, mapping._2)

}

trait TheBeastEnv extends ScorerPredef {
  implicit def string2varbuilder(name: String) = new {
    def in[T](values: Values[T]) = Var(name, values)

    //def in[T, R](values: FunctionValues[T, R]) = FunVar(name, values)
  }


  implicit def value2constant[T](value: T) = Constant(value)

  case class FunAppVarBuilder[T, R](val funvar: EnvVar[T => R]) {
    def of(t: T) = FunAppVar(funvar, t)
  }

  implicit def funvar2funAppVarBuilder[T, R](funvar: EnvVar[T => R]) = FunAppVarBuilder(funvar)

  implicit def term2funAppBuilder[T, R](fun: Term[T => R]) = new (Term[T] => FunApp[T, R]) {
    def apply(t: Term[T]) = FunApp(fun, t)
  }

  implicit def term2eqBuilder[T](lhs: Term[T]) = new {
    def ===(rhs: Term[T]) = TermEq(lhs, rhs)
  }

  implicit def bool2termEq[T](term: Term[Boolean]) = TermEq(term, Constant(true))

  implicit def values2FunctionValuesBuilder[T, R](domain: Values[T]): FunctionValuesBuilder[T, R] =
    FunctionValuesBuilder[T, R](domain)

  case class FunctionValuesBuilder[T, R](domain: Values[T]) {
    def ->[R](range: Values[R]) = new FunctionValues(domain, range)
  }

  def ^[T](t: T) = Constant(t)

  //  implicit def term2envVar[T](term:Term[T]): EnvVar[T] = {
  //    term match {
  //      case FunApp(f,Constant(v)) => FunAppVar(term2envVar(f),v)
  //      case _=> null
  //    }
  //  }

  implicit def intTerm2IntAppBuilder(lhs: Term[Int]) = new {
    def +(rhs: Term[Int]) = FunApp(FunApp(Constant(Add), lhs), rhs)
  }

}

object Add extends (Int => (Int => Int)) {
  def apply(arg1: Int): (Int => Int) = (arg2: Int) => arg1 + arg2
}



object Example extends Application with TheBeastEnv {
  val Ints = Values(1, 2, 3)
  val Bools = Values(true, false)
  val b = "b" in Bools
  val x = "x" in Ints
  val f = "f" in Ints -> Ints
  val pred = "pred" in Ints -> Bools
  val k = "k" in Ints -> (Ints -> Ints)
  val env = new MutableEnv
  println(env.eval(x))
  env += x -> 1
  env += (f of 1) -> 2
  env += (f of 2) -> 3
  env += ((k of 1) of 2) -> 3
  println(env.eval(x))
  println(env(FunApp(f, 1)))
  println(env(f(f(x))))
  println(env(k(1)(2)))
  println(env(Add))
  println(env(^(Add)(x)(1)))
  println(env(^(1) + x))

  val model = scorer.RealPlus(Seq(
    %(2.0) * $(f(x) === 1),
    %(1.0) * $(x === 0),
    %(1.5) * $(b & pred(x))
    ))
  println(f(x) === 1)
  println($(f(x) === 1) * Weight(2.0))
  println(($(f(x) === 2) * Weight(2.0)).score(env))

  println(f(x).domain)
  //val env = MutableEnv
  //val f = "f" in FunctionValues(Set(1,2,3),Set(1,2))
  //env += (f->Map(1->2))
  //env += (f(1)->2)

}