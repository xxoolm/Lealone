/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.dml;

import java.text.Collator;

import org.lealone.common.compress.CompressTool;
import org.lealone.common.compress.Compressor;
import org.lealone.common.exceptions.DbException;
import org.lealone.db.Database;
import org.lealone.db.DbSetting;
import org.lealone.db.Mode;
import org.lealone.db.Setting;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Table;
import org.lealone.db.value.CompareMode;

/**
 * This class represents the statement
 * SET
 * 
 * @author H2 Group
 * @author zhh
 */
// 只处理database级的类型
public class SetDatabase extends SetStatement {

    private final DbSetting setting;

    public SetDatabase(ServerSession session, DbSetting type) {
        super(session);
        this.setting = type;
    }

    @Override
    protected String getSettingName() {
        return setting.name();
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        Database database = session.getDatabase();
        String name = setting.name();
        switch (setting) {
        // 以下是database级的类型
        case ALLOW_LITERALS: {
            int value = getIntValue();
            if (value < 0 || value > 2) {
                throw DbException.getInvalidValueException(name, value);
            }
            database.setAllowLiterals(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case CACHE_SIZE: {
            int value = getAndValidateIntValue();
            database.setCacheSize(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case COLLATION: {
            Table table = database.getFirstUserTable();
            if (table != null) {
                throw DbException.get(ErrorCode.COLLATION_CHANGE_WITH_DATA_TABLE_1, table.getSQL());
            }
            final boolean binaryUnsigned = database.getCompareMode().isBinaryUnsigned();
            CompareMode compareMode;
            StringBuilder buff = new StringBuilder(stringValue);
            if (stringValue.equals(CompareMode.OFF)) {
                compareMode = CompareMode.getInstance(null, 0, binaryUnsigned);
            } else {
                int strength = getIntValue();
                buff.append(" STRENGTH ");
                if (strength == Collator.IDENTICAL) {
                    buff.append("IDENTICAL");
                } else if (strength == Collator.PRIMARY) {
                    buff.append("PRIMARY");
                } else if (strength == Collator.SECONDARY) {
                    buff.append("SECONDARY");
                } else if (strength == Collator.TERTIARY) {
                    buff.append("TERTIARY");
                }
                compareMode = CompareMode.getInstance(stringValue, strength, binaryUnsigned);
            }
            addOrUpdateSetting(name, buff.toString());
            database.setCompareMode(compareMode);
            break;
        }
        case BINARY_COLLATION: {
            Table table = database.getFirstUserTable();
            if (table != null) {
                throw DbException.get(ErrorCode.COLLATION_CHANGE_WITH_DATA_TABLE_1, table.getSQL());
            }
            CompareMode currentMode = database.getCompareMode();
            CompareMode newMode;
            if (stringValue.equals(CompareMode.SIGNED)) {
                newMode = CompareMode.getInstance(currentMode.getName(), currentMode.getStrength(), false);
            } else if (stringValue.equals(CompareMode.UNSIGNED)) {
                newMode = CompareMode.getInstance(currentMode.getName(), currentMode.getStrength(), true);
            } else {
                throw DbException.getInvalidValueException(name, stringValue);
            }
            addOrUpdateSetting(name, stringValue);
            database.setCompareMode(newMode);
            break;
        }
        case COMPRESS_LOB: {
            int algo = CompressTool.getCompressAlgorithm(stringValue);
            database.setLobCompressionAlgorithm(algo == Compressor.NO ? null : stringValue);
            addOrUpdateSetting(name, stringValue);
            break;
        }
        case CREATE_BUILD: {
            if (database.isStarting()) {
                // just ignore the command if not starting
                // this avoids problems when running recovery scripts
                int value = getIntValue();
                addOrUpdateSetting(name, value);
            }
            break;
        }
        case DATABASE_EVENT_LISTENER: {
            database.setEventListenerClass(stringValue);
            break;
        }
        case DB_CLOSE_DELAY: {
            int value = getIntValue();
            if (value == -1) {
                // -1 is a special value for in-memory databases,
                // which means "keep the DB alive and use the same DB for all connections"
            } else if (value < 0) {
                throw DbException.getInvalidValueException(name, value);
            }
            database.setCloseDelay(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case DEFAULT_LOCK_TIMEOUT: {
            addOrUpdateSetting(name, getAndValidateIntValue());
            break;
        }
        case DEFAULT_TABLE_TYPE: {
            int value = getIntValue();
            if (value < 0 || value > 1) {
                throw DbException.getInvalidValueException(name, value);
            }
            database.setDefaultTableType(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case EXCLUSIVE: {
            int value = getIntValue();
            switch (value) {
            case 0:
                database.setExclusiveSession(null, false);
                break;
            case 1:
                database.setExclusiveSession(session, false);
                break;
            case 2:
                database.setExclusiveSession(session, true);
                break;
            default:
                throw DbException.getInvalidValueException(name, value);
            }
            break;
        }
        case IGNORECASE: {
            int value = getIntValue();
            database.setIgnoreCase(value == 1);
            addOrUpdateSetting(name, value);
            break;
        }
        case LOCK_MODE: {
            int value = getIntValue();
            database.setLockMode(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case MAX_LENGTH_INPLACE_LOB: {
            int value = getAndValidateIntValue();
            database.setMaxLengthInplaceLob(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case MAX_LOG_SIZE: {
            int value = getAndValidateIntValue();
            database.setMaxLogSize((long) value * 1024 * 1024);
            addOrUpdateSetting(name, value);
            break;
        }
        case MAX_MEMORY_ROWS: {
            int value = getAndValidateIntValue();
            database.setMaxMemoryRows(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case MAX_MEMORY_UNDO: {
            int value = getAndValidateIntValue();
            database.setMaxMemoryUndo(value);
            addOrUpdateSetting(name, value);
            break;
        }
        case MAX_OPERATION_MEMORY: {
            database.setMaxOperationMemory(getAndValidateIntValue());
            break;
        }
        case MODE: {
            Mode mode = Mode.getInstance(stringValue);
            if (mode == null) {
                throw DbException.get(ErrorCode.UNKNOWN_MODE_1, stringValue);
            }
            if (database.getMode() != mode) {
                database.setMode(mode);
            }
            break;
        }
        case OPTIMIZE_REUSE_RESULTS: {
            database.setOptimizeReuseResults(getAndValidateBooleanValue());
            break;
        }
        case REFERENTIAL_INTEGRITY: {
            database.setReferentialIntegrity(getAndValidateBooleanValue());
            break;
        }
        case QUERY_STATISTICS: {
            database.setQueryStatistics(getAndValidateBooleanValue());
            break;
        }
        case QUERY_STATISTICS_MAX_ENTRIES: {
            database.setQueryStatisticsMaxEntries(getAndValidateIntValue(1));
            break;
        }
        case TRACE_LEVEL_SYSTEM_OUT: {
            if (getCurrentObjectId() == 0) {
                // don't set the property when opening the database
                // this is for compatibility with older versions, because
                // this setting was persistent
                database.getTraceSystem().setLevelSystemOut(getIntValue());
            }
            break;
        }
        case TRACE_LEVEL_FILE: {
            if (getCurrentObjectId() == 0) {
                // don't set the property when opening the database
                // this is for compatibility with older versions, because
                // this setting was persistent
                database.getTraceSystem().setLevelFile(getIntValue());
            }
            break;
        }
        case TRACE_MAX_FILE_SIZE: {
            int value = getAndValidateIntValue();
            int size = value * 1024 * 1024;
            database.getTraceSystem().setMaxFileSize(size);
            addOrUpdateSetting(name, value);
            break;
        }
        case WRITE_DELAY: {
            int value = getAndValidateIntValue();
            database.setWriteDelay(value);
            addOrUpdateSetting(name, value);
            break;
        }
        default:
            if (DbSetting.contains(name)) {
                database.getParameters().put(name, getStringValue());
            } else {
                DbException.throwInternalError("unknown setting type: " + setting);
            }
        }
        databaseChanged(database);
        return 0;
    }

    private void addOrUpdateSetting(String name, String s) {
        addOrUpdateSetting(name, s, 0);
    }

    private void addOrUpdateSetting(String name, int v) {
        addOrUpdateSetting(name, null, v);
    }

    private void addOrUpdateSetting(String name, String s, int v) {
        Database database = session.getDatabase();
        if (database.isReadOnly()) {
            return;
        }
        Setting setting = database.findSetting(name);
        boolean addNew = false;
        if (setting == null) {
            addNew = true;
            int id = getObjectId();
            setting = new Setting(database, id, name);
        }
        if (s != null) {
            if (!addNew && setting.getStringValue().equals(s)) {
                return;
            }
            setting.setStringValue(s);
        } else {
            if (!addNew && setting.getIntValue() == v) {
                return;
            }
            setting.setIntValue(v);
        }
        if (addNew) {
            database.addDatabaseObject(session, setting);
        } else {
            database.updateMeta(session, setting);
        }
    }
}