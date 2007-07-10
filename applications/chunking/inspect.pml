include "corpora/train.types.pml";

include "model.pml";

load global from "global.txt";
load global.rare from "corpora/rare.txt";


set instancesCacheSize = 3;

load corpus from conll00noisy "corpora/test.conll";

save corpus (0-100) to ram;

//load weights from dump "/tmp/weights.dmp";
load weights from dump "/tmp/epoch_15.dmp";

set solver.ilp.solver = "lpsolve";
set solver.integer = true;
set solver.bbDepthLimit = 10;
set solver.deterministicFirst = false;
set solver.maxIterations = 10;