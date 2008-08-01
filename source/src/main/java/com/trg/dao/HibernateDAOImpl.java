package com.trg.dao;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.hibernate.Criteria;
import org.hibernate.FetchMode;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.CriteriaSpecification;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Junction;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.ProjectionList;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.metadata.CollectionMetadata;
import org.hibernate.transform.Transformers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

import com.trg.search.Fetch;
import com.trg.search.Filter;
import com.trg.search.Search;
import com.trg.search.SearchResult;
import com.trg.search.Sort;

@SuppressWarnings("unchecked")
public class HibernateDAOImpl extends HibernateDaoSupport {

	@Autowired
	public void setSessionFactoryAutowire(SessionFactory sessionFactory) {
		setSessionFactory(sessionFactory);
	}

	/**
	 * Add the specified object as a new entry in the database. NOTE: The Java
	 * object is also attached to the Hibernate session in persistent state.
	 */
	protected void _create(Object object) {
		getSession().save(object);
	}

	/**
	 * Delete the object of the specified class with the specified id from the
	 * database.
	 */
	protected void _deleteById(Serializable id, Class klass) {
		if (id == null)
			return;
		getSession().delete(getSession().get(klass, id));
	}

	/**
	 * Delete the specified object from the database.
	 */
	protected void _deleteEntity(Object object) {
		if (object == null)
			return;
		Serializable id;
		try {
			id = (Serializable) object.getClass().getMethod("getId").invoke(object);
			getSession().delete(getSession().get(object.getClass(), id));
		} catch (IllegalArgumentException e) {
			logger.error("Error getting id when attempting to delete entity: " + object.getClass().getName());
		} catch (SecurityException e) {
			logger.error("Error getting id when attempting to delete entity: " + object.getClass().getName());
		} catch (IllegalAccessException e) {
			logger.error("Error getting id when attempting to delete entity: " + object.getClass().getName());
		} catch (InvocationTargetException e) {
			logger.error("Error getting id when attempting to delete entity: " + object.getClass().getName());
		} catch (NoSuchMethodException e) {
			logger.error("Attempt to delete entity with no getId method: " + object.getClass().getName());
		}
	}

	/**
	 * Get the object of the specified class with the specified id from the
	 * database.
	 */
	protected Object _fetch(Serializable id, Class klass) {
		return getSession().get(klass, id);
	}

	/**
	 * Get a list of all the objects of the specified class.
	 */
	protected List _fetchAll(Class klass) {
		return getSession().createCriteria(klass).setResultTransformer(
				Criteria.DISTINCT_ROOT_ENTITY).list();
	}

	/**
	 * Update the corresponding object in the database with the properties of
	 * the specified object. The corresponding object is determined by id. NOTE:
	 * The Java object does not become attached to the Hibernate session. It
	 * remains in its current state.
	 */
	protected void _update(Object object) {
		getSession().merge(object);
	}

	/**
	 * Search for objects based on the search parameters in the specified
	 * <code>Search</code> object.
	 * 
	 * @see Search
	 */
	protected List _search(Search search) {
		if (search == null)
			return null;

		Criteria crit = buildCriteria(search);
		List list = crit.list();
		aliasErrorFix(list, search);

		return list;
	}

	/**
	 * Returns the total number of results that would be returned using the
	 * given <code>Search</code> if there were no paging or maxResult limits.
	 * 
	 * @see Search
	 */
	protected int _searchLength(Search search) {
		if (search == null)
			return 0;

		Criteria crit;
		crit = buildCriteriaWithFiltersOnly(search);
		crit.setProjection(Projections.rowCount());
		return ((Integer) crit.uniqueResult()).intValue();
	}

