
package de.fraunhofer.aisec.analysis.markevaluation;

import com.google.common.collect.Lists;
import de.fraunhofer.aisec.analysis.structures.AnalysisContext;
import de.fraunhofer.aisec.analysis.structures.CPGInstanceContext;
import de.fraunhofer.aisec.analysis.structures.CPGVariableContext;
import de.fraunhofer.aisec.analysis.structures.CPGVertexWithValue;
import de.fraunhofer.aisec.analysis.structures.Finding;
import de.fraunhofer.aisec.analysis.structures.Pair;
import de.fraunhofer.aisec.analysis.structures.ResultWithContext;
import de.fraunhofer.aisec.analysis.structures.ServerConfiguration;
import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.helpers.Benchmark;
import de.fraunhofer.aisec.crymlin.CrymlinQueryWrapper;
import de.fraunhofer.aisec.crymlin.connectors.db.TraversalConnection;
import de.fraunhofer.aisec.crymlin.dsl.CrymlinTraversalSource;
import de.fraunhofer.aisec.mark.markDsl.AliasedEntityExpression;
import de.fraunhofer.aisec.mark.markDsl.Expression;
import de.fraunhofer.aisec.mark.markDsl.OpStatement;
import de.fraunhofer.aisec.mark.markDsl.RuleStatement;
import de.fraunhofer.aisec.markmodel.MEntity;
import de.fraunhofer.aisec.markmodel.MOp;
import de.fraunhofer.aisec.markmodel.MRule;
import de.fraunhofer.aisec.markmodel.Mark;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static java.lang.Math.toIntExact;

/**
 * Evaluates all loaded MARK rules against the CPG.
 *
 * returns a number of Findings if any of the rules are violated
 */
public class Evaluator {
	private static final Logger log = LoggerFactory.getLogger(Evaluator.class);

	@NonNull
	private final Mark markModel;
	@NonNull
	private final ServerConfiguration config;

	public Evaluator(@NonNull Mark markModel, @NonNull ServerConfiguration config) {
		this.markModel = markModel;
		this.config = config;
	}

	/**
	 * Evaluates the {@code markModel} against the currently analyzed program (CPG).
	 *
	 * <p>
	 * This is the core of the MARK evaluation.
	 *
	 * @param result representing the analysed program, i.e., the CPG
	 * @param ctx [out] the context storing the result of the evaluation. This could also include results from previous steps
	 */
	public TranslationResult evaluate(@NonNull TranslationResult result, @NonNull final AnalysisContext ctx) {

		Benchmark bOuter = new Benchmark(this.getClass(), "Mark evaluation");

		try (TraversalConnection traversal = new TraversalConnection(TraversalConnection.Type.OVERFLOWDB)) { // connects to the DB

			log.info("Precalculating matching nodes");
			assignCallVerticesToOps(traversal.getCrymlinTraversal());

			log.info("Evaluate forbidden calls");
			Benchmark b = new Benchmark(this.getClass(), "Evaluate forbidden calls");
			ForbiddenEvaluator forbiddenEvaluator = new ForbiddenEvaluator(this.markModel);
			forbiddenEvaluator.evaluate(ctx);
			b.stop();

			log.info("Evaluate rules");
			b = new Benchmark(this.getClass(), "Evaluate rules");
			evaluateRules(ctx, traversal.getCrymlinTraversal());
			b.stop();

			bOuter.stop();

			return result;
		}
		finally {
			// reset everything attached to this model
			this.markModel.reset();
		}
	}

	/**
	 * Iterate over all MOps in all MEntities, find all call statements in CPG and assign them to their respective MOp.
	 *
	 * After this method, all call statements can be retrieved by MOp.getAllVertices(), MOp.getStatements(), and MOp.getVertexToCallStatementsMap().
	 *
	 * @param crymlinTraversal traversal-connection to the DB
	 */
	private void assignCallVerticesToOps(@NonNull CrymlinTraversalSource crymlinTraversal) {
		Benchmark b = new Benchmark(this.getClass(), "Precalculating matching nodes");
		// iterate over all entities and precalculate:
		// - call statements to vertices
		for (MEntity ent : markModel.getEntities()) {
			log.info("Precalculating call statements for entity {}", ent.getName());
			ent.parseVars();
			for (MOp op : ent.getOps()) {
				log.debug("Looking for call statements for {}", op.getName());
				int numMatches = 0;
				for (OpStatement a : op.getStatements()) {
					Set<Vertex> temp = CrymlinQueryWrapper.getVerticesForFunctionDeclaration(a.getCall(), ent, crymlinTraversal);
					log.debug(
						"{}({}):{}",
						a.getCall().getName(),
						String.join(", ", a.getCall().getParams()),
						temp.size());
					numMatches += temp.size();
					op.addVertex(a, temp);
				}
				op.setParsingFinished();
				if (numMatches > 0) {
					log.info("Found {} call statements in the cpg for {}", numMatches, op.getName());
				}
			}
		}
		b.stop();
	}

