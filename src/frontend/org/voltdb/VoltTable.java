/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializable;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.types.TimestampType;
import org.voltdb.types.VoltDecimalHelper;

/*
 * The primary representation of a result set (of tuples) or a temporary
 * table in VoltDB. VoltTable has arbitrary schema, is serializable,
 * and is used from within Stored Procedures and from the client library.
 *

A brief overview of the serialized format:

COLUMN HEADER:
[int: column header size in bytes (non-inclusive)]
[short: num columns]
[byte: column type] * num columns
[string: column name] * num columns
TABLE BODY DATA:
[int: num tuples]
[int: row size (non inclusive), blob row data] * num tuples

Strings are represented as:

[int: length-prefix][bytes: stringdata]

where offset is the starting byte in the raw string bytes buffer, and length is the number of bytes
in the string.

This format allows the entire table to be slurped into memory without having to look at it much.

TODO(evanj): In the future, it would be nice to avoid having to copy it in some cases. For
example, most of the data coming from C++ either is consumed immediately by the stored procedure
or is sent immediately to the client. However, it is tough to avoid copying when we don't know for
sure how the results will be used, since the next call to the EE currently reuses the buffer. Also,
the direct ByteBuffers used by the EE are expensive to allocate and free, so we don't want to
rely on the garbage collector.
 */
/**
 * <h3>Summary</h3>
 *
 * <p>The primary representation of a result set (of tuples) or a temporary
 * table in VoltDB. VoltTable has arbitrary schema, is serializable,
 * and is used from within Stored Procedures and from the client library.</p>
 *
 * <h3>Accessing Rows by Index</h3>
 *
 * <p>Given a VoltTable, individual rows can be accessed via the {@link #fetchRow(int)}
 * method. This method returns a {@link VoltTableRow} instance with position set to
 * the specified row. See VoltTableRow for further information on accessing individual
 * column data within a row.</p>
 *
 * <h3>A VoltTable is also a VoltTableRow</h3>
 *
 * <p>Like a {@link VoltTableRow}, a VoltTable has a current position within its rows.
 * This is because VoltTable is a subclass of VoltTableRow. This allows for easy
 * sequential access of data. Example:</p>
 *
 * <code>
 * while (table.advanceRow()) {<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;System.out.println(table.getLong(7));<br/>
 * }
 * </code>
 *
 * <h3>Building a Table Dynamically</h3>
 *
 * <p>VoltTables can be constructed on the fly. This can help generate cleaner
 * result sets from stored procedures, or more manageable parameters to them.
 * Example:</p>
 *
 * <code>
 * VoltTable t = new VoltTable(<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;new VoltTable.ColumnInfo("col1", VoltType.BIGINT),<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;new VoltTable.ColumnInfo("col2", VoltType.STRING));<br/>
 * t.addRow(15, "sampleString");<br/>
 * t.addRow(-9, "moreData");
 * </code>
 */
public class VoltTable extends VoltTableRow implements FastSerializable {

    /**
     * Size in bytes of the maximum length for a VoltDB tuple.
     * This value is counted from byte 0 of the header size to the end of row data.
     */
    public static final int MAX_SERIALIZED_TABLE_LENGTH = 10 * 1024 * 1024;
    public static final String MAX_SERIALIZED_TABLE_LENGTH_STR =
        String.valueOf(MAX_SERIALIZED_TABLE_LENGTH / 1024) + "k";

    static final int NULL_STRING_INDICATOR = -1;
    static final String METADATA_ENCODING = "US-ASCII";
    static final String ROWDATA_ENCODING = "UTF-8";

    static final AtomicInteger expandCountDouble = new AtomicInteger(0);

    boolean m_readOnly = false;
    int m_rowStart = -1; // the beginning of the row data (points to before the row count int)
    int m_rowCount = -1;
    int m_colCount = -1;

    /**
     * <p>Object that represents the name and schema for a {@link VoltTable} column.
     * Primarily used to construct in the constructor {@link VoltTable#VoltTable(ColumnInfo...)}
     * and {@link VoltTable#VoltTable(ColumnInfo[], int)}.</p>
     *
     * <p>Example:<br/>
     * <tt>VoltTable t = new VoltTable(<br/>
     * &nbsp;&nbsp;&nbsp;&nbsp;new ColumnInfo("foo", VoltType.INTEGER),
     * new ColumnInfo("bar", VoltType.STRING));</tt>
     * </p>
     *
     * <p>Note: VoltDB current supports ASCII encoded column names only. Column values are
     * still UTF-8 encoded.</p>
     */
    public static final class ColumnInfo {

        /**
         * Construct and immutable <tt>ColumnInfo</tt> instance.
         *
         * @param name The name of the column (ASCII).
         * @param type The type of the column. Note that not all types are
         * supported (such as {@link VoltType#INVALID} or {@link VoltType#NUMERIC}.
         */
        public ColumnInfo(String name, VoltType type) {
            this.name = name; this.type = type;
        }

