package thebeast.pml.parser;

import jline.ConsoleReader;
import jline.SimpleCompletor;
import thebeast.nod.FileSink;
import thebeast.nod.FileSource;
import thebeast.pml.*;
import thebeast.pml.corpora.*;
import thebeast.pml.formula.*;
import thebeast.pml.function.*;
import thebeast.pml.predicate.*;
import thebeast.pml.solve.CuttingPlaneSolver;
import thebeast.pml.term.*;
import thebeast.pml.training.*;
import thebeast.util.*;

import java.io.*;
import java.util.*;

/**
 * The Shell processes PML commands either in an active mode from standard in or directly from a file or any other input
 * stream.
 */
public class Shell implements ParserStatementVisitor, ParserFormulaVisitor, ParserTermVisitor {

  private Model model;
  private Signature signature;
  private BooleanFormula formula;
  private Term term;
  private Stack<Type> typeContext = new Stack<Type>();
  private Stack<HashMap<String, Variable>> variables = new Stack<HashMap<String, Variable>>();
  private InputStream in;
  private PrintStream out;
  private PrintStream err;
  private ParserFactorFormula rootFactor;
  private StopWatch stopWatch = new StopWatch();

  private HashMap<String, CorpusFactory> corpusFactories = new HashMap<String, CorpusFactory>();
  private HashMap<String, TypeGenerator> typeGenerators = new HashMap<String, TypeGenerator>();
  private HashMap<String, CorpusEvaluationFunction> evalFunctions = new HashMap<String, CorpusEvaluationFunction>();
  private String evalFunction = null;

  private GroundAtoms guess, gold;
  private Corpus corpus;
  private RandomAccessCorpus ramCorpus;
  private Iterator<GroundAtoms> iterator;
  private ListIterator<GroundAtoms> listIterator;

  private CuttingPlaneSolver solver, solver4Learner;
  private Scores scores;
  private Weights weights;
  private Solution solution;
  private OnlineLearner learner;
  private LocalFeatureExtractor extractor;
  private LocalFeatures features;
  private TrainingInstances instances;

  private GroundAtomsPrinter printer = new DefaultPrinter();//new CoNLL00SentencePrinter();

  private boolean signatureUpdated = false;
  private boolean modelUpdated = true;
  private boolean weightsUpdated = false;
  private boolean evalScores = false;

  private boolean printStackTraces = true;
  private boolean printModelChanges = true;
  private boolean printSignagureChanges = true;
  private boolean printPrompt = true;

  private String directory;
  private boolean solutionAvailable, externalFeatures, externalScores, featuresAvailable, scoresAvailable;
  private ConsoleReader console;
  private FeatureCollector collector;

  private int defaultCorpusCacheSize = 20 * 1024 * 1024;
  private int defaultInstanceCacheSize = 5 * 1024 * 1024;

  private HashMap<String, GroundAtomsPrinter> printers = new HashMap<String, GroundAtomsPrinter>();
  private HashMultiMapList<UserPredicate, Object[]> evaluationRestrictions = new HashMultiMapList<UserPredicate, Object[]>();
  private String[] args;

  private UserPredicate predicateForSize;
  private SimpleCompletor completor = new SimpleCompletor("");
  private int maxTabComplete = 50;
  private boolean cacheFeatures = true;

  public Shell() {
    this(System.in, System.out, System.err);
  }

  public Shell(InputStream in, PrintStream out, PrintStream err) {
    this.in = in;
    this.out = out;
    this.err = err;
    try {
      console = new ConsoleReader(System.in, new OutputStreamWriter(System.out));
      console.getHistory().setHistoryFile(new File(System.getProperty("user.home") + "/.thebeasthistory"));
      console.addCompletor(completor);
      List historyList = console.getHistory().getHistoryList();
      ListIterator listIterator = historyList.listIterator(historyList.size());
      while (completor.getCandidates().size() < maxTabComplete && listIterator.hasPrevious())
        completor.addCandidateString((String) listIterator.previous());
//        for (Object o : historyList)
//        if (completor.getCandidates().size() > maxTabComplete) break;
//        else completor.addCandidateString((String) o);
    } catch (IOException e) {
      e.printStackTrace();
    }
    signature = TheBeast.getInstance().createSignature();
    weights = signature.createWeights();
    model = signature.createModel();
    solver = new CuttingPlaneSolver();
    solver.setProfiler(new TreeProfiler());
    solver4Learner = new CuttingPlaneSolver();
    solver4Learner.setMaxIterations(20);
    learner = new OnlineLearner(model, weights, solver4Learner);
    learner.setProfiler(new TreeProfiler());
    learner.setProgressReporter(new DotProgressReporter(out, 5, 5, 5));
    collector = new FeatureCollector(model, weights);
    initCorpusTools();
  }


  public boolean isPrintPrompt() {
    return printPrompt;
  }

  public void setPrintPrompt(boolean printPrompt) {
    this.printPrompt = printPrompt;
  }

  public boolean isPrintStackTraces() {
    return printStackTraces;
  }

  public void setPrintStackTraces(boolean printStackTraces) {
    this.printStackTraces = printStackTraces;
  }

  /**
   * Fills up a model (and its signature) with the formulas, types and predices from a PML stream.
   *
   * @param inputStream the stream to load from
   * @param model       the model to save to
   * @throws Exception in case there are some semantic errors or IO problems.
   */
  public void load(InputStream inputStream, Model model) throws Exception {
    this.model = model;
    this.signature = model.getSignature();
    this.typeContext = new Stack<Type>();
    this.variables = new Stack<HashMap<String, Variable>>();
    byte[] buffer = new byte[1000];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    while (inputStream.available() > 0) {
      int howmany = inputStream.read(buffer);
      os.write(buffer, 0, howmany);
    }
    byte[] file = os.toByteArray();
    PMLParser parser = new PMLParser(new Yylex(new ByteArrayInputStream(file)));
    try {
      for (Object obj : ((List) parser.parse().value)) {
        ParserStatement statement = (ParserStatement) obj;
        statement.acceptParserStatementVisitor(this);
      }
    } catch (PMLParseException e) {
      err.println(errorMessage(e, new ByteArrayInputStream(file)));
    }
  }


  /**
   * Processes the input line by line. I.e. it does not wait for EOFs but only for EOLs.
   *
   * @throws IOException if there is some I/O problem.
   */
  public void interactive() throws IOException {
    //BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    out.println("Markov The Beast " + TheBeast.VERSION);
    if (printPrompt) out.print("# ");
    for (String line = console.readLine(); line != null; line = console.readLine()) {
      PMLParser parser = new PMLParser(new Yylex(new ByteArrayInputStream(line.getBytes())));
      completor.addCandidateString(line);
      try {
        for (Object obj : ((List) parser.parse().value)) {
          ParserStatement statement = (ParserStatement) obj;
          statement.acceptParserStatementVisitor(this);
        }
      } catch (PMLParseException e) {
        System.out.println(errorMessage(e, new ByteArrayInputStream(line.getBytes())));
      } catch (Exception e) {
        if (printStackTraces) e.printStackTrace();
        else out.print(e.getMessage());
      }
      if (printPrompt) out.print("# ");
    }
    //console.getHistory().
  }