	/**
	 * Evaluates the "when" and "ensure" part of a rule
	 *
	 * @param ctx the result/analysis context
	 * @param crymlinTraversal connection to the db
	 */
	private void evaluateRules(AnalysisContext ctx, @NonNull CrymlinTraversalSource crymlinTraversal) {

		for (MRule rule : this.markModel.getRules()) {
			ExpressionEvaluator ee = new ExpressionEvaluator(rule, ctx, config, crymlinTraversal);

			RuleStatement s = rule.getStatement();
			log.info("checking rule {}", rule.getName());

			// collect all entities, and calculate which instances correspond to the entity
			// Stores one List for each markinstance with corresponding vertices. E.g.:
			// [[(r, v123), (r, v23)], [(cm, v163), (cm, v33)],
			List<List<Pair<String, Vertex>>> entities = new ArrayList<>();
			// Evaluation of "using" part:
			// Find entities whose ops are used in the current Mark rule.
			// We collect all entities and calculate which instances (=program variables) correspond to the entity.
			// entities is a map with key: name of the Mark Entity (e.g., "b"). value: Vertex to which the program variable REFERS_TO.
			for (AliasedEntityExpression entity : s.getEntities()) {
				HashSet<Vertex> bases = new HashSet<>();
				MEntity referencedEntity = this.markModel.getEntity(entity.getE());
				if (referencedEntity != null) {
					for (MOp op : referencedEntity.getOps()) {
						for (Vertex vertex : op.getAllVertices()) {
							Iterator<Edge> it = vertex.edges(Direction.OUT, "BASE");
							Vertex ref = null;
							if (it.hasNext()) {
								Vertex baseVertex = it.next().inVertex();
								Iterator<Edge> refIterator = baseVertex.edges(Direction.OUT, "REFERS_TO");
								if (refIterator.hasNext()) {
									ref = refIterator.next().inVertex();
									bases.add(ref);
								}
							}

							if (ref == null) {
								log.warn("Did not find a base for " + vertex.value("code"));
							}
						}
					}
					ArrayList<Pair<String, Vertex>> innerList = new ArrayList<>();
					for (Vertex v : bases) {
						innerList.add(new Pair<>(entity.getN(), v));
					}
					entities.add(innerList);
				} // else: unknown Entity referenced, this rule does not make much sense

			}

			// Generate all combinations of instances for each entity.
			// We take the cartesian product of all _possible_ program variables that correspond to Mark entities.
			// A CPGInstanceContext is a specific interpretation of a Mark rule that needs to be evaluated.
			List<CPGInstanceContext> instanceContexts = new ArrayList<>();
			for (List<Pair<String, Vertex>> list : Lists.cartesianProduct(entities)) {
				CPGInstanceContext instance = new CPGInstanceContext();
				for (Pair<String, Vertex> p : list) {
					instance.putMarkInstance(p.getValue0(), p.getValue1());
				}
				instanceContexts.add(instance);
			}
			ArrayList<ResultWithContext> results = new ArrayList<>();

			for (CPGInstanceContext instanceContext : instanceContexts) {
				ee.setCPGInstanceContext(instanceContext);

				// Evaluate "when" part, if present
				if (s.getCond() != null) {
					List<ResultWithContext> resultsCond = evaluateExpressionWithContext(null, s.getCond().getExp(), ee, rule, crymlinTraversal);
					for (ResultWithContext resultCond : resultsCond) {
						if (!(resultCond.get() instanceof Boolean)) {
							log.error("Result is of type {}, expected boolean.", resultCond.getClass());
							continue;
						}
						if (resultCond.get().equals(false)) {
							log.info("Precondition is false, do not evaluate ensure.");
							continue;
						}

						results.addAll(evaluateExpressionWithContext(resultCond.getVariableContext(), s.getEnsure().getExp(), ee, rule, crymlinTraversal));
					}
				} else {
					results.addAll(evaluateExpressionWithContext(null, s.getEnsure().getExp(), ee, rule, crymlinTraversal));
				}
			}

			log.info("Got {} results", results.size());
			for (ResultWithContext result : results) {
				// the value of the result should always be boolean, as this should be the result of the topmost expression
				if (result.get() instanceof Boolean) {
					// if the result of the expression evaluation is false, and we did not add a finding during expression
					// evaluation (e.g., as it is the case in the order evaluation), add a new finding which references all
					// responsible vertices
					if (result.get().equals(false) && !result.isFindingAlreadyAdded()) {
						List<Range> ranges = new ArrayList<>();
						if (result.getResponsibleVertices().isEmpty()) {
							ranges.add(new Range(new Position(-1, -1),
								new Position(-1, -1)));
						} else {
							for (Vertex v : result.getResponsibleVertices()) {
								int startLine = toIntExact((Long) v.property("startLine").value()) - 1;
								int endLine = toIntExact((Long) v.property("endLine").value()) - 1;
								int startColumn = toIntExact((Long) v.property("startColumn").value()) - 1;
								int endColumn = toIntExact((Long) v.property("endColumn").value()) - 1;
								ranges.add(new Range(new Position(startLine, startColumn),
									new Position(endLine, endColumn)));
							}
						}
						ctx.getFindings()
								.add(new Finding(
									"MarkRuleEvaluationFinding: Rule "
											+ rule.getName()
											+ " violated",
									ctx.getCurrentFile(),
									rule.getErrorMessage(),
									ranges));
					}
				} else {
					log.error("Unable to evaluate rule {}", rule.getName());
				}
			}
		}
	}

