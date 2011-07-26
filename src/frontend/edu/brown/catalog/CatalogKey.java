package edu.brown.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.*;

import edu.brown.catalog.special.MultiAttributeCatalogType;
import edu.brown.catalog.special.MultiColumn;
import edu.brown.catalog.special.MultiProcParameter;
import edu.brown.catalog.special.ReplicatedColumn;
import edu.brown.catalog.special.VerticalPartitionColumn;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.LoggerUtil.LoggerBoolean;

public abstract class CatalogKey {
    private static final Logger LOG = Logger.getLogger(CatalogKey.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());

    private static final String PARENT_DELIMITER = ".";
    private static final Pattern PARENT_DELIMITER_REGEX = Pattern.compile(Pattern.quote(PARENT_DELIMITER));
    
    private static final String MULTIATTRIBUTE_DELIMITER = "#";
    private static final Pattern MULTIATTRIBUTE_DELIMITER_REGEX = Pattern.compile(Pattern.quote(MULTIATTRIBUTE_DELIMITER));
    
    private static final Map<CatalogType, String> CACHE_CREATEKEY = new HashMap<CatalogType, String>();
    private static final Map<Database, Map<String, CatalogType>> CACHE_GETFROMKEY = new HashMap<Database, Map<String,CatalogType>>();
    private static final Map<String, String> CACHE_NAMEFROMKEY = new HashMap<String, String>();
     
//    public static <T extends CatalogType> String createEscapedKey(T catalog_item) {
//        String key = createKey(catalog_item);
//        return (key.replace("\"", "\\\""));
//    }
    
    /**
     * Returns a String key representation of the column as "Parent.Child"
     * @param catalog_col
     * @return
     */
    public static <T extends CatalogType> String createKey(T catalog_item) {
     // There is a 7x speed-up when we use the cache versus always constructing a new key
        String ret = CACHE_CREATEKEY.get(catalog_item);
        if (ret != null) return (ret);
        if (catalog_item == null) return (null);
        
        JSONStringer stringer = new JSONStringer();
        try {
            createKey(catalog_item, stringer);
            ret = stringer.toString().replace("\"", "'");
            CACHE_CREATEKEY.put(catalog_item, ret);
        } catch (JSONException ex) {
            throw new RuntimeException("Failed to create catalog key for " + catalog_item, ex);
        }
        return (ret);
    }
    
    @SuppressWarnings("unchecked")
    private static <T extends CatalogType> void createKey(T catalog_item, JSONStringer stringer) throws JSONException {
        assert(catalog_item.getParent() != null) : catalog_item + " has null parent";
        
        stringer.object();
        CatalogType parent = catalog_item.getParent();
        String key = null;
        
        // SPECIAL CASE: StmtParameter
        // Since there may be Statement's with the same name but in different Procedures,
        // we also want to include the Procedure name
        if (catalog_item instanceof StmtParameter) {
            assert(parent.getParent() != null);
            key = parent.getParent().getName() + PARENT_DELIMITER + parent.getName();
            
        // SPECIAL CASE: MultiAttributeCatalogType
        } else if (catalog_item instanceof MultiAttributeCatalogType) {
            MultiAttributeCatalogType multicatalog = (MultiAttributeCatalogType)catalog_item;
            key = parent.getName() + MULTIATTRIBUTE_DELIMITER + multicatalog.getPrefix();
        } else {
            key = parent.getName();
        }
        assert(key.isEmpty() == false);
        stringer.key(key);
        
        // SPECIAL CASE: MultiAttributeCatalogType
        if (catalog_item instanceof MultiAttributeCatalogType) {
            MultiAttributeCatalogType multicatalog = (MultiAttributeCatalogType)catalog_item;
            stringer.array();
            Iterator<? extends CatalogType> it = multicatalog.iterator();
            while (it.hasNext()) {
                // We support nested MultiAttribute objects...
                CatalogKey.createKey(it.next(), stringer);
            } // WHILE
            stringer.endArray();
        } else {
            stringer.value(catalog_item.getName());
        }
        
        stringer.endObject();
    }

    /**
     * Returns a String key representation of the column as "Parent.Child"
     * @param parent
     * @param child
     * @return
     */
    public static String createKey(String parent, String child) {
        return (String.format("{\"%s\":\"%s\"}", parent, child));
    }

    public static Collection<String> createKeys(Iterable<? extends CatalogType> map) {
        Collection<String> keys = new ArrayList<String>();
        for (CatalogType catalog_item : map) {
            keys.add(createKey(catalog_item));
        } // FOR
        return (keys);
    }