  /**
   * Parses the whole input stream in one run and executes each command.
   *
   * @throws IOException if I/O goes wrong.
   */
  public void execute() throws IOException {
    byte[] buffer = new byte[1000];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    while (in.available() > 0) {
      int howmany = in.read(buffer);
      os.write(buffer, 0, howmany);
    }
    byte[] file = os.toByteArray();

    PMLParser parser = new PMLParser(new Yylex(new ByteArrayInputStream(file)));
    try {
      for (Object obj : ((List) parser.parse().value)) {
        ParserStatement statement = (ParserStatement) obj;
        statement.acceptParserStatementVisitor(this);
      }
    } catch (PMLParseException e) {
      System.out.println(errorMessage(e, new ByteArrayInputStream(file)));
    } catch (Exception e) {
      if (printStackTraces) e.printStackTrace();
      else out.print(e.getMessage());
    }
  }

  public static void main(String[] args) throws Exception {
    Shell shell = new Shell(System.in, System.out, System.err);
    shell.interactive();
  }

  public static String errorMessage(PMLParseException exception, InputStream is) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    int lineNr = 0;
    int colNr = exception.getCol();
    for (String line = reader.readLine(); line != null; line = reader.readLine(), ++lineNr) {
      if (lineNr == exception.getLine()) {
        StringBuffer buffer = new StringBuffer(line);
        //System.out.println(buffer);
        buffer.insert(colNr, "!ERROR!");
        return "Error on line " + lineNr + ": " + buffer.toString();
      }
      colNr -= line.length() + 1;
    }
    return "Syntax Error!";
  }

  public void visitCreateType(ParserCreateType parserCreateType) {
    signature.createType(parserCreateType.name, parserCreateType.unknowns, parserCreateType.getNames());
    if (printSignagureChanges) out.println("Type " + parserCreateType.name + " created.");
    signatureUpdated = true;
  }

  public void visitCreatePredicate(ParserCreatePredicate parserCreatePredicate) {
    LinkedList<Type> types = new LinkedList<Type>();
    for (String name : parserCreatePredicate.types) {
      Type type = signature.getType(name);
      if (type == null) throw new RuntimeException("There is no type with name " + name);
      types.add(type);
    }
    signature.createPredicate(parserCreatePredicate.name, types);
    if (printSignagureChanges) out.println("Predicate " + parserCreatePredicate.name + " created.");
    signatureUpdated = true;

  }

  public void visitCreateWeightFunction(ParserCreateWeightFunction parserCreateWeightFunction) {
    LinkedList<Type> types = new LinkedList<Type>();
    for (String name : parserCreateWeightFunction.argTypes) {
      Type type = signature.getType(name);
      if (type == null) throw new ShellException("There is no type with name " + name +
              " like you used in " + parserCreateWeightFunction);
      types.add(type);
    }
    String returnType = parserCreateWeightFunction.returnType;
    String name = parserCreateWeightFunction.name;
    if (!returnType.startsWith("Double"))
      throw new RuntimeException("Return type of a weight function must be Double(+/-)");
    if (returnType.endsWith("-"))
      signature.createWeightFunction(name, false, types);
    else if (returnType.endsWith("+"))
      signature.createWeightFunction(name, true, types);
    else
      signature.createWeightFunction(name, types);
    signatureUpdated = true;
  }

  public Quantification pushQuantification(List<ParserTyping> vars) {
    LinkedList<Variable> quantification = new LinkedList<Variable>();
    HashMap<String, Variable> map = new HashMap<String, Variable>();
    for (ParserTyping typing : vars) {
      Type type = signature.getType(typing.type);
      if (type == null) throw new ShellException("There is no type with name " + typing.type);
      Variable variable = new Variable(type, typing.var);
      quantification.add(variable);
      map.put(typing.var, variable);
    }
    variables.push(map);
    return new Quantification(quantification);
  }

  public Map<String, Variable> popQuantification() {
    return variables.pop();
  }

  public void visitFactorFormula(ParserFactorFormula parserFactorFormula) {
    rootFactor = parserFactorFormula;
    Quantification quantification = parserFactorFormula.quantification == null ?
            new Quantification(new ArrayList<Variable>()) :
            pushQuantification(parserFactorFormula.quantification);
    if (parserFactorFormula.condition != null)
      parserFactorFormula.condition.acceptParserFormulaVisitor(this);
    else
      formula = null;
    BooleanFormula condition = formula;
    parserFactorFormula.formula.acceptParserFormulaVisitor(this);
    BooleanFormula formula = this.formula;
    typeContext.push(Type.DOUBLE);
    parserFactorFormula.weight.acceptParserTermVisitor(this);
    typeContext.pop();
    Term weight = term;
    FactorFormula factorFormula = new FactorFormula(parserFactorFormula.spec.name, quantification, condition, formula, weight);
    factorFormula.setOrder(parserFactorFormula.spec.order);
    factorFormula.setGround(parserFactorFormula.spec.ground);
    model.addFactorFormula(factorFormula);
    if (parserFactorFormula.quantification != null)
      popQuantification();
    modelUpdated = true;
    if (printModelChanges) out.println("Factor added: " + factorFormula);
  }

  public synchronized void visitImport(ParserImport parserImport) {
    File file = null;
    //out = new PrintStream(new ByteArrayOutputStream(1024));
    boolean previousPrintModelChanges = printModelChanges;
    boolean previousPrintSignatureChanges = printSignagureChanges;
    printModelChanges = false;
    printSignagureChanges = false;
    String oldDir = directory;
    String filename = filename((String) resolveParam(parserImport.filename));
    try {
      file = new File(filename);
      if (file.getParentFile() != null) directory = file.getParentFile().getPath();
      PMLParser parser = new PMLParser(new Yylex(new FileInputStream(file)));
      for (Object obj : ((List) parser.parse().value)) {
        ParserStatement statement = (ParserStatement) obj;
        statement.acceptParserStatementVisitor(this);
      }
    } catch (PMLParseException e) {
      try {
        out.println(errorMessage(e, new FileInputStream(file)));
      } catch (IOException e1) {
        e1.printStackTrace();
      }
    } catch (Exception e) {
      e.printStackTrace(out);
    } finally {
      directory = oldDir;
    }

    out.println("File \"" + filename + "\" included.");
    printModelChanges = previousPrintModelChanges;
    printSignagureChanges = previousPrintSignatureChanges;
  }

  public void visitAddPredicateToModel(ParserAddPredicateToModel parserAddPredicateToModel) {
    if (printModelChanges) out.print("Predicates ");
    int index = 0;
    for (String name : parserAddPredicateToModel.predicates) {
      if (printModelChanges) {
        if (index++ > 0) out.print(", ");
        out.print(name);
      }
      UserPredicate predicate = (UserPredicate) signature.getPredicate(name);
      if (predicate == null) throw new ShellException("Predicate " + name + " does not exist");
      switch (parserAddPredicateToModel.type) {
        case HIDDEN:
          model.addHiddenPredicate(predicate);
          break;
        case OBSERVED:
          model.addObservedPredicate(predicate);
          break;
        case GLOBAL:
          model.addGlobalPredicate(predicate);
          break;
        case AUXILIARY:
          model.addAuxilaryPredicate(predicate);
          break;
      }
    }
    if (printModelChanges) out.println(" added to the model.");
  }

  public void visitInspect(ParserInspect parserInspect) {
    if (!parserInspect.inspectType) {
      Predicate predicate = signature.getPredicate(parserInspect.target);
      if (predicate != null) {
        out.println(predicate);
        return;
      }
      Function function = signature.getFunction(parserInspect.target);
      if (function != null) {
        out.println(function);
      }
    } else {
      Type type = signature.getType(parserInspect.target);
      if (type.getTypeClass() == Type.Class.CATEGORICAL || type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) {
        out.print(type);
        out.print(": ");
        if (type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) out.print("... ");
        out.println(Util.toStringWithDelimiters(type.getConstants(), ", "));
      } else {
        out.println(type);
      }

    }
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }

  private String filename(String name) {
    if (name.startsWith("/") || directory == null) return name;
    return directory + "/" + name;
  }

  public synchronized void visitLoad(ParserLoad parserLoad) {
    update();
    try {
      String filename = resolveParam(parserLoad.file).toString();
      if ("global".equals(parserLoad.target.head)) {
        if (parserLoad.target.tail == null)
          model.getGlobalAtoms().load(new FileInputStream(filename(filename)));
        else {
          UserPredicate predicate = signature.getUserPredicate(parserLoad.target.tail.head);
          if (!model.getGlobalPredicates().contains(predicate))
            throw new RuntimeException(predicate + " is not a global predicate. Use \"global: "
                    + predicate.getName() + "\"");
          model.getGlobalAtoms().load(new FileInputStream(filename(filename)), predicate);
        }
        out.println("Global atoms loaded.");
        //System.out.println(model.getGlobalAtoms());
      } else if ("instances".equals(parserLoad.target.head)) {
        instances = new TrainingInstances(model, new File(filename(filename)), defaultInstanceCacheSize,cacheFeatures);
        out.println(instances.size() + " instances loaded.");
      } else if ("weights".equals(parserLoad.target.head)) {
        if ("dump".equals(parserLoad.mode)) {
          FileSource source = TheBeast.getInstance().getNodServer().createSource(new File(filename(filename)), 1024);
          weights.read(source);
          weightsUpdated = true;
        } else if (null == parserLoad.mode) {
          weights.load(new FileInputStream(filename(filename)));
        } else {
          throw new ShellException("Mode " + parserLoad.mode + " not supported for loading " + parserLoad.target);
        }
        out.println(weights.getFeatureCount() + " weights loaded.");
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void update() {
    if (signatureUpdated) {
      guess = signature.createGroundAtoms();
      guess.load(model.getGlobalAtoms(), model.getGlobalPredicates());
      gold = signature.createGroundAtoms();
      gold.load(model.getGlobalAtoms(), model.getGlobalPredicates());
      //solution = new Solution(model, weights);
      signatureUpdated = false;
    }
    if (modelUpdated) {
      model.validateModel();
      if (model.getHiddenPredicates().size() == 0)
        throw new ShellException("There are no hidden predicates defined.");
      weights = signature.createWeights();
      scores = new Scores(model, weights);
      features = new LocalFeatures(model, weights);
      extractor = new LocalFeatureExtractor(model, weights);
      solver.configure(model, weights);
      solution = new Solution(model, weights);
      if (learner == null) {
        learner = new OnlineLearner(model, weights, solver);
      } else {
        learner.configure(model, weights);
      }
      collector.configure(model, weights);
      //instances = new TrainingInstances();
      modelUpdated = false;
      out.println("All components updated");
    }

  }

  public void visitPrint(ParserPrint parserPrint) {
    PropertyName propertyName = toPropertyName(parserPrint.name);
    if ("atoms".equals(parserPrint.name.head)) {
      if (parserPrint.name.tail == null)
        printer.print(guess, out);
      else {
        UserPredicate predicate = (UserPredicate) signature.getPredicate(parserPrint.name.tail.head);
        out.print(guess.getGroundAtomsOf(predicate));
      }
    } else if ("scores".equals(parserPrint.name.head)) {
      out.println(scores);
    } else if ("history".equals(parserPrint.name.head)) {
      printHistory();
    } else if ("collector".equals(parserPrint.name.head)) {
      out.println(collector);
    } else if ("solver".equals(parserPrint.name.head)) {
      if (propertyName.isTerminal()) out.println(solver);
      else
        out.println(solver.getProperty(propertyName.getTail()));
    } else if ("learner".equals(parserPrint.name.head)) {
      if (propertyName.isTerminal()) out.println(learner);
      else
        out.println(learner.getProperty(propertyName.getTail()));
    } else if ("eval".equals(parserPrint.name.head)) {
      Evaluation evaluation = new Evaluation(model);
      for (UserPredicate pred : evaluationRestrictions.keySet()) {
        for (Object[] pattern : evaluationRestrictions.get(pred))
          evaluation.addRestrictionPattern(pred, pattern);
      }
      evaluation.evaluate(gold, guess);
      printer.printEval(evaluation, out);
    } else if ("formulas".equals(parserPrint.name.head)) {
      GroundFormulas formulas = new GroundFormulas(model, weights);
      formulas.update(guess);
      out.println(formulas);
    } else if ("gold".equals(parserPrint.name.head)) {
      if ("formulas".equals(parserPrint.name.tail.head)) {
        GroundFormulas formulas = new GroundFormulas(model, weights);
        formulas.update(gold);
        out.println(formulas);
      } else if ("atoms".equals(parserPrint.name.tail.head))
        out.println(gold);
    } else if ("weights".equals(parserPrint.name.head)) {
      if (parserPrint.name.tail == null)
        weights.save(out);
      else {
        if (parserPrint.name.tail.head.equals("nonzero")) {
          out.println(weights.getNonZeroCount());
        } else {
          WeightFunction function = (WeightFunction) signature.getFunction(parserPrint.name.tail.head);
          if (parserPrint.name.tail.arguments != null) {
            out.println(weights.getWeights(function, parserPrint.name.tail.arguments.toArray()));
          } else {
            weights.save(function, out);
          }
        }
      }
    } else if ("memory".equals(parserPrint.name.head)) {
      out.printf("%-20s%8.3fmb\n", "Total memory:", Runtime.getRuntime().totalMemory() / 1024 / 1024.0);
      out.printf("%-20s%8.3fmb\n", "Free memory:", Runtime.getRuntime().freeMemory() / 1024 / 1024.0);
      out.printf("%-20s%8.3fmb\n", "Gold corpus:", corpus.getUsedMemory() / 1024 / 1024.0);
      out.printf("%-20s%8.3fmb\n", "Training instances:", instances.getUsedMemory() / 1024 / 1024.0);
      out.printf("%-20s%8.3fmb\n", "Weights:", weights.getUsedMemory() / 1024 / 1024.0);
      //out.printf("%-20s%8.3fmb\n", "Collector:", collector.getUsedMemory() / 1024 / 1024.0);
      //System.
    }
  }

  public void visitSolve(ParserSolve parserSolve) {
    update();
    //CuttingPlaneSolver solver = new CuttingPlaneSolver();
    //solver.configure(model, weights);
    stopWatch.start();
    solver.setObservation(guess);
    if (parserSolve.numIterations == -1)
      solver.solve();
    else
      solver.solve(parserSolve.numIterations);
    guess.load(solver.getBestAtoms());
    long time = stopWatch.stopAndContinue();

//    if (solutionAvailable && !guess.isEmpty(model.getHiddenPredicates()))
//      solver.setInititalSolution(guess);
//    solver.solve(parserSolve.numIterations);
//    solutionAvailable = false;
//    guess.load(solver.getAtoms(), model.getHiddenPredicates());
    if (solver.getIterationCount() == 1)
      out.println("Solved locally in " + time + "ms.");
    else {
      out.println("Solved with " + solver.getIterationCount() + " steps (" + solver.getIntegerEnforcementsCount()
              + " integer enforcements) in " + time + "ms.");
    }
  }

  public void visitGenerateTypes(ParserGenerateTypes parserGenerateTypes) {
    TypeGenerator generator = typeGenerators.get(parserGenerateTypes.generator);
    if (generator == null)
      throw new RuntimeException("No type generator with nane " + parserGenerateTypes.generator);
    try {
      generator.generateTypes(new FileInputStream(filename(parserGenerateTypes.file)), signature);
      out.println("Types generated.");
    } catch (FileNotFoundException e) {
      throw new RuntimeException("Couldn't load " + filename(parserGenerateTypes.file), e);
    }
    signatureUpdated = true;
  }

  public void visitSaveTypes(ParserSaveTypes parserSaveTypes) {
    try {
      PrintStream file = new PrintStream(new FileOutputStream(filename(parserSaveTypes.file)));
      for (Type type : signature.getTypes())
        if (type.getTypeClass() == Type.Class.CATEGORICAL || type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) {
          file.print("type " + type + ": ");
          if (type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) file.print("... ");
          file.print(Util.toEscapedStringWithDelimiters(type.getConstants(), ", "));
          file.println(";");
        }
      file.close();
      out.println("Types saved.");
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public void visitLoadCorpus(ParserLoadCorpus parserLoadCorpus) {
    update();
    String s = resolveParam(parserLoadCorpus.file).toString();
    if ("dump".equals(parserLoadCorpus.factory)) {
      corpus = new DumpedCorpus(signature, new File(s), defaultCorpusCacheSize);
      //iterator = corpus.iterator();
    } else {
      CorpusFactory factory = getCorpusFactory(parserLoadCorpus.factory);
      corpus = factory.createCorpus(signature, new File(filename(s)));
      corpus = new AugmentedCorpus(model, corpus);
      if (parserLoadCorpus.from != -1) {
        Iterator<GroundAtoms> instance = corpus.iterator();
        corpus = new RandomAccessCorpus(signature, parserLoadCorpus.to - parserLoadCorpus.from);
        for (int i = 0; i < parserLoadCorpus.from; ++i) instance.next();
        for (int i = parserLoadCorpus.from; i < parserLoadCorpus.to; ++i) corpus.add(instance.next());
      }
    }
    out.println("Corpus loaded" + (parserLoadCorpus.factory == null ? "." :
            " using the " + parserLoadCorpus.factory + " factory."));
  }


  public void visitSaveCorpus(ParserSaveCorpus parserSaveCorpus) {
    update();
    try {
      if (corpus == null) throw new ShellException("No corpus loaded yet, can't be saved to ram");
      if ("dump".equals(parserSaveCorpus.factory)) {
        File file = new File(parserSaveCorpus.file);
        file.delete();
        if (parserSaveCorpus.from != -1)
          corpus = new DumpedCorpus(file, corpus, parserSaveCorpus.from, parserSaveCorpus.to, defaultCorpusCacheSize);
        else
          corpus = new DumpedCorpus(file, corpus, defaultCorpusCacheSize);
        out.println("Corpus dumped to disk (using dumped version now).");
        //iterator = corpus.iterator();
      } else if ("ram".equals(parserSaveCorpus.factory)) {
        if (parserSaveCorpus.from != -1) {
          Iterator<GroundAtoms> instance = corpus.iterator();
          ramCorpus = new RandomAccessCorpus(signature, parserSaveCorpus.to - parserSaveCorpus.from);
          for (int i = 0; i < parserSaveCorpus.from; ++i) instance.next();
          for (int i = parserSaveCorpus.from; i < parserSaveCorpus.to; ++i) ramCorpus.add(instance.next());
          corpus = ramCorpus;
        } else {
          ramCorpus = new RandomAccessCorpus(corpus);
          corpus = ramCorpus;
        }
        out.println("Corpus saved to RAM (can be used for inspection now).");
        listIterator = ramCorpus.listIterator();
      } else if ("instances".equals(parserSaveCorpus.factory)) {
        if (corpus == null) throw new ShellException("Corpus must be loaded before training instances can generated");
        File file = new File(filename(parserSaveCorpus.file));
        file.delete();
        if (parserSaveCorpus.from != -1) {
          throw new RuntimeException("Instances can only be created for the complete corpus (no range allowed).");
        } else
          instances = new TrainingInstances(file, extractor, cacheFeatures, corpus, defaultInstanceCacheSize,
                  new DotProgressReporter(out, 5, 5, 5));
        //iterator = corpus.iterator();
        out.println(instances.size() + " instances generated.");
      } else {
        CorpusFactory factory = corpusFactories.get(parserSaveCorpus.factory);
        File file = new File(parserSaveCorpus.file);
        file.delete();
        Corpus dst = factory.createCorpus(signature, file);
        for (GroundAtoms atoms : corpus) {
          dst.append(atoms);
        }
        corpus = dst;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void visitSave(ParserSave parserSave) {
    update();
    try {
      if ("weights".equals(parserSave.target.head)) {
        if ("dump".equals(parserSave.mode)) {
          File file = new File(filename(parserSave.file));
          file.delete();
          FileSink sink = TheBeast.getInstance().getNodServer().createSink(file, 1024);
          weights.write(sink);
          sink.flush();
          //weightsUpdated = true;
        } else {
          throw new ShellException("Mode " + parserSave.mode + " not supported for saving " + parserSave.target);
        }
        out.println(weights.getFeatureCount() + " weights saved.");
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void visitTest(ParserTest parserTest) {
    Corpus dst = null;
    if ("ram".equals(parserTest.mode)) {
      dst = new RandomAccessCorpus(signature, 1000);
    } else if ("printer".equals(parserTest.mode)) {
      String filename = filename(resolveParam(parserTest.file).toString());
      File file = new File(filename);
      file.delete();
      try {
        dst = new PrinterCorpus(printer, new PrintStream(file));
      } catch (FileNotFoundException e) {
        e.printStackTrace();
      }
    } else {
      String filename = filename(resolveParam(parserTest.file).toString());
      File file = new File(filename);
      file.delete();
      dst = corpusFactories.get(parserTest.mode).createCorpus(signature, file);
    }
    CorpusEvaluation corpusEvaluation = new CorpusEvaluation(model);
    if (evalFunction != null) {
      CorpusEvaluationFunction function = evalFunctions.get(evalFunction);
      if (function != null) {
        corpusEvaluation.addCorpusEvaluationFunction(evalFunction, function);
      }
    }
    Evaluation evaluation = new Evaluation(model);
    for (UserPredicate pred : evaluationRestrictions.keySet()) {
      for (Object[] pattern : evaluationRestrictions.get(pred))
        evaluation.addRestrictionPattern(pred, pattern);
    }
    DotProgressReporter reporter = new DotProgressReporter(out, 5, 5, 5);
    if (evalScores)
      reporter.setColumns("Loss", "Iterations", "Gold Score", "Guess Score", "Violations");
    else
      reporter.setColumns("Loss", "Iterations");

    reporter.started();
    LossFunction lossFunction = new AverageF1Loss(model);
    Counter<Integer> iterations = new Counter<Integer>();
    Solution goldSolution = new Solution(model, weights);
    Solution guessSolution = new Solution(model, weights);
    for (GroundAtoms gold : corpus) {
      solver.setObservation(gold);
      if (predicateForSize != null)
        solver.getProfiler().start(String.valueOf(gold.getGroundAtomsOf(predicateForSize).size()));
      solver.solve();
      if (predicateForSize != null) {
        solver.getProfiler().end();
        iterations.increment(gold.getGroundAtomsOf(predicateForSize).size(), solver.getIterationCount());
      }

//      for (UserPredicate pred : model.getHiddenPredicates()){
//        System.out.println(gold.getGroundAtomsOf(pred));
//        System.out.println(solver.getBestAtoms().getGroundAtomsOf(pred));
//      }
      dst.append(solver.getBestAtoms());
      double loss = lossFunction.loss(gold, solver.getBestAtoms());
      evaluation.evaluate(gold, solver.getBestAtoms());
      corpusEvaluation.add(evaluation);
      if (evalScores) {
        goldSolution.load(gold);
        goldSolution.updateGroundFormulas();
        GroundFormulas formulas = new GroundFormulas(model, weights);
        formulas.update(solver.getBestAtoms());
        guessSolution.load(solver.getBestAtoms(), formulas);
        double goldScore = weights.score(goldSolution.extract());
        double guessScore = weights.score(guessSolution.extract());
        int violations = guessSolution.getGroundFormulas().getViolationCount();
        reporter.progressed(loss, solver.getIterationCount(), goldScore, guessScore, violations);
      } else
        reporter.progressed(loss, solver.getIterationCount());

      //System.out.println(loss);
    }
    reporter.finished();
    out.print(corpusEvaluation);
    if (predicateForSize != null) {
      iterations.save(System.out);
    }
  }

  public void visitCreateIndex(ParserCreateIndex parserCreateIndex) {
    UserPredicate predicate = signature.getUserPredicate(parserCreateIndex.name);
    predicate.addIndex(parserCreateIndex.markers);
  }


  public void visitLoadScores(ParserLoadScores parserLoadScores) {
    update();
    File file = new File(filename(parserLoadScores.file));
    try {
      scores.load(new FileInputStream(file));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    scoresAvailable = true;
    solver.setScores(scores);
    out.println("Scores loaded.");
  }

  public void visitShift(ParserShift parserShift) {
    int delta = parserShift.delta;
    jump(delta);
  }

  private void jump(int delta) {
    if (listIterator == null)
      throw new RuntimeException("No corpus loaded into ram, can't go forward or backwards");
    try {
      if (delta < 0) {
        for (int i = 0; i > delta + 1; --i)
          listIterator.previous();
        GroundAtoms atoms = listIterator.previous();
        loadAtoms(atoms);
      } else {
        for (int i = 0; i < delta - 1; ++i)
          listIterator.next();
        GroundAtoms atoms = listIterator.next();
        loadAtoms(atoms);
      }
    } catch (Exception e) {
      throw new RuntimeException("You ran out of bounds.");
    }
  }

  private void loadAtoms(GroundAtoms atoms) {
    //gold.load(model.getGlobalAtoms(), model.getGlobalPredicates());
    gold.load(atoms, model.getGlobalPredicates());
    gold.load(atoms, model.getInstancePredicates());
    //guess.load(model.getGlobalAtoms(), model.getGlobalPredicates());
    guess.load(atoms, model.getGlobalPredicates());
    guess.load(atoms, model.getInstancePredicates());
    solver.setObservation(atoms);
    solutionAvailable = true;
    scoresAvailable = false;
    featuresAvailable = false;
  }

  public void visitGreedy(ParserGreedy parserGreedy) {
    updateScores();
    guess.load(scores.greedySolve(0.0), model.getHiddenPredicates());
    solutionAvailable = true;
    out.println("Greedy solution extracted.");
  }

  private void updateScores() {
    if (!scoresAvailable) {
      updateFeatures();
      scores.score(features, guess);
      scoresAvailable = true;
    }
  }

  private void updateFeatures() {
    if (!featuresAvailable) {
      extractor.extract(guess, features);
      //features.extract(guess);
      featuresAvailable = true;
    }
  }

  public void visitLoadWeights(ParserLoadWeights parserLoadWeights) {
    try {
      if (weights == null) weights = signature.createWeights();
      String filename = resolveParam(parserLoadWeights.file).toString();
      weights.load(new FileInputStream(filename));
      weightsUpdated = true;
      out.println(weights.getFeatureCount() + " weights loaded.");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void visitCollect(ParserCollect parserCollect) {
    if (corpus == null)
      throw new ShellException("Need a corpus for collecting features!");
    update();
    int oldCount = weights.getFeatureCount();
    collector.setProgressReporter(new DotProgressReporter(out, 5, 5, 5));
    collector.collect(corpus);
    //collector.collect(corpus);
    out.println("Collected " + (weights.getFeatureCount() - oldCount) + " features.");
    iterator = corpus.iterator();


  }

  public void visitPrintWeights(ParserPrintWeights parserPrintWeights) {
    if (parserPrintWeights.function == null) weights.save(out);
    else {
      WeightFunction function = (WeightFunction) signature.getFunction(parserPrintWeights.function);
      weights.save(function, out);
    }
  }

  public void visitLearn(ParserLearn parserLearn) {
    update();
    if (parserLearn.instances == -1) {
      if (instances.size() == 0)
        out.println("There are no training instances. Use the 'save corpus to instances ... ' " +
                "command to create some");
      else {
        int oldNumEpochs = learner.getNumEpochs();
        if (parserLearn.epochs != -1)
          learner.setNumEpochs(parserLearn.epochs);
        learner.learn(instances);
        learner.setNumEpochs(oldNumEpochs);
      }
    } else {
      out.println("Not implemented.");
    }
    //learner.endEpoch();
  }


  public void addEvaluationRestriction(UserPredicate pred, List<Object> pattern) {
    evaluationRestrictions.add(pred, pattern.toArray(new Object[]{}));
  }

  private Object getParameter(String name) {
    String stringValue;
    try {
      int number = Integer.parseInt(name);
      if (number >= args.length)
        throw new ShellException("You are using " + name + " parameters but there are only " + (args.length - 1) + " " +
                "parameters available!");
      stringValue = args[number];
    }
    catch (NumberFormatException ex) {
      stringValue = System.getProperty(name);
    }
    try {
      return Double.parseDouble(stringValue);
    } catch (NumberFormatException e1) {
      try {
        return Integer.parseInt(stringValue);
      } catch (NumberFormatException e2) {
        return stringValue;
      }
    }
  }

  private Object resolveParam(Object value) {
    if (value instanceof String && ((String) value).startsWith("$")) {
      String param = ((String) value).substring(1);
      return getParameter(param);
    }
    return value;
  }

  public void visitSet(ParserSet parserSet) {
    Object value = resolveParam(parserSet.value);
    if ("instancesCacheSize".equals(parserSet.propertyName.head))
      defaultInstanceCacheSize = 1024 * 1024 * (Integer) value;
    else if ("corpusCacheSize".equals(parserSet.propertyName.head))
      defaultCorpusCacheSize = 1024 * 1024 * (Integer) value;
    else if ("cacheFeatures".equals(parserSet.propertyName.head))
      cacheFeatures = (Boolean) value;
    else if ("solver".equals(parserSet.propertyName.head))
      solver.setProperty(toPropertyName(parserSet.propertyName.tail), value);
    else if ("weights".equals(parserSet.propertyName.head))
      weights.setProperty(toPropertyName(parserSet.propertyName.tail), value);
    else if ("evalrestrict".equals(parserSet.propertyName.head)) {
      if ((Boolean) value)
        addEvaluationRestriction(signature.getUserPredicate(parserSet.propertyName.tail.head),
                parserSet.propertyName.tail.arguments);
    } else if ("learner".equals(parserSet.propertyName.head))
      learner.setProperty(toPropertyName(parserSet.propertyName.tail), value);
    else if ("collector".equals(parserSet.propertyName.head))
      collector.setProperty(toPropertyName(parserSet.propertyName.tail), value);
    else if ("printer".equals(parserSet.propertyName.head))
      printer = printers.get(value.toString());
    else if ("sizePred".equals(parserSet.propertyName.head)) {
      predicateForSize = signature.getUserPredicate(value.toString());
    } else if ("evalScores".equals(parserSet.propertyName.head)) {
      evalScores = (Boolean) value;
    } else if ("eval".equals(parserSet.propertyName.head))
      evalFunction = value.toString();
    else
      throw new RuntimeException("There is no property named " + parserSet.propertyName);

    out.println(parserSet.propertyName + " set to " + value + ".");
  }

  private PropertyName toPropertyName(ParserName name) {
    if (name.tail == null) return new PropertyName(name.head, null, name.arguments);
    return new PropertyName(name.head, toPropertyName(name.tail), name.arguments);
  }

  public void visitClear(ParserClear parserClear) {
    if (parserClear.what.equals("atoms")) {
      guess.clear(model.getHiddenPredicates());
//reseting the solver
      out.println("Atoms cleared.");
    } else if (parserClear.what.equals("scores")) {
      scores.clear();
      out.println("Scores cleared.");
    }
  }


  public void visitAtom(ParserAtom parserAtom) {
    LinkedList<Term> args = new LinkedList<Term>();
    Predicate predicate = signature.getPredicate(parserAtom.predicate);
    if (predicate == null)
      throw new ShellException("There is no predicate called " + parserAtom.predicate);
    int index = 0;
    if (parserAtom.args.size() != predicate.getArity())
      throw new ShellException("Predicate " + predicate.getName() + " has " + predicate.getArity()
              + " arguments, not " + parserAtom.args.size() + " as in " + parserAtom);
    for (ParserTerm term : parserAtom.args) {
      typeContext.push(predicate.getArgumentTypes().get(index++));
      term.acceptParserTermVisitor(this);
      args.add(this.term);
      typeContext.pop();
    }
    formula = new PredicateAtom(predicate, args);
  }

  public void visitConjuction(ParserConjunction parserConjunction) {
    LinkedList<BooleanFormula> args = new LinkedList<BooleanFormula>();
    parserConjunction.lhs.acceptParserFormulaVisitor(this);
    args.add(this.formula);
    ParserFormula rhs = parserConjunction.rhs;
    while (rhs instanceof ParserConjunction) {
      ParserConjunction c = (ParserConjunction) rhs;
      c.lhs.acceptParserFormulaVisitor(this);
      args.add(this.formula);
      rhs = c.rhs;
    }
    rhs.acceptParserFormulaVisitor(this);
    args.add(this.formula);
    formula = new Conjunction(args);
  }

  public void visitDisjunction(ParserDisjunction parserDisjunction) {
    LinkedList<BooleanFormula> args = new LinkedList<BooleanFormula>();
    parserDisjunction.lhs.acceptParserFormulaVisitor(this);
    args.add(this.formula);
    ParserFormula rhs = parserDisjunction.rhs;
    while (rhs instanceof ParserDisjunction) {
      ParserDisjunction c = (ParserDisjunction) rhs;
      c.lhs.acceptParserFormulaVisitor(this);
      args.add(this.formula);
      rhs = c.rhs;
    }
    rhs.acceptParserFormulaVisitor(this);
    args.add(this.formula);
    formula = new Disjunction(args);
  }
    
  public void visitImplies(ParserImplies parserImplies) {
    parserImplies.lhs.acceptParserFormulaVisitor(this);
    BooleanFormula lhs = this.formula;
    parserImplies.rhs.acceptParserFormulaVisitor(this);
    BooleanFormula rhs = this.formula;
    formula = new Implication(lhs, rhs);
  }

  public void visitCardinalityConstraint(ParserCardinalityConstraint parserCardinalityConstraint) {
    typeContext.push(Type.INT);
    parserCardinalityConstraint.lowerBound.acceptParserTermVisitor(this);
    typeContext.pop();
    Term lb = term;
    Quantification quantification = pushQuantification(parserCardinalityConstraint.quantification);
    parserCardinalityConstraint.formula.acceptParserFormulaVisitor(this);
    popQuantification();
    typeContext.push(Type.INT);
    parserCardinalityConstraint.upperBound.acceptParserTermVisitor(this);
    typeContext.pop();
    Term ub = term;
    //formula = new CardinalityConstraint(lb, quantification, formula, ub,true);
    formula = new CardinalityConstraint(lb, quantification, formula, ub, parserCardinalityConstraint.useClosure);
  }

  public void visitComparison(ParserComparison parserComparison) {
    Term lhs, rhs;
    if (parserComparison.type == ParserComparison.Type.NEQ || parserComparison.type == ParserComparison.Type.EQ) {
      if (parserComparison.lhs instanceof ParserNamedConstant) {
        parserComparison.rhs.acceptParserTermVisitor(this);
        rhs = term;
        typeContext.push(rhs.getType());
        parserComparison.lhs.acceptParserTermVisitor(this);
        typeContext.pop();
        lhs = term;
      } else {
        parserComparison.lhs.acceptParserTermVisitor(this);
        lhs = term;
        typeContext.push(lhs.getType());
        parserComparison.rhs.acceptParserTermVisitor(this);
        typeContext.pop();
        rhs = term;

      }
    } else {
      parserComparison.lhs.acceptParserTermVisitor(this);
      lhs = term;
      parserComparison.rhs.acceptParserTermVisitor(this);
      rhs = term;
    }
    switch (parserComparison.type) {
      case EQ:
        formula = new PredicateAtom(signature.createEquals(Type.INT), lhs, rhs);
        break;
      case NEQ:
        formula = new PredicateAtom(signature.createNotEquals(Type.INT), lhs, rhs);
        break;
      case LEQ:
        formula = new PredicateAtom(IntLEQ.INT_LEQ, lhs, rhs);
        break;
      case LT:
        formula = new PredicateAtom(IntLT.INT_LT, lhs, rhs);
        break;
      case GT:
        formula = new PredicateAtom(IntGT.INT_GT, lhs, rhs);
        break;
      case GEQ:
        formula = new PredicateAtom(IntGEQ.INT_GEQ, lhs, rhs);
        break;
    }
  }

  public void visitAcyclicityConstraint(ParserAcyclicityConstraint parserAcyclicityConstraint) {
    UserPredicate predicate = (UserPredicate) signature.getPredicate(parserAcyclicityConstraint.predicate);
    formula = new AcyclicityConstraint(predicate);
  }

  public void visitNot(ParserNot parserNot) {
    parserNot.formula.acceptParserFormulaVisitor(this);
    formula = new Not(formula);
  }

  public void visitUndefinedWeight(ParserUndefinedWeight parserUndefinedWeight) {
    parserUndefinedWeight.functionApplication.acceptParserTermVisitor(this);
    formula = new UndefinedWeight((FunctionApplication) term);
  }

  public void visitNamedConstant(ParserNamedConstant parserNamedConstant) {
    term = typeContext.peek().getConstant(parserNamedConstant.name);
//typeCheck();
  }

  public void visitIntConstant(ParserIntConstant parserIntConstant) {
    term = new IntConstant(parserIntConstant.number);
    typeCheck();

  }

  public void visitParserAdd(ParserAdd parserAdd) {
    parserAdd.lhs.acceptParserTermVisitor(this);
    Term lhs = term;
    parserAdd.rhs.acceptParserTermVisitor(this);
    Term rhs = term;
    if (lhs.getType().getTypeClass() == Type.Class.DOUBLE && rhs.getType().getTypeClass() == Type.Class.DOUBLE)
      term = new FunctionApplication(DoubleAdd.ADD, lhs, rhs);
    else
      term = new FunctionApplication(IntAdd.ADD, lhs, rhs);

    typeCheck();
  }


  public void visitParserTimes(ParserTimes parserTimes) {
    parserTimes.lhs.acceptParserTermVisitor(this);
    Term lhs = term;
    parserTimes.rhs.acceptParserTermVisitor(this);
    Term rhs = term;
    term = new FunctionApplication(DoubleProduct.PRODUCT, lhs, rhs);
    typeCheck();
  }

  public void visitParserMinus(ParserMinus parserMinus) {
    parserMinus.lhs.acceptParserTermVisitor(this);
    Term lhs = term;
    parserMinus.rhs.acceptParserTermVisitor(this);
    Term rhs = term;
    if (lhs.getType().getTypeClass() == Type.Class.DOUBLE && rhs.getType().getTypeClass() == Type.Class.DOUBLE)
      term = new FunctionApplication(DoubleMinus.MINUS, lhs, rhs);
    else
      term = new FunctionApplication(IntMinus.MINUS, lhs, rhs);
    typeCheck();
  }

  public void visitDontCare(ParserDontCare parserDontCare) {
    term = DontCare.DONTCARE;
//typeCheck();
  }

  public void visitFunctionApplication(ParserFunctionApplication parserFunctionApplication) {
    LinkedList<Term> args = new LinkedList<Term>();
    Function function = signature.getFunction(parserFunctionApplication.function);
    if (function == null)
      throw new ShellException("There is no function with name " + parserFunctionApplication.function);
    int index = 0;
    if (function.getArity() != parserFunctionApplication.args.size()) {
      throw new ShellException("Function " + function.getName() + " has arity " + function.getArity() + " but " +
              "it is applied with " + parserFunctionApplication.args.size() + " in " + parserFunctionApplication);
    }
    for (ParserTerm term : parserFunctionApplication.args) {
      typeContext.push(function.getArgumentTypes().get(index++));
      term.acceptParserTermVisitor(this);
      args.add(this.term);
      typeContext.pop();
    }
    term = new FunctionApplication(function, args);
    typeCheck();
  }

  public void visitDoubleConstant(ParserDoubleConstant parserDoubleConstant) {
    term = new DoubleConstant(parserDoubleConstant.number);
    typeCheck();
  }

  private Variable resolve(String name) {
    for (HashMap<String, Variable> scope : variables) {
      Variable var = scope.get(name);
      if (var != null) return var;
    }
    return null;
  }

  public void visitVariable(ParserVariable parserVariable) {
    term = resolve(parserVariable.name);
    WeightFunction function = signature.getWeightFunction(parserVariable.name);
    if (term != null && function != null)
      throw new ShellException("We don't like this ambiguity: " + parserVariable.name + " is both a variable " +
              "and a (zero-arity) weight function. Why we could resolve this in a clever manner, we refrain from doing so " +
              "to make sure you know what you're doing.");
    if (term == null) {
      if (function == null)
        throw new RuntimeException(parserVariable.name + " was not quantified in " + rootFactor);
      LinkedList<Term> args = new LinkedList<Term>();
      term = new FunctionApplication(function, args);
      typeCheck();
    } else
      typeCheck();

  }

  public void visitBins(ParserBins parserBins) {
    LinkedList<Integer> bins = new LinkedList<Integer>();
    for (ParserTerm term : parserBins.bins) {
      if (term instanceof ParserIntConstant) {
        ParserIntConstant intConstant = (ParserIntConstant) term;
        bins.add(intConstant.number);
      } else
        throw new ShellException("bins must be integers");
    }
    parserBins.argument.acceptParserTermVisitor(this);
    term = new BinnedInt(bins, term);
    typeCheck();
  }

  public void visitBoolConstant(ParserBoolConstant parserBoolConstant) {
    term = new BoolConstant(parserBoolConstant.value);
    typeCheck();
  }


  private void typeCheck() {
    if (!typeContext.isEmpty() && !term.getType().inherits(typeContext.peek()))
      throw new RuntimeException("Variable " + term + " must be of type " + typeContext.peek() + " in " +
              rootFactor);
  }

  /**
   * Gets a factory which can build corpora. We provide a few built-in factories but user defined ones can be added (and
   * then used from within the interpreter).
   *
   * @param name the name of the factory
   * @return a corpus factory that his been registered with this beast under the given name
   */
  public CorpusFactory getCorpusFactory(String name) {
    return corpusFactories.get(name);
  }

  /**
   * Registers a corpus factory under a name which can be referred to from within the  interpreter.
   *
   * @param name    name of the factory
   * @param factory the factory to be registered.
   */
  public void registerCorpusFactory(String name, CorpusFactory factory) {
    corpusFactories.put(name, factory);
  }

  public void registerTypeGenerator(String name, TypeGenerator generator) {
    typeGenerators.put(name, generator);
  }

  private void initCorpusTools() {
    registerCorpusFactory(null, new TextFileCorpus.Factory());
    registerCorpusFactory("ram", RandomAccessCorpus.FACTORY);
    registerCorpusFactory("malt", new MALTFactory());
    registerTypeGenerator("malt", MALTFactory.GENERATOR);
    registerCorpusFactory("conll06", new CoNLL06Factory());
    registerTypeGenerator("conll06", CoNLL06Factory.GENERATOR);
    registerCorpusFactory("conll00", new CoNLL00Factory());
    registerCorpusFactory("conll00noisy", new CoNLL00Factory(true));
    registerTypeGenerator("conll00", CoNLL00Factory.GENERATOR);
    registerTypeGenerator("conll00noisy", CoNLL00Factory.GENERATOR_NOISYPOS);
    registerPrinter("conll00", new CoNLL00SentencePrinter());
    registerPrinter("conll05", new CoNLL05SentencePrinter());
    registerPrinter("semtag", new SemtagPrinter());
    registerPrinter("mt", new MTPrinter());
    registerPrinter("mtm4", new MTModel4Printer());
    registerPrinter("mtm4out", new MTModel4Printer(false));
    registerPrinter("align", new AlignmentPrinter());
    registerPrinter("default", new DefaultPrinter());
    registerEvaluator("F1 SRL", new CoNLL05Evaluator(CoNLL05Evaluator.Type.F1));
    registerEvaluator("Recall SRL", new CoNLL05Evaluator(CoNLL05Evaluator.Type.RECALL));
    registerEvaluator("Precision SRL", new CoNLL05Evaluator(CoNLL05Evaluator.Type.PRECISION));
  }

  public void registerEvaluator(String name, CorpusEvaluationFunction function) {
    evalFunctions.put(name, function);
  }

  public void registerPrinter(String name, GroundAtomsPrinter printer) {
    printers.put(name, printer);
  }

  public void setArgs(String[] args) {
    this.args = args;
  }

  public String getDirectory() {
    return directory;
  }

  public static interface PropertySetter {
    void set(ParserName name, ParserTerm term);
  }

  private void printHistory() {
    Solution solution = new Solution(model, weights);
    Evaluation evaluation = new Evaluation(model);
    out.printf("%-10s %-10s %-10s %-10s %-10s\n", "Iter.", "F1", "Score", "Violated", "Total");
    for (int i = 0; i < 55; ++i) out.print("-");
    out.println();
    for (int i = 0; i < solver.getCandidateCount(); ++i) {
      GroundFormulas formulas = new GroundFormulas(model, weights);
      formulas.update(solver.getCandidateAtoms(i));
      solution.load(solver.getCandidateAtoms(i), formulas);
      //solution.load(solver.getCandidateAtoms(i), solver.getCandidateFormulas(i));
      FeatureVector vector = solution.extract(solver.getLocalFeatures());
      evaluation.evaluate(gold, solution.getGroundAtoms());
      out.printf("%-10d %-10.3f %-10.4f %-10d %-10d\n", i, evaluation.getF1(), weights.score(vector),
              formulas.getViolationCount(), solver.getCandidateFormulas(i).getNewCount());
      //System.out.println(solver.getCandidateFormulas().get(i));
    }

    out.println();
    out.println("Best Solution");
    out.printf("%-10s %-10s %-10s %-10s\n", "F1", "Score", "Violated", "Total");
    for (int i = 0; i < 55; ++i) out.print("-");
    out.println();
    GroundFormulas formulas = new GroundFormulas(model, weights);
    formulas.update(solver.getBestAtoms());
    solution.load(solver.getBestAtoms(), formulas);
    //solution.load(solver.getCandidateAtoms(i), solver.getCandidateFormulas(i));
    FeatureVector vector = solution.extract(solver.getLocalFeatures());
    evaluation.evaluate(gold, solution.getGroundAtoms());
    out.printf("%-10.3f %-10.4f %-10d %-10d\n", evaluation.getF1(), weights.score(vector),
            formulas.getViolationCount(), solver.getBestFormulas().getNewCount());
    out.println();
    out.println("Memory usage (bytes): " + Runtime.getRuntime().totalMemory());
    out.println("Time spent (ms): " + solver.getTimeSpent());
    out.println("Iterations: " + solver.getIterationCount());
    out.println();

    //GroundAtoms bestAtoms = solver.getCandidateAtoms(0);
    GroundAtoms bestAtoms = solver.getBestAtoms();
    //solution.load(bestAtoms, solver.getBestFormulas());
    evaluation.evaluate(gold, bestAtoms);
    for (UserPredicate pred : model.getHiddenPredicates()) {
      System.out.println(pred.getName());
      for (int i = 0; i < 20; ++i) out.print("-");
      out.println();
      out.printf("%-15s %-5d\n", "Count:", bestAtoms.getGroundAtomsOf(pred).size());
      out.printf("%-15s %-5.3f\n", "Precision:", evaluation.getPrecision(pred));
      out.printf("%-15s %-5.3f\n", "Recall:", evaluation.getRecall(pred));
      out.printf("%-15s %-5.3f\n", "F1:", evaluation.getF1(pred));
      out.println();
    }

  }


}