        // immutable actual data
        final String name;
        final VoltType type;
        
        public String getName() {
            return (this.name);
        }
        public VoltType getType() {
            return (this.type);
        }
    }

    /**
     * Do nothing constructor that does no initialization or allocation.
     */
    VoltTable() {}

    /**
     * Clone the schema of a table
     * @param clone
     */
    public VoltTable(VoltTable clone) {
        ColumnInfo cols[] = new ColumnInfo[clone.getColumnCount()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = new ColumnInfo(clone.getColumnName(i), clone.getColumnType(i));
        }
        initializeFromColumns(cols, cols.length);
    }

    /**
     * Create a table from an existing backing buffer.
     *
     * @param backing The buffer containing the serialized table.
     * @param readOnly Can this table be changed?
     */
    public VoltTable(ByteBuffer backing, boolean readOnly) {
        m_buffer = backing;

        // rowstart represents and offset to the start of row data,
        //  but the serialization is the non-inclusive length of the header,
        //  so add 4 bytes.
        m_rowStart = m_buffer.getInt(0) + 4;

        m_colCount = m_buffer.getShort(5);
        m_rowCount = m_buffer.getInt(m_rowStart);
        m_buffer.position(m_buffer.limit());
        m_readOnly = readOnly;

        assert(verifyTableInvariants());
    }

    /**
     * Create an empty table from column schema. While {@link #VoltTable(ColumnInfo...)}
     * is the preferred constructor, this version may reduce the need for an array
     * allocation by allowing the caller to specify only a portion of the given array
     * should be used.
     *
     * @param columns An array of ColumnInfo objects, one per column
     * in the desired order.
     * @param columnCount The number of columns in the array to use.
     */
    public VoltTable(ColumnInfo[] columns, int columnCount) {
        initializeFromColumns(columns, columnCount);
    }

    /**
     * Create an empty table from column schema given as an array.
     *
     * @param columns An array of ColumnInfo objects, one per column
     * in the desired order.
     */
    public VoltTable(ColumnInfo[] columns) {
        initializeFromColumns(columns, columns.length);
    }

    /**
     * Create an empty table from column schema.
     * Note that while this accepts a varargs set of columns,
     * it requires at least one column to prevent user errors.
     *
     * @param firstColumn The first column of the table.
     * @param columns An array of ColumnInfo objects, one per column
     * in the desired order (can be empty).
     */
    public VoltTable(ColumnInfo firstColumn, ColumnInfo... columns) {
        int allLen = 1 + columns.length;
        ColumnInfo[] allColumns = new ColumnInfo[allLen];
        allColumns[0] = firstColumn;
        for (int i = 0; i < columns.length; i++) {
            allColumns[i+1] = columns[i];
        }
        initializeFromColumns(allColumns, allLen);
    }

    private void initializeFromColumns(ColumnInfo[] columns, int columnCount) {
        // allocate a 1K table backing for starters
        int allocationSize = 1024;
        m_buffer = ByteBuffer.allocate(allocationSize);

        // while not successful at initializing,
        //  use a bigger and bigger backing
        boolean success = false;
        while (!success) {
            try {
                // inside the try block, do initialization

                m_colCount = columnCount;
                m_rowCount = 0;

                // do some trivial checks to make sure the schema is not totally wrong
                if (columns == null) {
                    throw new RuntimeException("VoltTable(..) constructor passed null schema.");
                }
                if (columnCount <= 0) {
                    throw new RuntimeException("VoltTable(..) constructor requires at least one column.");
                }
                if (columns.length < columnCount) {
                    throw new RuntimeException("VoltTable(..) constructor passed truncated column schema array.");
                }

                // put a dummy value in for header size for now
                m_buffer.putInt(0);

                //Put in 0 for the status code
                m_buffer.put(Byte.MIN_VALUE);

                m_buffer.putShort((short) columnCount);

                for (int i = 0; i < columnCount; i++)
                    m_buffer.put(columns[i].type.getValue());
                
                for (int i = 0; i < columnCount; i++) {
                    if (columns[i].name == null) {
                        m_buffer.position(0);
                        throw new IllegalArgumentException("VoltTable column names can not be null.");
                    }
                    if (columns[i].name.isEmpty()) { // FAST HACK
                        m_buffer.putInt(0);   
                    } else {
                        writeStringToBuffer(columns[i].name, METADATA_ENCODING, m_buffer);
                    }
                }
                // write the header size to the first 4 bytes (length-prefixed non-inclusive)
                m_rowStart = m_buffer.position();
                m_buffer.putInt(0, m_rowStart - 4);
                // write the row count to the next 4 bytes after the header
                m_buffer.putInt(0);
                m_buffer.limit(m_buffer.position());

                success = true;
            }
            catch (BufferOverflowException e) {
                // if too small buffer, grow
                allocationSize *= 4;
                m_buffer = ByteBuffer.allocate(allocationSize);
            }
        }
        assert(verifyTableInvariants());
    }