	/**
	 * Returns a <code>SearchResult</code> object that includes the list of
	 * results like <code>search()</code> and the total length like
	 * <code>searchLength</code>.
	 * 
	 * @see Search
	 */
	protected SearchResult _searchAndLength(Search search) {
		if (search == null)
			return null;

		SearchResult result = new SearchResult();
		result.search = search;
		result.firstResult = search.getFirstResult();
		result.page = search.getPage();
		result.maxResults = search.getMaxResults();

		result.result = _search(search);

		if (search.getMaxResults() > 0) {
			result.totalLength = _searchLength(search);
		} else {
			result.totalLength = result.result.size()
					+ search.calcFirstResult();
		}

		return result;
	}

	/**
	 * Returns true if the object is connected to the current hibernate session.
	 */
	protected boolean _isConnected(Object o) {
		return getSession().contains(o);
	}

	/**
	 * Flushes changes in the hibernate cache to the database.
	 */
	protected void _flush() {
		getSession().flush();
	}

	// ---- Search helpers ----
	/**
	 * The <code>parsePath</code> method returns an array of length two. These
	 * two values BASE and PROPERTY represent the two indexes into that array.
	 * <code>result[BASE]</code> is the base path of the object with the final
	 * property, and <code>result[PROPERTY]</code> is the name of the property
	 * specified on that object.
	 * 
	 * @see parsePath()
	 */
	protected static final int BASE = 0, PROPERTY = 1;

	/**
	 * Key for root criteria in the critMap that is passed around when building
	 * a criteria from a Search. The root criteria is the Criteria object that
	 * is actually used in the hibernate query.
	 */
	protected static final String ROOT_CRIT = "";

	/**
	 * @see aliasErrorFix()
	 */
	protected static final String ALIAS_HACK_PREFIX = "///sadHaCk";

	/**
	 * Used in addPaging();
	 * 
	 * @see addPaging()
	 */
	protected static Map<String, List<String>> eagerCollections;

	/**
	 * Build a hibernate <code>Criteria</code> using all the properties and
	 * features of the given <code>Search</code>. The <code>Criteria</code>
	 * that is returned is ready to be executed.
	 */
	protected Criteria buildCriteria(Search search) {
		Map<String, Criteria> critMap = startCriteria(search);

		// if related collections are fetched eagerly (by join), this
		// DISTINCT_ROOT_ENTITY result transformer is needed to make sure each
		// result only shows up once. Note that when using any of the fetch mode
		// options other than FETCH_ENTITY is used, this technique does not
		// work.
		critMap.get(ROOT_CRIT).setResultTransformer(
				Criteria.DISTINCT_ROOT_ENTITY);

		addFilters(critMap, search);
		addPaging(critMap, search);
		addOrdering(critMap, search);
		addFetching(critMap, search);

		return critMap.get(ROOT_CRIT);
	}

	/**
	 * Build a hibernate <code>Criteria</code> using only the filter
	 * properties of the given <code>Search</code>. Paging, Ordering,
	 * Fetching, and Selects are ignored. This is useful for the
	 * <code>_searchLength</code> operation. The <code>Criteria</code> that
	 * is returned is ready to be executed.
	 */
	protected Criteria buildCriteriaWithFiltersOnly(Search search) {
		Map<String, Criteria> critMap = startCriteria(search);
		addFilters(critMap, search);

		return critMap.get(ROOT_CRIT).setResultTransformer(
				Criteria.DISTINCT_ROOT_ENTITY);
	}

	protected Map<String, Criteria> startCriteria(Search search) {
		Map<String, Criteria> critMap = new HashMap<String, Criteria>();
		critMap.put(ROOT_CRIT, getSession().createCriteria(
				search.getSearchClass()));
		return critMap;
	}

	protected void addPaging(Map<String, Criteria> critMap, Search search) {
		addPaging(critMap.get(ROOT_CRIT), search);
	}

