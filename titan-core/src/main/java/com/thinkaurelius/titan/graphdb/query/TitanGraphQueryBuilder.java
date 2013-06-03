package com.thinkaurelius.titan.graphdb.query;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.thinkaurelius.titan.core.TitanElement;
import com.thinkaurelius.titan.core.TitanGraphQuery;
import com.thinkaurelius.titan.core.TitanKey;
import com.thinkaurelius.titan.core.TitanType;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.core.attribute.Interval;
import com.thinkaurelius.titan.graphdb.query.keycondition.KeyAnd;
import com.thinkaurelius.titan.graphdb.query.keycondition.KeyAtom;
import com.thinkaurelius.titan.graphdb.query.keycondition.KeyCondition;
import com.thinkaurelius.titan.graphdb.query.keycondition.KeyNot;
import com.thinkaurelius.titan.graphdb.query.keycondition.KeyOr;
import com.thinkaurelius.titan.graphdb.query.keycondition.Relation;
import com.thinkaurelius.titan.graphdb.relations.AttributeUtil;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;
import com.thinkaurelius.titan.util.stats.ObjectAccumulator;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class TitanGraphQueryBuilder implements TitanGraphQuery, QueryOptimizer<StandardElementQuery>  {

    private static final Logger log = LoggerFactory.getLogger(TitanGraphQueryBuilder.class);


    private static final List<KeyCondition<TitanKey>> INVALID = ImmutableList.of();

    private final StandardTitanTx tx;
    private List<? super KeyCondition<TitanKey>> conditions;
    private int limit = Query.NO_LIMIT;
    private int skip = 0; // Skip nothing by default

    public TitanGraphQueryBuilder(StandardTitanTx tx) {
        Preconditions.checkNotNull(tx);
        this.tx=tx;
        this.conditions = Lists.newArrayList();
    }

    private boolean isInvalid() {
        return conditions==INVALID || limit==0;
    }

    @Override
    public TitanGraphQuery has(String key, Relation relation, Object condition) {
        Preconditions.checkNotNull(key);
        TitanType type = tx.getType(key);
        if (type==null || !(type instanceof TitanKey)) {
            if (tx.getConfiguration().getAutoEdgeTypeMaker().ignoreUndefinedQueryTypes()) {
                conditions = INVALID;
                return this;
            } else {
                throw new IllegalArgumentException("Unknown or invalid property key: " + key);
            }
        } else return has((TitanKey) type, relation, condition);
    }

    @Override
    public TitanGraphQuery has(String key, Compare relation, Object value) {
        return has(key,Cmp.convert(relation),value);
    }

    @Override
    public TitanGraphQuery has(TitanKey key, Relation relation, Object condition) {
        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(relation);
        condition=AttributeUtil.verifyAttributeQuery(key,condition);
        Preconditions.checkArgument(relation.isValidCondition(condition),"Invalid condition: %s",condition);
        Preconditions.checkArgument(relation.isValidDataType(key.getDataType()),"Invalid data type for condition: %s",key.getDataType());
        if (conditions!=INVALID) {
            conditions.add(new KeyAtom<TitanKey>(key,relation,condition));
        }
        return this;
    }

    @Override
    public <T extends Comparable<T>> TitanGraphQuery has(String s, T t, Compare compare) {
        return has(s,Cmp.convert(compare),t);
    }

    @Override
    public <T extends Comparable<T>> TitanGraphQuery has(String key, Compare compare, T value) {
        return has(key, compare, (Object)value);
    }

    @Override
    public <T extends Comparable<T>> TitanGraphQuery interval(String s, T t, T t2) {
        return has(s,Cmp.INTERVAL,new Interval<T>(t,t2));
    }

    private StandardElementQuery constructQuery(StandardElementQuery.Type elementType) {
        Preconditions.checkNotNull(elementType);
        return new StandardElementQuery(elementType,KeyAnd.of(conditions.toArray(new KeyCondition[conditions.size()])),limit,skip,null);
    }

    @Override
    public Iterable<Vertex> vertices() {
        if (isInvalid()) return ImmutableList.of();
        StandardElementQuery query = constructQuery(StandardElementQuery.Type.VERTEX);
        return Iterables.filter(new QueryProcessor<StandardElementQuery,TitanElement>(query,tx.elementProcessor,this),Vertex.class);
    }

    @Override
    public Iterable<Edge> edges() {
        if (isInvalid()) return ImmutableList.of();
        StandardElementQuery query = constructQuery(StandardElementQuery.Type.EDGE);
        return Iterables.filter(new QueryProcessor<StandardElementQuery,TitanElement>(query,tx.elementProcessor,this),Edge.class);
    }


    @Override
    public TitanGraphQueryBuilder limit(long max) {
        Preconditions.checkArgument(max>=0,"Non-negative limit expected: %s",max);
        Preconditions.checkArgument(max<=Integer.MAX_VALUE,"Limit expected to be smaller or equal than [%s] but given %s",Integer.MAX_VALUE,limit);
        this.limit=(int)max;
        return this;
    }

    @Override
    public List<StandardElementQuery> optimize(StandardElementQuery query) {
        if (query.isInvalid()) return ImmutableList.of();
        /*
         * Find most suitable index. A "most suitable" index is one which covers
         * the largest number of KeyAtoms in query.
         */
        ObjectAccumulator<String> opt = new ObjectAccumulator<String>(5);
        KeyCondition<TitanKey> condition = query.getCondition();
        if (condition.hasChildren()) {
            Preconditions.checkArgument(condition instanceof KeyAnd);
            for (KeyCondition<TitanKey> c : condition.getChildren()) {
                // TODO look for KeyAtoms or completely-covered KeyOrs
                if (c instanceof KeyAtom) {
                    KeyAtom<TitanKey> atom = (KeyAtom<TitanKey>)c;
                    if (atom.getCondition()==null) continue; //Cannot answer those with index
                    for (String index : atom.getKey().getIndexes(query.getType().getElementType())) {
                        if (tx.getGraph().getIndexInformation(index).supports(atom.getKey().getDataType(),atom.getRelation()))
                            opt.incBy(index,1);
                    }
                } else if (c instanceof KeyOr) {
                    // TODO
                } else if (c instanceof KeyNot) {
                    // TODO
                }
            }
        }
        String bestIndex = opt.getMaxObject();
        log.debug("Best index for query [{}]: {}",query,bestIndex);
        if (bestIndex!=null) return ImmutableList.of(new StandardElementQuery(query,bestIndex));
        else return ImmutableList.of(query);
    }

    @Override
    public GraphQuery has(String keyName, Object... values) {
        if (null == values || 0 == values.length) {
            // Match any element for which keyName is set, regardless of the literal value
            return has(keyName, Cmp.NOT_EQUAL, (Object)null);
        } else {
            return has(keyName, Cmp.EQUAL, values);
        }
    }

    @Override
    public GraphQuery hasNot(String keyName, Object... values) {
        if (null == values || 0 == values.length) {
            Preconditions.checkNotNull(keyName);
            // Match any element for which keyName is unset/null
            TitanType type = tx.getType(keyName);
            if (type==null || !(type instanceof TitanKey)) {
                if (tx.getConfiguration().getAutoEdgeTypeMaker().ignoreUndefinedQueryTypes()) {
                    /*
                     * This matches every element in the graph because if the
                     * type does not exist, then it's impossible for any element
                     * to have a value for that element. We don't have to add a
                     * condition.
                     */
                    return this;
                } else {
                    throw new IllegalArgumentException("Unknown or invalid property key: " + keyName);
                }
            }
            // The key actually exists
            TitanKey key = (TitanKey)type;
            conditions.add(KeyAtom.of(key, Cmp.EQUAL, null));
            return this;
            // Doesn't work because it prunes elements without the key
//            return has(keyName, Cmp.EQUAL, (Object)null);
        } else {
            return has(keyName, Cmp.NOT_EQUAL, values);
        }
    }

    @Override
    public GraphQuery limit(long skip, long take) {
        // TODO log a warning during execution if an ordering is not also specified?
        Preconditions.checkArgument(0 <= skip);
        Preconditions.checkArgument(Integer.MAX_VALUE >= skip);
        Preconditions.checkArgument(0 <= take);
        Preconditions.checkArgument(Integer.MAX_VALUE >= take);
        this.skip = (int)skip;
        this.limit = (int)take + this.skip;
        return this;
    }
    
    private GraphQuery has(String keyName, Relation rel, Object... values) {
        Preconditions.checkNotNull(keyName);
        Preconditions.checkNotNull(rel);
        Preconditions.checkArgument(Cmp.EQUAL.equals(rel) || Cmp.NOT_EQUAL.equals(rel));
        TitanType type = tx.getType(keyName);
        if (type==null || !(type instanceof TitanKey)) {
            if (tx.getConfiguration().getAutoEdgeTypeMaker().ignoreUndefinedQueryTypes()) {
                conditions = INVALID;
                return this;
            } else {
                throw new IllegalArgumentException("Unknown or invalid property key: " + keyName);
            }
        }
        
        TitanKey key = (TitanKey)type;
        
        if (null == values || 0 == values.length) {
            // Wildcard: key must be present but can assume any value
            return has(keyName, rel, (Object)null);
        } else {
            KeyAtom[] atoms = new KeyAtom[values.length];
            int i = 0;
            for (Object o : values) {
                Preconditions.checkNotNull(o);
                o = AttributeUtil.verifyAttributeQuery((TitanKey)type, o);
                Preconditions.checkArgument(rel.isValidCondition(o), "Invalid condition: %s", o);
                Preconditions.checkArgument(rel.isValidDataType(key.getDataType())," Invalid data type for condition: %s", key.getDataType());
                atoms[i++] = new KeyAtom<TitanKey>(key, rel, o);
            }
            conditions.add((KeyCondition<TitanKey>)KeyOr.of(atoms));
        }
        
        return this;
    }
}
