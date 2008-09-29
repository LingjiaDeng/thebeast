include "TYPES";
include "MODEL";

load corpus from "ATOMS";

load weights from dump "WEIGHTS";

set solver.model.initIntegers = true;
set solver.maxIterations = 20;

test to "LABEL";