	/**
	 * This method adds the page and max results options to the criteria if
	 * specified by the search. It also deals with a tricky issue with database
	 * paging when fetching collections eagerly. Eager collection fetching is
	 * accomplished by joining the tables and having a row in the result set for
	 * each collection item. This messes with the paging options. So this method
	 * checks if paging is being used on an entity that has eagerly fetched
	 * collection properties. If it does it forces those to load by separate
	 * selects in this case.
	 * 
	 * @param crit
	 * @param search
	 */
	protected void addPaging(Criteria crit, Search search) {
		crit.setFirstResult(search.calcFirstResult());
		if (search.getMaxResults() > 0)
			crit.setMaxResults(search.getMaxResults());

		// The rest of this code deals with the eager collection fetching paging
		// conflict
		if (search.calcFirstResult() > 0 || search.getMaxResults() > 0) {
			if (eagerCollections == null) {
				// eagerCollections should contain all collections that are to
				// be loaded eagerly.
				// the first time we use it we need to fill it.
				eagerCollections = new HashMap<String, List<String>>();
				for (Object o : getSessionFactory().getAllCollectionMetadata()
						.entrySet()) {
					Map.Entry entry = (Map.Entry) o;
					if (!((CollectionMetadata) entry.getValue()).isLazy()) {
						String key = (String) entry.getKey();
						int pos = key.lastIndexOf('.');
						List<String> list = eagerCollections.get(key.substring(
								0, pos));
						if (list == null) {
							list = new ArrayList<String>(1);
							eagerCollections.put(key.substring(0, pos), list);
						}
						list.add(key.substring(pos + 1));
					}
				}
			}
			// if any apply to the searchClass, set FetchMode.SELECT for those
			// properties.
			List<String> myCollections = eagerCollections.get(search
					.getSearchClass().getName());
			if (myCollections != null) {
				for (String property : myCollections) {
					crit.setFetchMode(property, FetchMode.SELECT);
				}
			}
		}
	}

	protected void addOrdering(Map<String, Criteria> critMap, Search search) {
		Iterator<Sort> sorts = search.sortIterator();
		while (sorts.hasNext()) {
			Sort sort = sorts.next();
			if (sort == null || sort.property == null
					|| "".equals(sort.property))
				continue;
			String[] parsed = parsePath(sort.property);
			Criteria crit = getCriteria(critMap, parsed[BASE]);

			if (sort.desc)
				crit.addOrder(Order.desc(parsed[PROPERTY]));
			else
				crit.addOrder(Order.asc(parsed[PROPERTY]));
		}
	}

	protected void addFetching(Map<String, Criteria> critMap, Search search) {
		// FetchMode Entity
		if (search.getFetchMode() == Search.FETCH_ENTITY) {
			Iterator<Fetch> fetches = search.fetchIterator();
			while (fetches.hasNext()) {
				String property = fetches.next().property;
				if (property == null || "".equals(property))
					continue;
				String[] parsed = parsePath(property);
				Criteria crit = getCriteria(critMap, parsed[BASE]);

				crit.setFetchMode(parsed[PROPERTY], FetchMode.JOIN);
			}
			// FetchMode Array, List, Map
		} else {
			if (search.getFetchMode() == Search.FETCH_ENTITY)
				return;

			ProjectionList projectionList = Projections.projectionList();
			Iterator<Fetch> selects = search.fetchIterator();
			int i = -1;
			while (selects.hasNext()) {
				i++;
				Fetch select = selects.next();
				if (select == null || select.property == null
						|| "".equals(select.property) || select.key == null
						|| "".equals(select.key))
					continue;

				String[] parsed = parsePath(select.property);
				String path = select.property;
				String alias = select.key;
				if (!"".equals(parsed[BASE])) {
					path = getCriteria(critMap, parsed[BASE]).getAlias() + "."
							+ parsed[PROPERTY];
					// if (alias.equals(parsed[PROPERTY]))
					// alias = ALIAS_HACK_PREFIX + Integer.toString(i);
				}

				if (!alias.contains(".")) {
					// if any of the filters uses this alias as a property
					Iterator<Filter> filters = search.filterIterator();
					while (filters.hasNext()) {
						Filter filter = filters.next();
						if (hasFilterOnProperty(filter, alias)) {
							alias = ALIAS_HACK_PREFIX + alias;
							break;
						}
					}
				}
				projectionList.add(Projections.property(path), alias);
			}
			critMap.get(ROOT_CRIT).setProjection(projectionList);
			if (search.getFetchMode() == Search.FETCH_MAP) {
				critMap.get(ROOT_CRIT).setResultTransformer(
						Transformers.ALIAS_TO_ENTITY_MAP);
			} else if (search.getFetchMode() == Search.FETCH_LIST) {
				critMap.get(ROOT_CRIT).setResultTransformer(
						Transformers.TO_LIST);
			}
		}
	}

