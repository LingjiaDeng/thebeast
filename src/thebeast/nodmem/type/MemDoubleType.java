package thebeast.nodmem.type;

import thebeast.nod.identifier.Name;
import thebeast.nod.type.DoubleType;
import thebeast.nod.type.TypeVisitor;
import thebeast.nod.value.DoubleValue;
import thebeast.nod.value.Value;
import thebeast.nod.exception.NoDValueNotInTypeException;
import thebeast.nodmem.mem.MemChunk;
import thebeast.nodmem.mem.MemVector;
import thebeast.nodmem.value.AbstractMemValue;
import thebeast.nodmem.value.MemDouble;
import thebeast.nodmem.identifier.MemName;

import java.io.StreamTokenizer;
import java.io.IOException;

/**
 * @author Sebastian Riedel
 */
public class MemDoubleType extends AbstractScalarType implements DoubleType {

  private double from, to;

  public static final MemDoubleType DOUBLE =
          new MemDoubleType(new MemName("double"), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

  public MemDoubleType(Name name, double from, double to) {
    super(name, DataType.DOUBLE);
    setNumDoubleCols(1);
    this.to = to;
    this.from = from;
  }

  public void acceptTypeVisitor(TypeVisitor visitor) {
    visitor.visitDoubleType(this);
  }

  public double from() {
    return from;
  }

  public double to() {
    return to;
  }


  public DoubleValue value(double value) {
    if (value < from || value >= to) throw new NoDValueNotInTypeException(this, value);
    MemChunk chunk = new MemChunk(1, 1, 0, 1, 0);
    chunk.doubleData[0] = value;
    return new MemDouble(chunk, 0, this);
  }

  public Value emptyValue() {
    return value(0);
  }

  public AbstractMemValue valueFromChunk(MemChunk chunk, MemVector pointer) {
    return new MemDouble(chunk, pointer.xDouble, this);
  }

  public void load(StreamTokenizer src, MemChunk dst, MemVector ptr) throws IOException {
    src.nextToken();
    dst.doubleData[ptr.xDouble] = src.nval;
  }
}
