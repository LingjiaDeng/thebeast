package thebeast.nodmem.variable;

import thebeast.nod.expression.ExpressionVisitor;
import thebeast.nod.type.ArrayType;
import thebeast.nod.value.ArrayValue;
import thebeast.nod.variable.ArrayVariable;
import thebeast.nod.NoDServer;
import thebeast.nodmem.mem.MemChunk;
import thebeast.nodmem.mem.MemVector;
import thebeast.nodmem.mem.MemDim;
import thebeast.nodmem.type.MemArrayType;
import thebeast.nodmem.type.AbstractMemType;
import thebeast.nodmem.value.MemArray;

import java.util.Arrays;

/**
 * @author Sebastian Riedel
 */
public class MemArrayVariable extends AbstractMemVariable<ArrayValue, ArrayType> implements ArrayVariable {

  public MemArrayVariable(NoDServer server, ArrayType type) {
    super(server, type, new MemChunk(1, 1, MemDim.CHUNK_DIM));
    chunk.chunkData[0] = new MemChunk(0, 0, ((AbstractMemType) type.instanceType()).getDim());
  }

  public MemArrayVariable(NoDServer server, ArrayType type, int size) {
    super(server, type, new MemChunk(1, 1, MemDim.CHUNK_DIM));
    chunk.chunkData[0] = new MemChunk(size, size, ((AbstractMemType) type.instanceType()).getDim());
  }


  public void destroy() {

  }

  public boolean copy(AbstractMemVariable other) {
    chunk.chunkData[pointer.xChunk] = other.chunk.chunkData[other.pointer.xChunk].copy();
    return true;
  }

  public ArrayValue value() {
    return new MemArray(chunk.chunkData[0], new MemVector(), (MemArrayType) type);
  }


  public void acceptExpressionVisitor(ExpressionVisitor visitor) {
    visitor.visitArrayVariable(this);
  }

  public int byteSize() {
    return chunk.chunkData[pointer.xChunk].byteSize();
  }

  public double doubleValue(int index) {
    return chunk.chunkData[pointer.xChunk].doubleData[index];
  }

  public void setDoubleArray(double[] array) {
    MemChunk memChunk = chunk.chunkData[pointer.xChunk];
    memChunk.ensureCapacity(array.length);
    System.arraycopy(array,0,memChunk.doubleData,0,array.length);
    memChunk.size = array.length;
  }

  public void fill(double value, int howmany) {
    MemChunk memChunk = chunk.chunkData[pointer.xChunk];
    memChunk.ensureCapacity(howmany);
    Arrays.fill(memChunk.doubleData, 0, howmany, value);
    memChunk.size = howmany;

  }
}