	protected boolean hasFilterOnProperty(Filter filter, String prop) {
		if (filter.operator == Filter.OP_AND || filter.operator == Filter.OP_OR) {
			for (Filter f : (List<Filter>) filter.value) {
				if (hasFilterOnProperty(f, prop))
					return true;
			}
			return false;
		} else if (filter.operator == Filter.OP_NOT) {
			return hasFilterOnProperty((Filter) filter.value, prop);
		} else {
			return prop.equals(filter.property);
		}
	}

	protected void addFilters(Map<String, Criteria> critMap, Search search) {
		Junction junction = search.isDisjunction() ? Restrictions.disjunction()
				: Restrictions.conjunction();

		Iterator<Filter> filters = search.filterIterator();
		while (filters.hasNext()) {
			Filter filter = filters.next();
			Criterion criterion = getCriterionFromFilter(critMap, filter);
			if (criterion != null)
				junction.add(criterion);
		}
		critMap.get(ROOT_CRIT).add(junction);
	}

	protected Criterion getCriterionFromFilter(Map<String, Criteria> critMap,
			Filter filter) {
		if (filter == null
				|| filter.property == null
				|| "".equals(filter.property)
				|| (filter.value == null && filter.operator != Filter.OP_EQUAL && filter.operator != Filter.OP_NOT_EQUAL))
			return null;
		String[] parsed = parsePath(filter.property);
		String aliasedPath;
		if ("".equals(parsed[BASE])) {
			aliasedPath = parsed[PROPERTY];
		} else {
			aliasedPath = getCriteria(critMap, parsed[BASE]).getAlias() + "."
					+ parsed[PROPERTY];
		}

		switch (filter.operator) {
		case Filter.OP_IN:
			return Restrictions.in(aliasedPath, (Collection) filter.value);
		case Filter.OP_EQUAL:
			if (filter.value == null) {
				return Restrictions.isNull(aliasedPath);
			} else {
				return Restrictions.eq(aliasedPath, filter.value);
			}
		case Filter.OP_NOT_EQUAL:
			if (filter.value == null) {
				return Restrictions.isNotNull(aliasedPath);
			} else {
				return Restrictions.ne(aliasedPath, filter.value);
			}
		case Filter.OP_GREATER_THAN:
			return Restrictions.gt(aliasedPath, filter.value);
		case Filter.OP_LESS_THAN:
			return Restrictions.lt(aliasedPath, filter.value);
		case Filter.OP_GREATER_OR_EQUAL:
			return Restrictions.ge(aliasedPath, filter.value);
		case Filter.OP_LESS_OR_EQUAL:
			return Restrictions.le(aliasedPath, filter.value);
		case Filter.OP_LIKE:
			return Restrictions.like(aliasedPath, filter.value);
		case Filter.OP_AND:
		case Filter.OP_OR:
			if (!(filter.value instanceof List)) {
				return null;
			}
			Junction junction = filter.operator == Filter.OP_AND ? Restrictions
					.conjunction() : Restrictions.disjunction();
			for (Object o : ((List) filter.value)) {
				if (o instanceof Filter) {
					Criterion criterion = getCriterionFromFilter(critMap,
							(Filter) o);
					if (criterion != null)
						junction.add(criterion);
				}
			}
			return junction;
		case Filter.OP_NOT:
			if (!(filter.value instanceof Filter)) {
				return null;
			}
			Criterion criterion = getCriterionFromFilter(critMap,
					(Filter) filter.value);
			if (criterion == null)
				return null;
			return Restrictions.not(criterion);
		default:
			throw new IllegalArgumentException("Filter comparison ( "
					+ filter.operator + " ) is invalid.");
		}
	}

