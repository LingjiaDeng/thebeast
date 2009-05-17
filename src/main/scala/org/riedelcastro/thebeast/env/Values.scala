package org.riedelcastro.thebeast.env
/**
 * @author Sebastian Riedel
 */
trait Values[+T] extends Iterable[T] {
  def defaultValue:T = elements.next
  def createVariable(name:String):Var[T] = new Var(name, this)
}

object Values {
  def apply[T](values: T*) =
    new ValuesProxy(values.foldLeft(Set.empty[T]){(result, v) => result ++ Set(v)})
}

class ValuesProxy[+T](override val self: Iterable[T]) extends Values[T] with IterableProxy[T]

case class FunctionValues[T, +R](val domain: Values[T], val range: Values[R]) extends Values[T => R] {
  def elements = AllFunctions(domain.toStream, range.toStream).elements

  override def defaultValue = (t:T) => range.defaultValue
}