    /**
     * End users should not call this method.
     * Obtain a reference to the table's underlying buffer.
     * The returned reference's position and mark are independent of
     * the table's buffer position and mark. The returned buffer has
     * no mark and is at position 0.
     */
    public ByteBuffer getTableDataReference() {
        ByteBuffer buf = m_buffer.duplicate();
        buf.rewind();
        return buf;
    }
    
    public ByteBuffer getDirectDataReference() {
        return (m_buffer);
    }

    /**
     * Delete all row data. Column data is preserved.
     * Useful for reusing an <tt>VoltTable</tt>.
     */
    public final void clearRowData() {
        assert(verifyTableInvariants());
        m_buffer.position(m_rowStart);
        m_buffer.putInt(0);
        m_rowCount = 0;
        assert(verifyTableInvariants());
    }

    /**
     * Get a new {@link VoltTableRow} instance with identical position as this table.
     * After the cloning, the new instance and this table can be advanced or reset
     * independently.
     * @return A new {@link VoltTableRow} instance with the same position as this table.
     */
    @Override
    public VoltTableRow cloneRow() {
        Row retval = new Row(m_position);
        retval.m_hasCalculatedOffsets = m_hasCalculatedOffsets;
        retval.m_wasNull = m_wasNull;
        if (m_offsets != null)
            for (int i = 0; i < m_colCount; i++)
                retval.m_offsets[i] = m_offsets[i];
        retval.m_activeRowIndex = m_activeRowIndex;
        return retval;
    }

    /**
     * Representation of a row in an <tt>VoltTable</tt>. Immutable. Values returned by the various
     * getters are SQL <tt>null</tt> (as opposed to Java <tt>null</tt>) when they are Java null <code>((x == null) == true)</code>
     * or {@link #wasNull()} returns <tt>true</tt> when called immediately after the getter that retrieved the value.
     * @see #wasNull()
     */
    final class Row extends VoltTableRow {
        /**
         * A Row instance has no real data, but sits on top of a specific position
         * in the buffer data, interpreting that data into a table row.
         *
         * @param position Offset into the buffer of the start of this row's data.
         * @param offsets Pass in an array sized for the number of columns. This
         * avoids allocating a new object for every row. The reason it can't just
         * use m_schemaOffsets in the RowIterator is that the fetchRow(..) call
         * can create a row without an iterator.
         */
        Row(int position) {
            m_buffer = VoltTable.this.m_buffer;
            assert (position < m_buffer.limit());
            m_position = position;
            m_offsets = new int[m_colCount];
            m_activeRowIndex = -1;
        }

        @Override
        protected int getColumnCount() {
            return VoltTable.this.getColumnCount();
        }

        @Override
        protected int getColumnIndex(String columnName) {
            return VoltTable.this.getColumnIndex(columnName);
        }
        
        @Override
        protected boolean hasColumn(String columnName) {
            return VoltTable.this.hasColumn(columnName);
        }

        @Override
        protected VoltType getColumnType(int columnIndex) {
            return VoltTable.this.getColumnType(columnIndex);
        }

        @Override
        protected int getRowCount() {
            return VoltTable.this.getRowCount();
        }

        @Override
        protected int getRowStart() {
            return VoltTable.this.getRowStart();
        }
        
        @Override
        public int getRowSize() {
            return VoltTable.this.getRowSize();
        }
        
        @Override
        public VoltTableRow cloneRow() {
            Row retval = new Row(m_position);
            retval.m_hasCalculatedOffsets = m_hasCalculatedOffsets;
            retval.m_wasNull = m_wasNull;
            for (int i = 0; i < m_colCount; i++)
                retval.m_offsets[i] = m_offsets[i];
            retval.m_activeRowIndex = m_activeRowIndex;
            return retval;
        }
    }

    /**
     * Return the name of the column with the specified index.
     * @param index Index of the column
     * @return Name of the column with the specified index.
     */
    public String getColumnName(int index) {
        assert(verifyTableInvariants());
        if ((index < 0) || (index >= m_colCount))
            throw new IllegalArgumentException("Not a valid column index.");

        // move to the start of the list of column names
        // (this could be done faster by just reading the string lengths
        //  and skipping ahead)
        int pos = 4 + 1 + 2 + m_colCount;//headerLength + status code + column count + (m_colCount * colTypeByte)
        String name = null;
        for (int i = 0; i < index; i++)
            pos += m_buffer.getInt(pos) + 4;
        name = readString(pos, METADATA_ENCODING);
        assert(name != null);

        assert(verifyTableInvariants());
        return name;
    }
    
