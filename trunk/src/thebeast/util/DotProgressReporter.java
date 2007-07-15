package thebeast.util;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * Created by IntelliJ IDEA. User: s0349492 Date: 19-Feb-2007 Time: 17:34:18
 */
public class DotProgressReporter implements PerformanceProgressReporter {

  private PrintStream out;
  private int dotInterval;
  private int chunkInterval, lineInterval;
  private int count;
  private StopWatch stopWatch = new StopWatch();
  private double loss;
  private boolean performanceAvailable;
  private int iterationCount;
  private double[] values;
  private String[] names, formats;

  public DotProgressReporter(PrintStream out, int stepsPerDot, int dotsPerChunk, int chunksPerLine) {
    this.out = out;
    dotInterval = stepsPerDot;
    chunkInterval = stepsPerDot * dotsPerChunk;
    lineInterval = stepsPerDot * dotsPerChunk * chunksPerLine;
    values = new double[0];
    names = new String[0];
    formats = new String[0];
  }

  public void started() {
    count = 0;
    loss =  0;
    iterationCount = 0;
    Arrays.fill(values,0.0);
    stopWatch.start();
  }

  public void started(String name) {
    started();
    out.println("Started " + name + ":");
  }

  public void setColumns(String ... names){
    values = new double[names.length];
    this.names = new String[names.length];
    System.arraycopy(names,0,this.names,0,names.length);
    formats = new String[names.length];
    Arrays.fill(formats,"%3.3f");
  }

  public void progressed(double ... values){
    for (int i = 0; i < values.length;++i){
      this.values[i] += values[i];
    }
    progressed();
  }

  public void progressed() {
    if (count % dotInterval == dotInterval - 1) out.print(".");
    if (count % chunkInterval == chunkInterval - 1) out.print(" ");
    if (count % lineInterval == lineInterval - 1) {
      out.printf("%6d", count + 1);
      for (int i = 0; i < values.length; ++i){
        out.printf(formats[i] + " ",values[i] / count);
      }
      if (performanceAvailable){
        out.printf(" %3.3f %3.2f", loss / count, (double) iterationCount / count);
      } 
      out.print(" " + Util.toMemoryString(Runtime.getRuntime().totalMemory()));
      out.println();

      //System.gc();
      //System.out.println(TheBeast.getInstance().getNodServer().interpreter().getMemoryString());
    }
    ++count;

  }

  public void progressed(double loss, int iterationCount) {
    performanceAvailable = true;
    this.loss += loss;
    this.iterationCount += iterationCount;
    progressed();
  }

  public void finished() {
    if (count == 0){
      out.println("No instances processed.");
      return;
    }
    long time = stopWatch.stopAndContinue();
    out.println();
    out.printf("%-15s%-6d\n", "Processed:", count);
    out.printf("%-15s%-6s\n", "Time:", Util.toTimeString(time));
    out.printf("%-15s%-6s\n", "Avg. time:", Util.toTimeString(time/count));
    out.printf("%-15s%-6s\n", "Memory use:", Util.toMemoryString(Runtime.getRuntime().totalMemory()));
    for (int i = 0; i < values.length; ++i){
      out.printf("%-15s%" + formats[i], names[i], values[i] / count);      
    }
    if (performanceAvailable){
      out.printf("%-15s%-6.3f\n", "Loss: ", loss / count);
      out.printf("%-15s%-6.2f\n", "Iterations: ", (double) iterationCount / count);
    }
  }
}
