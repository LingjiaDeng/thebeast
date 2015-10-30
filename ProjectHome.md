# Introduction #
markov thebeast is a Statistical Relational Learning software based on [Markov Logic](http://en.wikipedia.org/wiki/Markov_logic_network).

It allows you to implement relational learning and structured predictions problems such as
  * Entity Resolution
  * Link Prediction
  * Dependency Parsing
  * Semantic Tagging
  * Sentence Compression
  * Chunking

by simply defining a model and providing training data. Learning and inference are both handled by thebeast.

thebeast uses Markov Logic as language to describe complex Markov Networks. In Markov Logic first order predicates and formulas describe the nodes and connectivity of Markov Networks. Compared to alchemy, another Markov Logic Engine, thebeast uses a different MAP inference technique (Integer Linear Programming with Cutting Planes), and may be faster at times.

# Features #
  * Fast and exact MAP inference using Cutting Planes combined with Integer Linear Programming (or Max-Walk-Sat), see the corresponding paper on [Cutting Plane Inference for Markov Logic](http://sites.google.com/site/riedelcastro/riedel08improving)
  * Online Discriminative Training using MIRA, Perceptron and Passive Aggressive Learning
  * Parametrized weights: use one formula to describe millions of features with individual weights
  * Shell: Use a interpreter to edit and inspect your model. Allows you to analyze errors, inspect feature weights and scores
  * Cardinality constraints: make statements about how often certain conditions should hold in a solution.

# Missing Features #
  * A more complete manual
  * Inference for marginal probabilities (Gibbs Sampling)
  * Pseudo-Likelihood training
  * EM
  * Structure-Learning: automatically find good formulas
  * ...

For these I recommend the [alchemy system](http://alchemy.cs.washington.edu/).

# Your Work #
If you use thebeast for your project and it has been helpful (or not) I'd be happy to hear about it. I'd also be happy if you could send me a copy of any published work that uses thebeast. Finally, it would be great if you could cite this [paper](http://riedelcastro.github.com/publications/details/riedel08improving.html) in such a publication.