    public final String[] getColumnNames() {
        String col_names[] = new String[m_colCount];
        for (int i = 0; i < m_colCount; i++) {
            col_names[i] = this.getColumnName(i);
        }
        return (col_names);
    }

    @Override
    public VoltType getColumnType(int index) {
        assert(verifyTableInvariants());
        assert(index < m_colCount);
        // move to the right place
        VoltType retval = VoltType.get(m_buffer.get(4 + 1 + 2 + index));//headerLength + status code + column count
        assert(verifyTableInvariants());
        return retval;
    }

    @Override
    public int getColumnIndex(String name) {
        assert(verifyTableInvariants());
        for (int i = 0; i < m_colCount; i++) {
            if (getColumnName(i).equalsIgnoreCase(name))
                return i;
        }
        String msg = "No Column named '" + name + "'. Existing columns are:";
        for (int i = 0; i < m_colCount; i++) {
            msg += "[" + i + "]" + getColumnName(i) + ",";
        }
        throw new IllegalArgumentException(msg);
    }
    
    @Override
    public boolean hasColumn(String name) {
        assert(verifyTableInvariants());
        for (int i = 0; i < m_colCount; i++) {
            if (getColumnName(i).equalsIgnoreCase(name))
                return (true);
        }
        return false;
    }

    /**
     * Return a {@link VoltTableRow} instance with the specified index.
     * @param index Index of the row
     * @return The requested {@link VoltTableRow Row}.
     * @throws IndexOutOfBoundsException if no row exists at the given index.
     */
    public VoltTableRow fetchRow(int index) {
        assert(verifyTableInvariants());
        if ((index < 0) || (index >= m_rowCount)) {
            throw new IndexOutOfBoundsException("index = " + index + "; rows = " + m_rowCount);
        }

        int pos = m_rowStart + 4;
        for (int i = 0; i < index; i++) {
            // add 4 bytes as the row size is non-inclusive
            pos += m_buffer.getInt(pos) + 4;
        }
        Row retval = new Row(pos + 4);
        retval.m_activeRowIndex = index;
        return retval;
    }
    
    /**
     * Returns the active row
     * @return
     */
    public VoltTableRow getRow() {
        int idx = this.getActiveRowIndex();
        return (this.fetchRow(idx));
    }

    /**
     * Returns the active row as a read-only array
     * @return
     */
    public Object[] getRowArray() {
        VoltTableRow row = this.getRow();
        Object arr[] = new Object[row.getColumnCount()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = row.get(i);
        } // FOR
        return (arr);
    }
    
    /**
     * Returns the active row as a read-only string array
     * @return
     */
    public String[] getRowStringArray() {
        VoltTableRow row = this.getRow();
        String arr[] = new String[row.getColumnCount()];
        for (int i = 0; i < arr.length; i++) {
            Object v = row.get(i);
            if (v != null) arr[i] = v.toString();
        } // FOR
        return (arr);
    }
    