    /**
     * Return the name of the catalog object from the key
     * @param catalog_key
     * @return
     */
    public static String getNameFromKey(String catalog_key) {
        assert(catalog_key != null);
        String name = CACHE_NAMEFROMKEY.get(catalog_key);
        if (name == null) {
            try {
                JSONObject jsonObject = new JSONObject(catalog_key);
                String key = CollectionUtil.getFirst(jsonObject.keys());
                assert(key != null);
                name = jsonObject.getString(key);
            } catch (Throwable ex) {
                // OLD VERSION
                name = CatalogKeyOldVersion.getNameFromKey(catalog_key);
                if (name == null) {
                    throw new RuntimeException("Failed to retrieve item name from key '" + catalog_key + "'", ex);
                }
            }
            CACHE_NAMEFROMKEY.put(catalog_key, name);
        }
        return (name);
    }

    /**
     * Given a String key generated by createKey(), return the corresponding catalog
     * object for the given Database catalog. If the parent object does not exist, this function will
     * return null. If the parent exists but the child does not exist, then it trips an assert
     * @param catalog_db
     * @param key
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T extends CatalogType> T getFromKey(Database catalog_db, String key, Class<T> catalog_class) {
        if (debug.get()) LOG.debug("Grabbing " + catalog_class + " object for '" + key + "'");
        assert(catalog_db != null);
        assert(catalog_class != null);

        // Caching...
        Map<String, CatalogType> cache = CatalogKey.CACHE_GETFROMKEY.get(catalog_db);
        if (cache != null) {
            if (cache.containsKey(key)) return (T)cache.get(key);
        } else {
            cache = new HashMap<String, CatalogType>();
            CatalogKey.CACHE_GETFROMKEY.put(catalog_db, cache);
        }
        
        T catalog_item = null;
        try {
            JSONObject jsonObject = new JSONObject(key.replace("'", "\""));
            catalog_item = getFromKey(catalog_db, jsonObject, catalog_class);
        } catch (JSONException ex) {
            // OLD VERSION
            catalog_item = CatalogKeyOldVersion.getFromKey(catalog_db, key, catalog_class);
            if (catalog_item == null) {
                throw new RuntimeException("Failed to retrieve " + catalog_class.getSimpleName() + " object for key '" + key + "'", ex);
            }
        }
        cache.put(key, catalog_item);
        return (catalog_item);
    }
    
    @SuppressWarnings("unchecked")
    private static <T extends CatalogType> T getFromKey(Database catalog_db, JSONObject jsonObject, Class<T> catalog_class) throws JSONException {
        T catalog_child = null;
        CatalogType catalog_parent = null;
        
        String parent_key = CollectionUtil.getFirst(jsonObject.keys());
        String orig_parent_key = parent_key;
        String multiattribute_key = null;
        String child_key = jsonObject.getString(parent_key);
        
        // SPECIAL CASE: MultiAttribute
        if (parent_key.contains(MULTIATTRIBUTE_DELIMITER)) {
            String split[] = MULTIATTRIBUTE_DELIMITER_REGEX.split(parent_key);
            assert(split.length == 2);
            parent_key = split[0];
            multiattribute_key = split[1];
        }
        
        List<Class<?>> superclasses = ClassUtil.getSuperClasses(catalog_class);
        
        // Get the parent based on the type of the object they want back
        if (superclasses.contains(Column.class) || catalog_class.equals(Index.class) || catalog_class.equals(Constraint.class) || catalog_class.equals(MaterializedViewInfo.class)) {
            catalog_parent = catalog_db.getTables().get(parent_key);
        } else if (catalog_class.equals(Statement.class) || superclasses.contains(ProcParameter.class)) {
            catalog_parent = catalog_db.getProcedures().get(parent_key);
        } else if (catalog_class.equals(Table.class) || catalog_class.equals(Procedure.class)) {
            catalog_parent = catalog_db;
        } else if (catalog_class.equals(Host.class)) {
            catalog_parent = (Cluster)catalog_db.getParent();
        // SPECIAL CASE: StmtParameter
        } else if (catalog_class.equals(StmtParameter.class)) {
            String split[] = PARENT_DELIMITER_REGEX.split(parent_key);
            assert(split.length == 2);
            Procedure catalog_proc = catalog_db.getProcedures().get(split[0]);
            assert(catalog_proc != null);
            catalog_parent = catalog_proc.getStatements().get(split[1]);
        }
        // Don't throw this error because it may be a dynamic catalog type that we use for the Markov stuff 
        //} else {
        //    assert(false) : "Unexpected Catalog key type '" + catalog_class + "'";
        //}
        
        // It's ok for the parent to be missing, but it's *not* ok if the child is missing
        if (catalog_parent != null) {
            if (debug.get()) {
                LOG.debug("Catalog Parent:     " + CatalogUtil.getDisplayName(catalog_parent));
                LOG.debug("MultiAttribute Key: " + multiattribute_key);
                LOG.debug("Child Key:          " + child_key);
            }
            // COLUMN
            if (superclasses.contains(Column.class)) {
                // SPECIAL CASE: Replicated Column
                if (child_key.equals(ReplicatedColumn.COLUMN_NAME)) {
                    catalog_child = (T)ReplicatedColumn.get((Table)catalog_parent);
                    
                // SPECIAL CASE: MultiColumn
                } else if (multiattribute_key != null && multiattribute_key.equals(MultiColumn.PREFIX)) {
                    JSONArray jsonArray = jsonObject.getJSONArray(orig_parent_key);
                    Column params[] = new Column[jsonArray.length()];
                    for (int i = 0; i < params.length; i++) {
                        params[i] = getFromKey(catalog_db, jsonArray.getJSONObject(i), Column.class); 
                    } // FOR
                    assert(params.length > 0) : "Invalid MultiColumn Key: " + child_key;
                    catalog_child = (T)MultiColumn.get(params);
                    
                // SPECIAL CASE: VerticalPartitionColumn
                } else if (multiattribute_key != null && multiattribute_key.equalsIgnoreCase(VerticalPartitionColumn.PREFIX)) {
                    JSONArray jsonArray = jsonObject.getJSONArray(orig_parent_key);
                    MultiColumn params[] = new MultiColumn[jsonArray.length()];
                    for (int i = 0; i < params.length; i++) {
                        params[i] = getFromKey(catalog_db, jsonArray.getJSONObject(i), MultiColumn.class); 
                    } // FOR
                    assert(params.length == 2) : "Invalid VerticalPartitionColumn Key: " + child_key;
                    catalog_child = (T)VerticalPartitionColumn.get(params[0], params[1]);
                    
                // Regular Columns
                } else {
                    catalog_child = (T)((Table)catalog_parent).getColumns().get(child_key);                    
                }
                
            // INDEX
            } else if (superclasses.contains(Index.class)) {
                catalog_child = (T)((Table)catalog_parent).getIndexes().get(child_key);
                
            // CONSTRAINT
            } else if (superclasses.contains(Constraint.class)) {
                catalog_child = (T)((Table)catalog_parent).getConstraints().get(child_key);
                
            // MATERIALIZEDVIEW
            } else if (superclasses.contains(MaterializedViewInfo.class)) {
                catalog_child = (T)((Table)catalog_parent).getViews().get(child_key);
                
            // PROCPARAMETER
            } else if (superclasses.contains(ProcParameter.class)) {
                
                // SPECIAL CASE: MultiProcParameter
                if (multiattribute_key != null && multiattribute_key.equalsIgnoreCase(MultiProcParameter.PREFIX)) {
                    JSONArray jsonArray = jsonObject.getJSONArray(orig_parent_key);
                    ProcParameter params[] = new ProcParameter[jsonArray.length()];
                    for (int i = 0; i < params.length; i++) {
                        params[i] = getFromKey(catalog_db, jsonArray.getJSONObject(i), ProcParameter.class); 
                    } // FOR
                    assert(params.length > 1) : "Invalid MultiProcParameter Key: " + child_key;
                    catalog_child = (T)MultiProcParameter.get(params);
                    
                // Regular ProcParameter
                } else {
                    catalog_child = (T)((Procedure)catalog_parent).getParameters().get(child_key);
                }
                
            // STATEMENT
            } else if (superclasses.contains(Statement.class)) {
                catalog_child = (T)((Procedure)catalog_parent).getStatements().get(child_key);
                
            // STMTPARAMETER
            } else if (superclasses.contains(StmtParameter.class)) {
                catalog_child = (T)((Statement)catalog_parent).getParameters().get(child_key);
                
            // TABLE
            } else if (superclasses.contains(Table.class)) {
                catalog_child = (T)((Database)catalog_parent).getTables().get(child_key);
                if (catalog_child == null) {
                    LOG.debug("TABLES: " + CatalogUtil.debug(((Database)catalog_parent).getTables()));
                }
                
            // PROCEDURE
            } else if (superclasses.contains(Procedure.class)) {
                catalog_child = (T)((Database)catalog_parent).getProcedures().get(child_key);
                
            // HOST
            } else if (superclasses.contains(Host.class)) {
                catalog_child = (T)((Cluster)catalog_parent).getHosts().get(child_key);
                
            // UNKNOWN!
            } else {
                LOG.fatal("Invalid child class '" + catalog_class + "' for catalog key " + child_key);
                assert (false);
            }
            // if (catalog_child == null) LOG.warn("The child catalog item is null for '" + key + "'");
            assert (catalog_child != null) : "The child catalog item is null for '" + child_key + "'\n" + superclasses;
            return (catalog_child);
        }
        return (null);
    }

    
    public static <T extends CatalogType> Collection<T> getFromKeys(Database catalog_db, Collection<String> keys, Class<T> catalog_class, Collection<T> items) {
        for (String key : keys) {
            items.add(CatalogKey.getFromKey(catalog_db, key, catalog_class));
        } // FOR
        return (items);
    }
    
    public static <T extends CatalogType> Collection<T> getFromKeys(Database catalog_db, Collection<String> keys, Class<T> catalog_class) {
        return (getFromKeys(catalog_db, keys, catalog_class, new ArrayList<T>()));
    }
}