	/**
	 * Evaluates an expression with the given expressionevaluator in the context of one rule. The instances of markvars used in the expression is set before (this is
	 * stored in the expressionEvaluator)
	 *
	 * @param previousVariableContext Context, as set by evaluation of "when" part. Always null, if no "when" part exists.
	 * @param expression The expression to be evaluated
	 * @param expressionEvaluator The expressionevaluator
	 * @param markRule The rule the expression is contained in
	 * @param crymlin
	 * @return List of results for this expression. The list contains multiple results, if one or more markvars have more corresponding vertices in the CPG
	 */
	private List<ResultWithContext> evaluateExpressionWithContext(@Nullable CPGVariableContext previousVariableContext, Expression expression,
			ExpressionEvaluator expressionEvaluator, MRule markRule,
			CrymlinTraversalSource crymlin) {

		HashSet<String> newMarkVars = new HashSet<>();
		ExpressionHelper.collectVars(expression, newMarkVars); // extract all used markvars from the expression
		if (previousVariableContext != null) {
			// if we previously already set some of the markvars, these are not new here, and we do not want to set them again
			newMarkVars.removeAll(previousVariableContext.keySet());
		}

		// get all possible assignments for all markvars
		// Stores one List for each markvar with corresponding vertices. E.g.:
		// [[(r.rand, v123), (r.rand, v23)], [(cm.alg, v163), (cm.alg, v33)],
		List<List<Pair<String, CPGVertexWithValue>>> varAssignments = new ArrayList<>();
		for (String markVar : newMarkVars) {
			List<Vertex> matchingVertices = CrymlinQueryWrapper.getMatchingVertices(markVar, markRule, crymlin);
			List<CPGVertexWithValue> assignments = CrymlinQueryWrapper.getAssignmentsForVertices(matchingVertices);
			List<Pair<String, CPGVertexWithValue>> innerList = new ArrayList<>();
			for (CPGVertexWithValue vertexWithValue : assignments) {
				innerList.add(new Pair<>(markVar, vertexWithValue));
			}
			varAssignments.add(innerList);
		}

		// calculate all combination of all possible assignments of the vars
		List<CPGVariableContext> variableContexts = new ArrayList<>();
		if (!varAssignments.isEmpty()) {
			// if we have new variableAssignments in this evaluation run, calculate all possible combinations
			for (List<Pair<String, CPGVertexWithValue>> list : Lists.cartesianProduct(varAssignments)) {
				CPGVariableContext variableContext = new CPGVariableContext();
				if (previousVariableContext != null) {
					variableContext.fillFrom(previousVariableContext);
				}
				for (Pair<String, CPGVertexWithValue> p : list) {
					variableContext.put(p.getValue0(), p.getValue1());
				}
				variableContexts.add(variableContext);
			}
		} else {
			// else, just use the variable context of the previous evaluation run, since the vars did not change
			if (previousVariableContext != null) {
				variableContexts.add(previousVariableContext);
			}
		}

		List<ResultWithContext> allresults = new ArrayList<>();
		if (!variableContexts.isEmpty()) {
			for (CPGVariableContext variableContext : variableContexts) {
				expressionEvaluator.setCPGVariableContext(variableContext);
				// do the evaluation of the expression for one variableContext (and one instancecontext which was set before)
				try {
					ResultWithContext result = expressionEvaluator.evaluate(expression);
					// result has the variablecontext already set to variableContext

					allresults.add(result);
				}
				catch (ExpressionEvaluationException e) {
					log.error(e.getMessage(), e);
				}
			}
		} else {
			// can this be that we do not have any markvar in a rule?
			expressionEvaluator.setCPGVariableContext(new CPGVariableContext()); // empty context
			try {
				ResultWithContext result = expressionEvaluator.evaluate(expression);

				allresults.add(result);
			}
			catch (ExpressionEvaluationException e) {
				log.error(e.getMessage(), e);
			}
		}

		return allresults;
	}
}
