/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.lealone.db.schema;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.StatementBuilder;
import com.lealone.common.util.StringUtils;
import com.lealone.common.util.Utils;
import com.lealone.db.Constants;
import com.lealone.db.DbObjectType;
import com.lealone.db.SysProperties;
import com.lealone.db.api.ErrorCode;
import com.lealone.db.session.ServerSession;
import com.lealone.db.util.SourceCompiler;
import com.lealone.db.value.DataType;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueNull;
import com.lealone.sql.IExpression;

/**
 * Represents a user-defined function, or alias.
 *
 * @author Thomas Mueller
 * @author Gary Tong
 * @author zhh
 */
public class FunctionAlias extends SchemaObjectBase {

    private String className;
    private String methodName;
    private String source;
    private JavaMethod[] javaMethods;
    private boolean deterministic;
    private boolean bufferResultSetToLocalTemp = true;

    private FunctionAlias(Schema schema, int id, String name) {
        super(schema, id, name);
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.FUNCTION_ALIAS;
    }

    /**
     * Create a new alias based on a method name.
     *
     * @param schema the schema
     * @param id the id
     * @param name the name
     * @param javaClassMethod the class and method name
     * @param force create the object even if the class or method does not exist
     * @param bufferResultSetToLocalTemp whether the result should be buffered
     * @return the database object
     */
    public static FunctionAlias newInstance(Schema schema, int id, String name, String javaClassMethod,
            boolean force, boolean bufferResultSetToLocalTemp) {
        FunctionAlias alias = new FunctionAlias(schema, id, name);
        int paren = javaClassMethod.indexOf('(');
        int lastDot = javaClassMethod.lastIndexOf('.', paren < 0 ? javaClassMethod.length() : paren);
        if (lastDot < 0) {
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1, javaClassMethod);
        }
        alias.className = javaClassMethod.substring(0, lastDot);
        alias.methodName = javaClassMethod.substring(lastDot + 1);
        alias.bufferResultSetToLocalTemp = bufferResultSetToLocalTemp;
        alias.init(force);
        return alias;
    }

    /**
     * Create a new alias based on source code.
     *
     * @param schema the schema
     * @param id the id
     * @param name the name
     * @param source the source code
     * @param force create the object even if the class or method does not exist
     * @param bufferResultSetToLocalTemp whether the result should be buffered
     * @return the database object
     */
    public static FunctionAlias newInstanceFromSource(Schema schema, int id, String name, String source,
            boolean force, boolean bufferResultSetToLocalTemp) {
        FunctionAlias alias = new FunctionAlias(schema, id, name);
        alias.source = source;
        alias.bufferResultSetToLocalTemp = bufferResultSetToLocalTemp;
        alias.init(force);
        return alias;
    }

    private void init(boolean force) {
        try {
            // at least try to compile the class, otherwise the data type is not
            // initialized if it could be
            load();
        } catch (DbException e) {
            if (!force) {
                throw e;
            }
        }
    }

    private synchronized void load() {
        if (javaMethods != null) {
            return;
        }
        if (source != null) {
            loadFromSource();
        } else {
            loadClass();
        }
    }

    private void loadFromSource() {
        SourceCompiler compiler = database.getCompiler();
        synchronized (compiler) {
            String fullClassName = Constants.USER_PACKAGE + "." + getName();
            compiler.setSource(fullClassName, source);
            try {
                Method m = compiler.getMethod(fullClassName);
                JavaMethod method = new JavaMethod(m, 0);
                javaMethods = new JavaMethod[] { method };
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1, e, source);
            }
        }
    }

    private void loadClass() {
        Class<?> javaClass = Utils.loadUserClass(className);
        Method[] methods = javaClass.getMethods();
        ArrayList<JavaMethod> list = new ArrayList<>(1);
        for (int i = 0, len = methods.length; i < len; i++) {
            Method m = methods[i];
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getName().equals(methodName) || getMethodSignature(m).equals(methodName)) {
                JavaMethod javaMethod = new JavaMethod(m, i);
                for (JavaMethod old : list) {
                    if (old.getParameterCount() == javaMethod.getParameterCount()) {
                        throw DbException.get(ErrorCode.METHODS_MUST_HAVE_DIFFERENT_PARAMETER_COUNTS_2,
                                old.toString(), javaMethod.toString());
                    }
                }
                list.add(javaMethod);
            }
        }
        if (list.isEmpty()) {
            throw DbException.get(ErrorCode.PUBLIC_STATIC_JAVA_METHOD_NOT_FOUND_1,
                    methodName + " (" + className + ")");
        }
        javaMethods = new JavaMethod[list.size()];
        list.toArray(javaMethods);
        // Sort elements. Methods with a variable number of arguments must be at
        // the end. Reason: there could be one method without parameters and one
        // with a variable number. The one without parameters needs to be used
        // if no parameters are given.
        Arrays.sort(javaMethods);
    }

    private static String getMethodSignature(Method m) {
        StatementBuilder buff = new StatementBuilder(m.getName());
        buff.append('(');
        for (Class<?> p : m.getParameterTypes()) {
            // do not use a space here, because spaces are removed
            // in CreateFunctionAlias.setJavaClassMethod()
            buff.appendExceptFirst(",");
            if (p.isArray()) {
                buff.append(p.getComponentType().getName()).append("[]");
            } else {
                buff.append(p.getName());
            }
        }
        return buff.append(')').toString();
    }

    @Override
    public String getSQL() {
        if (!getSchema().getName().equals(Constants.SCHEMA_MAIN)) {
            return super.getSQL();
        }
        return database.quoteIdentifier(getName());
    }

    @Override
    public String getCreateSQL() {
        StringBuilder buff = new StringBuilder("CREATE FORCE ALIAS ");
        buff.append(getSQL());
        if (deterministic) {
            buff.append(" DETERMINISTIC");
        }
        if (!bufferResultSetToLocalTemp) {
            buff.append(" NOBUFFER");
        }
        if (source != null) {
            buff.append(" AS ").append(StringUtils.quoteStringSQL(source));
        } else {
            buff.append(" FOR ").append(database.quoteIdentifier(className + "." + methodName));
        }
        return buff.toString();
    }

    @Override
    public String getDropSQL() {
        return "DROP ALIAS IF EXISTS " + getSQL();
    }

    @Override
    public void invalidate() {
        className = null;
        methodName = null;
        javaMethods = null;
        super.invalidate();
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("RENAME");
    }

    /**
     * Find the Java method that matches the arguments.
     *
     * @param args the argument list
     * @return the Java method
     * @throws DbException if no matching method could be found
     */
    public JavaMethod findJavaMethod(IExpression[] args) {
        load();
        int parameterCount = args.length;
        for (JavaMethod m : javaMethods) {
            int count = m.getParameterCount();
            if (count == parameterCount || (m.isVarArgs() && count <= parameterCount + 1)) {
                return m;
            }
        }
        throw DbException.get(ErrorCode.METHOD_NOT_FOUND_1,
                getName() + " (" + className + ", parameter count: " + parameterCount + ")");
    }

    public String getJavaClassName() {
        return this.className;
    }

    public String getJavaMethodName() {
        return this.methodName;
    }

    /**
     * Get the Java methods mapped by this function.
     *
     * @return the Java methods.
     */
    public JavaMethod[] getJavaMethods() {
        load();
        return javaMethods;
    }

    public void setDeterministic(boolean deterministic) {
        this.deterministic = deterministic;
    }

    public boolean isDeterministic() {
        return deterministic;
    }

    public String getSource() {
        return source;
    }

    /**
     * Should the return value ResultSet be buffered in a local temporary file?
     *
     * @return true if yes
     */
    public boolean isBufferResultSetToLocalTemp() {
        return bufferResultSetToLocalTemp;
    }

    /**
     * There may be multiple Java methods that match a function name.
     * Each method must have a different number of parameters however.
     * This helper class represents one such method.
     */
    public static class JavaMethod implements Comparable<JavaMethod> {
        private final int id;
        private final Method method;
        private final int dataType;
        private boolean hasConnectionParam;
        private boolean varArgs;
        private Class<?> varArgClass;
        private int paramCount;

        JavaMethod(Method method, int id) {
            this.method = method;
            this.id = id;
            Class<?>[] paramClasses = method.getParameterTypes();
            paramCount = paramClasses.length;
            if (paramCount > 0) {
                Class<?> paramClass = paramClasses[0];
                if (Connection.class.isAssignableFrom(paramClass)) {
                    hasConnectionParam = true;
                    paramCount--;
                }
            }
            if (paramCount > 0) {
                Class<?> lastArg = paramClasses[paramClasses.length - 1];
                if (lastArg.isArray() && method.isVarArgs()) {
                    varArgs = true;
                    varArgClass = lastArg.getComponentType();
                }
            }
            Class<?> returnClass = method.getReturnType();
            dataType = DataType.getTypeFromClass(returnClass);
        }

        @Override
        public String toString() {
            return method.toString();
        }

        /**
         * Check if this function requires a database connection.
         *
         * @return if the function requires a connection
         */
        public boolean hasConnectionParam() {
            return this.hasConnectionParam;
        }

        /**
         * Call the user-defined function and return the value.
         *
         * @param session the session
         * @param args the argument list
         * @param columnList true if the function should only return the column list
         * @return the value
         */
        public Value getValue(ServerSession session, IExpression[] args, boolean columnList) {
            Class<?>[] paramClasses = method.getParameterTypes();
            Object[] params = new Object[paramClasses.length];
            int p = 0;
            if (hasConnectionParam && params.length > 0) {
                params[p++] = session.createNestedConnection(columnList);
            }

            // allocate array for varArgs parameters
            Object varArg = null;
            if (varArgs) {
                int len = args.length - params.length + 1 + (hasConnectionParam ? 1 : 0);
                varArg = Array.newInstance(varArgClass, len);
                params[params.length - 1] = varArg;
            }

            for (int a = 0, len = args.length; a < len; a++, p++) {
                boolean currentIsVarArg = varArgs && p >= paramClasses.length - 1;
                Class<?> paramClass;
                if (currentIsVarArg) {
                    paramClass = varArgClass;
                } else {
                    paramClass = paramClasses[p];
                }
                int type = DataType.getTypeFromClass(paramClass);
                Value v = args[a].getValue(session);
                Object o;
                if (Value.class.isAssignableFrom(paramClass)) {
                    o = v;
                } else if (v.getType() == Value.ARRAY && paramClass.isArray()
                        && paramClass.getComponentType() != Object.class) {
                    Value[] array = ((ValueArray) v).getList();
                    Object[] objArray = (Object[]) Array.newInstance(paramClass.getComponentType(),
                            array.length);
                    int componentType = DataType.getTypeFromClass(paramClass.getComponentType());
                    for (int i = 0; i < objArray.length; i++) {
                        objArray[i] = array[i].convertTo(componentType).getObject();
                    }
                    o = objArray;
                } else {
                    v = v.convertTo(type);
                    o = v.getObject();
                }
                if (o == null) {
                    if (paramClass.isPrimitive()) {
                        if (columnList) {
                            // if the column list is requested,
                            // the parameters may be null,
                            // otherwise the function can't be called at all
                            o = DataType.getDefaultForPrimitiveType(paramClass);
                        } else {
                            // NULL for a java primitive: return NULL
                            return ValueNull.INSTANCE;
                        }
                    }
                } else {
                    if (!paramClass.isAssignableFrom(o.getClass()) && !paramClass.isPrimitive()) {
                        o = convertTo(session.createNestedConnection(false), v, paramClass);
                    }
                }
                if (currentIsVarArg) {
                    Array.set(varArg, p - params.length + 1, o);
                } else {
                    params[p] = o;
                }
            }
            boolean old = session.isAutoCommit();
            long identity = session.getLastScopeIdentity();
            // boolean defaultConnection = session.getDatabase().getSettings().defaultConnection;
            try {
                session.setAutoCommit(false);
                Object returnValue;
                try {
                    // TODO
                    // if (defaultConnection) {
                    // Driver.setDefaultConnection(session.createConnection(columnList));
                    // }
                    returnValue = method.invoke(null, params);
                    if (returnValue == null) {
                        return ValueNull.INSTANCE;
                    }
                } catch (InvocationTargetException e) {
                    StatementBuilder buff = new StatementBuilder(method.getName());
                    buff.append('(');
                    for (Object o : params) {
                        buff.appendExceptFirst(", ");
                        buff.append(o == null ? "null" : o.toString());
                    }
                    buff.append(')');
                    throw DbException.convertInvocation(e, buff.toString());
                } catch (Exception e) {
                    throw DbException.convert(e);
                }
                if (Value.class.isAssignableFrom(method.getReturnType())) {
                    return (Value) returnValue;
                }
                Value ret = DataType.convertToValue(session, returnValue, dataType);
                return ret.convertTo(dataType);
            } finally {
                session.setLastScopeIdentity(identity);
                session.setAutoCommit(old);
                // TODO
                // if (defaultConnection) {
                // Driver.setDefaultConnection(null);
                // }
            }
        }

        public Class<?>[] getColumnClasses() {
            return method.getParameterTypes();
        }

        public int getDataType() {
            return dataType;
        }

        public int getParameterCount() {
            return paramCount;
        }

        public boolean isVarArgs() {
            return varArgs;
        }

        @Override
        public int compareTo(JavaMethod m) {
            if (varArgs != m.varArgs) {
                return varArgs ? 1 : -1;
            }
            if (paramCount != m.paramCount) {
                return paramCount - m.paramCount;
            }
            if (hasConnectionParam != m.hasConnectionParam) {
                return hasConnectionParam ? 1 : -1;
            }
            return id - m.id;
        }

    }

    /**
     * Convert a value to the specified class.
     *
     * @param conn the database connection
     * @param v the value
     * @param paramClass the target class
     * @return the converted object
     */
    // public static Object convertTo(JdbcConnection conn, Value v, Class<?> paramClass) {
    // if (paramClass == Blob.class) {
    // return new JdbcBlob(conn, v, 0);
    // } else if (paramClass == Clob.class) {
    // return new JdbcClob(conn, v, 0);
    // }
    // if (v.getType() == Value.JAVA_OBJECT) {
    // Object o = SysProperties.SERIALIZE_JAVA_OBJECT ? Utils.deserialize(v.getBytes()) : v.getObject();
    // if (paramClass.isAssignableFrom(o.getClass())) {
    // return o;
    // }
    // }
    // throw DbException.getUnsupportedException(paramClass.getName());
    // }

    public static Object convertTo(Connection conn, Value v, Class<?> paramClass) {
        try {
            if (paramClass == Blob.class) {
                Blob blob;
                blob = conn.createBlob();
                blob.setBytes(0, v.getBytes());
                return blob;
            } else if (paramClass == Clob.class) {
                Clob clob = conn.createClob(); // TODO
                return clob;
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        if (v.getType() == Value.JAVA_OBJECT) {
            Object o = SysProperties.SERIALIZE_JAVA_OBJECT ? Utils.deserialize(v.getBytes())
                    : v.getObject();
            if (paramClass.isAssignableFrom(o.getClass())) {
                return o;
            }
        }
        throw DbException.getUnsupportedException(paramClass.getName());
    }
}
