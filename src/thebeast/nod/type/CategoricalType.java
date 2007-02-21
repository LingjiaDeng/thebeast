package thebeast.nod.type;

import thebeast.nod.value.CategoricalValue;

import java.util.List;

/**
 * @author Sebastian Riedel
 */
public interface CategoricalType extends ScalarType {

  List<CategoricalValue> values();

  CategoricalValue value(String representation);

  CategoricalValue value(int index);

  int index(String represenation);

  boolean unknowns();

}