    /**
     * Append a {@link VoltTableRow row} from another <tt>VoltTable</tt>
     * to this VoltTable instance. Technically, it could be from the same
     * table, but this isn't the common usage.
     * @param row {@link VoltTableRow Row} to add.
     */
    public final void add(VoltTableRow row) {
        assert(verifyTableInvariants());
        final Object[] values = new Object[m_colCount];
        for (int i = 0; i < m_colCount; i++) {
            try {
                values[i] = row.get(i); // , getColumnType(i));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        addRow(values);
    }

    /**
     * Append a new row to the table using the supplied column values.
     * @param values Values of each column in the row.
     * @throws VoltTypeException  when there are casting/type failures
     *         between an input value and the corresponding column
     */
    public final void addRow(Object... values) {
        if (m_readOnly) {
            throw new IllegalStateException("Table is read-only. Make a copy before changing.");
        }
        assert(verifyTableInvariants());
        if (m_colCount == 0) {
            throw new IllegalStateException("table has no columns defined");
        }
        if (values.length != m_colCount) {
            throw new IllegalArgumentException(values.length + " arguments but table has " + m_colCount + " columns");
        }

        // memoize the start of this row in case we roll back
        final int pos = m_buffer.position();

        try {
            // Allow the buffer to grow to max capacity
            m_buffer.limit(m_buffer.capacity());
            // advance the row size value
            m_buffer.position(pos + 4);

            // where does the type bytes start
            // skip rowstart + status code + colcount
            int typePos = 4 + 1 + 2;

            for (int col = 0; col < m_colCount; col++) {
                Object value = values[col];
                VoltType columnType = VoltType.get(m_buffer.get(typePos + col));

                try
                {
                    if (VoltType.isNullVoltType(value))
                    {
                        switch (columnType) {
                        case BOOLEAN:
                        case TINYINT:
                            m_buffer.put(VoltType.NULL_TINYINT);
                            break;
                        case SMALLINT:
                            m_buffer.putShort(VoltType.NULL_SMALLINT);
                            break;
                        case INTEGER:
                            m_buffer.putInt(VoltType.NULL_INTEGER);
                            break;
                        case TIMESTAMP:
                            m_buffer.putLong(VoltType.NULL_BIGINT);
                            break;
                        case BIGINT:
                            m_buffer.putLong(VoltType.NULL_BIGINT);
                            break;
                        case FLOAT:
                            m_buffer.putDouble(VoltType.NULL_FLOAT);
                            break;
                        case STRING:
                            m_buffer.putInt(NULL_STRING_INDICATOR);
                            break;
                        case DECIMAL:
                            VoltDecimalHelper.serializeNull(m_buffer);
                            break;

                        default:
                            throw new VoltTypeException("Unsupported type: " +
                                                        columnType);
                        }
                    } else {

                        // Allow implicit conversions across all numeric types
                        // except BigDecimal and anything else. Require BigDecimal
                        // and reject Long128. Convert byte[] to VoltType.STRING.
                        // Allow longs to be converted to VoltType.TIMESTAMPS.

                        // In all error paths, catch ClassCastException
                        // and VoltTypeException to restore
                        // the correct table state.
                        // XXX consider adding a fast path that checks for
                        // equivalent types of input and column

                        switch (columnType) {
                        case BOOLEAN:
                            byte val = (((Boolean)value).booleanValue() ? (byte)1 : (byte)0);
                            m_buffer.put(val);
                            break;
                        case TINYINT:
                            if (value instanceof BigDecimal)
                                throw new ClassCastException();
                            final Number n1 = (Number) value;
                            if (columnType.wouldCastOverflow(n1))
                            {
                                throw new VoltTypeException("Cast of " +
                                                            n1.doubleValue() +
                                                            " to " +
                                                            columnType.toString() +
                                                            " would overflow");
                            }
                            m_buffer.put(n1.byteValue());
                            break;
                        case SMALLINT:
                            if (value instanceof BigDecimal)
                                throw new ClassCastException();
                            final Number n2 = (Number) value;
                            if (columnType.wouldCastOverflow(n2))
                            {
                                throw new VoltTypeException("Cast to " +
                                                            columnType.toString() +
                                                            " would overflow");
                            }
                            m_buffer.putShort(n2.shortValue());
                            break;
                        case INTEGER:
                            if (value instanceof BigDecimal)
                                throw new ClassCastException();
                            final Number n3 = (Number) value;
                            if (columnType.wouldCastOverflow(n3))
                            {
                                throw new VoltTypeException("Cast to " +
                                                            columnType.toString() +
                                                            " would overflow");
                            }
                            m_buffer.putInt(n3.intValue());
                            break;
                        case BIGINT:
                            if (value instanceof BigDecimal)
                                throw new ClassCastException();
                            final Number n4 = (Number) value;
                            if (columnType.wouldCastOverflow(n4))
                            {
                                throw new VoltTypeException("Cast to " +
                                                            columnType.toString() +
                                                            " would overflow");
                            }
                            m_buffer.putLong(n4.longValue());
                            break;

                        case FLOAT:
                            if (value instanceof BigDecimal)
                                throw new ClassCastException();
                            final Number n5 = (Number) value;
                            if (columnType.wouldCastOverflow(n5))
                            {
                                throw new VoltTypeException("Cast to " +
                                                            columnType.toString() +
                                                            " would overflow");
                            }
                            m_buffer.putDouble(n5.doubleValue());
                            break;

                        case STRING: {
                            // Accept byte[] and String
                            if (value instanceof byte[]){
                                if (((byte[]) value).length > VoltType.MAX_VALUE_LENGTH)
                                    throw new VoltOverflowException(
                                            "Value in VoltTable.addRow(...) larger than allowed max " + VoltType.MAX_VALUE_LENGTH_STR);

                                // bytes MUST be a UTF-8 encoded string.
                                assert(testForUTF8Encoding((byte[]) value));
                                writeStringToBuffer((byte[]) value, m_buffer);
                            }
                            else {
                                if (((String) value).length() > VoltType.MAX_VALUE_LENGTH)
                                    throw new VoltOverflowException(
                                            "Value in VoltTable.addRow(...) larger than allowed max " + VoltType.MAX_VALUE_LENGTH_STR);

                                writeStringToBuffer((String) value, ROWDATA_ENCODING, m_buffer);
                            }
                            break;
                        }

                        case TIMESTAMP: {
                            // Accept long and TimestampType
                            if (value instanceof TimestampType) {
                                m_buffer.putLong(((TimestampType)value).getTime());
                            }
                            else if (value instanceof BigDecimal) {
                                throw new ClassCastException();
                            }
                            else {
                                m_buffer.putLong(((Number) value).longValue());
                            }
                            break;
                        }

                        case DECIMAL: {
                            // Only accept BigDecimal; rely on class cast exception for error path
                            VoltDecimalHelper.serializeBigDecimal( (BigDecimal)value, m_buffer);
                            break;
                        }

                        default:
                            throw new VoltTypeException("Unsupported type: " + columnType);
                        }
                    }
                }
                catch (VoltTypeException vte)
                {
                    // revert the row size advance and any other
                    // buffer additions
                    m_buffer.position(pos);
                    throw vte;
                }
                catch (ClassCastException cce) {
                    cce.printStackTrace();
                    // revert any added tuples and strings
                    m_buffer.position(pos);
                    throw new VoltTypeException("Value for column " + col + " (" +
                                                getColumnName(col) + ") is type " +
                                                value.getClass().getSimpleName() + " when type " + columnType +
                    " was expected.");
                }
            }

            m_rowCount++;
            m_buffer.putInt(m_rowStart, m_rowCount);
            final int rowsize = m_buffer.position() - pos - 4;

            // check for too big rows
            if (rowsize > VoltTableRow.MAX_TUPLE_LENGTH) {
                throw new VoltOverflowException(
                        "Table row total length larger than allowed max " + VoltTableRow.MAX_TUPLE_LENGTH_STR);
            }

            // constrain buffer limit back to the new position
            m_buffer.limit(m_buffer.position());
            assert(rowsize >= 0);
            // buffer overflow is caught and handled below.
            m_buffer.putInt(pos, rowsize);
        }
        catch (BufferOverflowException e) {
            m_buffer.position(pos);
            expandBuffer();
            addRow(values);
        }
        catch (IllegalArgumentException e) {
            // if this was thrown because of a lack of space
            // then grow the buffer
            // the number 32 was picked out of a hat ( maybe a bug if str > 32 )
            if (m_buffer.limit() - m_buffer.position() < 64) {
                m_buffer.position(pos);
                expandBuffer();
                addRow(values);
            }
            else throw e;
        }

        assert(verifyTableInvariants());
    }

    private final void expandBuffer() {
        final int end = m_buffer.position();
        assert(end > m_rowStart);
        final ByteBuffer buf2 = ByteBuffer.allocate(m_buffer.capacity() * 2);
        m_buffer.limit(end);
        m_buffer.position(0);
        buf2.put(m_buffer);
        m_buffer = buf2;
    }

    /**
     * Tables containing a single row and a single integer column can be read using this convenience
     * method.
     * @return The integer row value.
     */
    public long asScalarLong() {
        verifyTableInvariants();

        if (m_rowCount != 1) {
            throw new IllegalStateException(
                    "table must contain exactly 1 tuple; tuples = " + m_rowCount);
        }
        if (m_colCount != 1) {
            throw new IllegalStateException(
                    "table must contain exactly 1 column; columns = " + Arrays.toString(this.getColumnNames()));
        }
        final VoltType colType = getColumnType(0);
        switch (colType) {
        case TINYINT:
            return m_buffer.get(m_rowStart + 8);
        case SMALLINT:
            return m_buffer.getShort(m_rowStart + 8);
        case INTEGER:
            return m_buffer.getInt(m_rowStart + 8);
        case BIGINT:
            return m_buffer.getLong(m_rowStart + 8);
        default:
            throw new IllegalStateException(
                    "table must contain exactly 1 integral value; column 1 is type = " + colType.name());
        }
    }

    /**
     * End users should not call this method.
     * Read an VoltTable from a {@link org.voltdb.messaging.FastDeserializer} into this object.
     */
    @Override
    public final void readExternal(FastDeserializer in) throws IOException {
        // Note: some of the snapshot and save/restore code makes assumptions
        // about the binary layout of tables.

        final int len = in.readInt();
        // smallest table is 4-bytes with zero value
        // indicating rowcount is 0
        assert(len >= 4);
        m_buffer = in.readBuffer(len);
        m_buffer.position(m_buffer.limit());

        // rowstart represents and offset to the start of row data,
        //  but the serialization is the non-inclusive length of the header,
        //  so add two bytes.
        m_rowStart = m_buffer.getInt(0) + 4;

        m_colCount = m_buffer.getShort(5);
        m_rowCount = m_buffer.getInt(m_rowStart);

        assert(verifyTableInvariants());
    }

    /**
     * End users should not call this method.
     * Write this VoltTable to a {@link org.voltdb.messaging.FastSerializer}.
     */
    @Override
    public void writeExternal(FastSerializer out) throws IOException {
        // Note: some of the snapshot and save/restore code makes assumptions
        // about the binary layout of tables.

        assert(verifyTableInvariants());
        final ByteBuffer buffer = m_buffer.duplicate();
        final int pos = buffer.position();
        buffer.position(0);
        buffer.limit(pos);
        out.writeInt(pos);
        out.write(buffer);
        assert(verifyTableInvariants());
    }

    /**
     * Returns a {@link java.lang.String String} representation of this table.
     * Resulting string will contain schema and all data and will be formatted.
     * @return a {@link java.lang.String String} representation of this table.
     */
    @Override
    public String toString() {
        return this.toString(true);
    }
    
    public String toString(boolean includeData) {
        assert(verifyTableInvariants());
        StringBuffer buffer = new StringBuffer();

        // commented out code to print byte by byte content
//        for (int i = 0; i < m_buffer.limit(); i++) {
//            byte b = m_buffer.get(i);
//            char c = (char) b;
//            if (Character.isLetterOrDigit(c))
//                buffer.append(c);
//            else
//                buffer.append("[").append(b).append("]");
//            buffer.append(" ");
//        }
//        buffer.append("\n");

//        buffer.append(" header size: ").append(m_buffer.getInt(0)).append("\n");

//        byte statusCode = m_buffer.get(4);
//        buffer.append(" status code: ").append(statusCode);

//        buffer.append(" bytes: ")
//              .append(m_buffer.limit())
//              .append(" / ")
//              .append(m_buffer.array().length)
//              .append("\n");
        
        short colCount = m_buffer.getShort(5);
//        buffer.append(" column count: ").append(colCount).append("\n");
        assert(colCount == m_colCount);
        buffer.append(String.format(" cols[%d] ", colCount));
        for (int i = 0; i < colCount; i++)
            buffer.append("(").append(getColumnName(i)).append(":").append(getColumnType(i).name()).append("), ");
        buffer.append("\n");

        buffer.append(" row count: ").append(getRowCount()).append("\n");
        if (includeData) {
            buffer.append(" rows -\n");
    
            VoltTableRow r = cloneRow();
            r.resetRowPosition();
            while (r.advanceRow()) {
                buffer.append("  ");
                for (int i = 0; i < m_colCount; i++) {
                    switch(getColumnType(i)) {
                    case TINYINT:
                    case SMALLINT:
                    case INTEGER:
                    case BIGINT:
                        long lval = r.getLong(i);
                        if (r.wasNull())
                            buffer.append("NULL, ");
                        else
                            buffer.append(lval + ", ");
                        break;
                    case FLOAT:
                        double dval = r.getDouble(i);
                        if (r.wasNull())
                            buffer.append("NULL, ");
                        else
                            buffer.append(dval + ", ");
                        break;
                    case TIMESTAMP:
                        TimestampType tstamp = r.getTimestampAsTimestamp(i);
                        if (r.wasNull()) {
                            buffer.append("NULL, ");
                            assert (tstamp == null);
                        } else {
                            buffer.append(tstamp + ", ");
                        }
                        break;
                    case STRING:
                        String string = r.getString(i);
                        if (r.wasNull()) {
                            buffer.append("NULL, ");
                            assert (string == null);
                        } else {
                            buffer.append(string + ", ");
                        }
                        break;
                    case DECIMAL:
                        BigDecimal bd = r.getDecimalAsBigDecimal(i);
                        if (r.wasNull()) {
                            buffer.append("NULL, ");
                            assert (bd == null);
                        } else {
                            buffer.append(bd.toString() + ", ");
                        }
                        break;
                    }
                }
                buffer.append("\n");
//                buffer.append("Final Position: ").append(m_buffer.position()).append("\n");
//                if (m_buffer.hasRemaining())
//                    buffer.append("WARN: There are still " + m_buffer.remaining() + " bytes left!\n");
            }
        } else {
            buffer.append(" rows - " + this.getRowCount() + "\n");
        }

        assert(verifyTableInvariants());
        return buffer.toString();
    }

    /**
     * Check to see if this table has the same contents as the provided table.
     * This is not {@link java.lang.Object#equals(Object)} because we don't
     * want to provide all of the additional contractual requirements that go
     * along with it, such as implementing {@link java.lang.Object#hashCode()}.
     */
    public boolean hasSameContents(VoltTable other) {
        assert(verifyTableInvariants());
        if (this == other) return true;

        int mypos = m_buffer.position();
        m_buffer.position(0);
        m_buffer.limit(mypos);
        int theirpos = other.m_buffer.position();
        other.m_buffer.limit(theirpos);
        other.m_buffer.position(0);

        boolean val = m_buffer.equals(other.m_buffer);

        m_buffer.limit(m_buffer.capacity());
        m_buffer.position(mypos);
        other.m_buffer.limit(other.m_buffer.capacity());
        other.m_buffer.position(theirpos);

        assert(verifyTableInvariants());
        return val;
    }

    /**
     *  An unreliable version of {@link java.lang.Object#equals(Object)} that should not be used. Only
     *  present for unit testing.
     *  @deprecated
     */
    @Deprecated
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VoltTable)) return false;
        return hasSameContents((VoltTable) o);
    }

    /**
     * Also overrides {@link java.lang.Object#hashCode()}  since we are overriding {@link java.lang.Object#equals(Object)}.
     * Throws an {@link java.lang.UnsupportedOperationException}.
     * @deprecated
     */
    @Deprecated
    @Override
    public int hashCode() {
        // TODO(evanj): When overriding equals, we should also override hashCode. I don't want to
        // implement this right now, since VoltTables should never be used as hash keys anyway.
        // throw new UnsupportedOperationException("unimplemented");
        return (0);
    }

    /**
     * Generates a duplicate of a table including the column schema. Only works
     * on tables that have no rows, have columns defined, and will not have columns added/deleted/modified
     * later. Useful as way of creating template tables that can be cloned and then populated with
     * {@link VoltTableRow rows} repeatedly.
     * @return An <tt>VoltTable</tt> with the same column schema as the original and enough space
     *         for the specified number of {@link VoltTableRow rows} and strings.
     */
    public final VoltTable clone(int extraBytes) {
        assert(verifyTableInvariants());
        final VoltTable cloned = new VoltTable();
        cloned.m_colCount = m_colCount;
        cloned.m_rowCount = 0;
        cloned.m_rowStart = m_rowStart;

        final int pos = m_buffer.position();
        m_buffer.position(0);
        m_buffer.limit(m_rowStart);
        // the 100 is for extra safety
        cloned.m_buffer = ByteBuffer.allocate(m_rowStart + extraBytes + 100);
        cloned.m_buffer.put(m_buffer);
        m_buffer.limit(m_buffer.capacity());
        m_buffer.position(pos);

        cloned.m_buffer.putInt(0);
        assert(verifyTableInvariants());
        assert(cloned.verifyTableInvariants());
        return cloned;
    }

    boolean testForUTF8Encoding(byte strbytes[]) {
        try {
            // this doesn't prove definitively that the string is UTF-8
            // but will find many cases...
            new String(strbytes, "UTF-8");
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        return true;
    }

    private final void writeStringToBuffer(String s, String encoding, ByteBuffer b) {
        if (s == null) {
            b.putInt(NULL_STRING_INDICATOR);
            return;
        }

        int len = 0;
        byte[] strbytes = null;
        try {
            strbytes = s.getBytes(encoding);
            len = strbytes.length;
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        assert (strbytes != null);
        b.putInt(len);
        b.put(strbytes);
    }

    private final void writeStringToBuffer(byte[] s, ByteBuffer b) {
        if (s == null) {
            b.putInt(NULL_STRING_INDICATOR);
            return;
        }

        int len = s.length;
        b.putInt(len);
        b.put(s);
    }

    @Override
    public int getRowCount() {
        return m_rowCount;
    }

    @Override
    protected int getRowStart() {
        return m_rowStart;
    }
    
    @Override
    public int getRowSize() {
        // FIXME
        int total_size = this.getUnderlyingBufferSize();
        return (total_size / m_rowCount);
    }

    @Override
    public int getColumnCount() {
        return m_colCount;
    }

    private final boolean verifyTableInvariants() {
        assert(m_buffer != null);
        assert(m_rowCount >= 0);
        assert(m_colCount > 0);
        assert(m_rowStart > 0);
        // minimum reasonable table size
        final int minsize = 4 + 1 + 2 + 1 + 4 + 4;
        assert(m_buffer.capacity() >= minsize);
        assert(m_buffer.limit() >= minsize);
        if (m_buffer.position() < minsize) {
            System.err.printf("Buffer position %d is smaller than it should be.\n", m_buffer.position());
            return false;
        }

        int rowStart = m_buffer.getInt(0) + 4;
        if (rowStart < (4 + 1 + 2 + 1 + 4)) {
            System.err.printf("rowStart with value %d is smaller than it should be.\n", rowStart);
            return false;
        }
        assert(m_buffer.position() > m_rowStart) :
            String.format("Buffer position is %d but row start is %d", m_buffer.position(), m_rowStart);

        int colCount = m_buffer.getShort(5);
        assert(colCount > 0);

        return true;
    }

    /**
     * End users should not call this method.
     * @return Underlying buffer size
     */
    public int getUnderlyingBufferSize() {
        return m_buffer.position();
    }

    /**
     * Set the status code associated with this table. Default value is 0.
     * @return Status code
     */
    public byte getStatusCode() {
        return m_buffer.get(4);
    }

    /**
     * Set the status code associated with this table. Default value if not set is 0
     * @param code Status code to set
     */
    public void setStatusCode(byte code) {
        m_buffer.put( 4, code);
    }
}