	/**
	 * <p>
	 * This method is the core of processing nested properties. It takes a path
	 * such as "firstKitten" or "parent.firstKitten" and returns the criteria or
	 * sub criteria based at that path. Once we have that base criteria we can
	 * add any of our specifications to it.
	 * <p>
	 * In order to get a sub criteria the method recursively adds criteria
	 * starting from the root. Once a sub criteria is created it is stored in
	 * the map with a key equal to its path. Subsequent references to the same
	 * path will retrieve the criteria from there.
	 * 
	 * @param path
	 *            This is the path to the base of the criteria. For example if
	 *            we are adding a filter for the property "city.name", we need
	 *            to get the criteria based at "city" and add a restriction for
	 *            "name" to it. So <code>getCriteria(critMap, "city")</code>
	 *            would be used.
	 */
	protected Criteria getCriteria(Map<String, Criteria> map, String path) {
		Criteria crit = map.get(path);
		if (crit != null) {
			return crit;
		} else {
			String[] parsed = parsePath(path);
			crit = getCriteria(map, parsed[BASE]).createCriteria(
					parsed[PROPERTY], generateAlias(path),
					CriteriaSpecification.LEFT_JOIN);
			map.put(path, crit);
			return crit;
		}
	}

	/**
	 * This is a helper function used in processing a Search. Parse a nested
	 * property path into the base path and property. For example passing in
	 * "Cat.firstKitten.name" would return the String array ["Cat.firstKitten",
	 * "name"]. Also passing simply "name" would return ["", "name"].
	 */
	protected String[] parsePath(String path) {
		int pos = path.lastIndexOf('.');
		if (pos == -1) {
			return new String[] { "", path };
		} else {
			return new String[] { path.substring(0, pos),
					path.substring(pos + 1) };
		}
	}

	protected String generateAlias(String path) {
		return "/" + path.replace('.', '/');
	}

	/**
	 * There is an error with hibernate (possibly SQL Dialect specific ?) when
	 * mapping an alias to a property with the same name as the alias. For
	 * example, one cannot map Cat.firstKitten.name to the alias "name". We let
	 * the user specify their own aliases when using
	 * <code>selectMode == SELECT_MAP</code> to determine the map keys. To
	 * keep this from ever happening we replace aliases that would cause this
	 * conflict with unique aliases generated from the
	 * <code>ALIAS_HACK_PREFIX</code> and the index of the select. Then after
	 * generating the result set we use this method to replace those map keys
	 * with the original intended alias.
	 */
	/**
	 * So the problem comes when a fetch alias is the same as a filter property
	 * and that is a property of the base object (ie. the property path has no
	 * "."s)
	 */
	protected void aliasErrorFix(List list, Search search) {
		if (search.getFetchMode() == Search.FETCH_MAP && list.size() > 0) {
			List<String> hackedAliases = new ArrayList<String>();

			// find keys that use the alias hack
			for (Object o : ((Map) list.get(0)).keySet()) {
				String key = (String) o;
				if (key.startsWith(ALIAS_HACK_PREFIX)) {
					hackedAliases.add(key);
				}
			}

			// restore those keys to their proper value
			for (String hackedAlias : hackedAliases) {
				String alias = hackedAlias
						.substring(ALIAS_HACK_PREFIX.length());
				for (Object o : list) {
					Map map = (Map) o;
					Object value = map.get(hackedAlias);
					map.remove(hackedAlias);
					map.put(alias, value);
				}
			}
		}
	}
}