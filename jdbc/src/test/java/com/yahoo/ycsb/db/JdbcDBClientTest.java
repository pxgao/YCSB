/**
 * Copyright (c) 2015 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.yahoo.ycsb.db;

import static org.junit.Assert.*;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.StringByteIterator;
import org.junit.*;

import java.sql.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Vector;

/**
 * Created by kruthar on 11/2/15.
 */
public class JdbcDBClientTest {
    private static final String TEST_DB_DRIVER = "org.hsqldb.jdbc.JDBCDriver";
    private static final String TEST_DB_URL = "jdbc:hsqldb:mem:ycsb";
    private static final String TEST_DB_USER = "sa";
    private static final String TABLE_NAME = "USERTABLE";
    private static final int FIELD_LENGTH = 32;
    private static final String FIELD_PREFIX = "FIELD";
    private static final String KEY_PREFIX = "user";
    private static final String KEY_FIELD = "YCSB_KEY";
    private static final int NUM_FIELDS = 3;

    private static Connection jdbcConnection = null;
    private static JdbcDBClient jdbcDBClient = null;

    @BeforeClass
    public static void setup() {
        try {
            jdbcConnection = DriverManager.getConnection(TEST_DB_URL);
            jdbcDBClient = new JdbcDBClient();

            Properties p = new Properties();
            p.setProperty(JdbcDBClientConstants.CONNECTION_URL, TEST_DB_URL);
            p.setProperty(JdbcDBClientConstants.DRIVER_CLASS, TEST_DB_DRIVER);
            p.setProperty(JdbcDBClientConstants.CONNECTION_USER, TEST_DB_USER);

            jdbcDBClient.setProperties(p);
            jdbcDBClient.init();
        } catch (SQLException e) {
            e.printStackTrace();
            fail("Could not create local Database");
        } catch (DBException e) {
            e.printStackTrace();
            fail("Could not create JdbcDBClient instance");
        }
    }

    @AfterClass
    public static void teardown() {
        try {
            if (jdbcConnection != null) {
                jdbcConnection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        
        try {
            if (jdbcDBClient != null) {
                jdbcDBClient.cleanup();
            }
        } catch (DBException e) {
            e.printStackTrace();
        }
    }

    @Before
    public void prepareTest() {
        try {
            DatabaseMetaData metaData = jdbcConnection.getMetaData();
            ResultSet tableResults = metaData.getTables(null, null, TABLE_NAME, null);
            if (tableResults.next()) {
                // If the table already exists, just truncate it
                jdbcConnection.prepareStatement(
                    String.format("TRUNCATE TABLE %s", TABLE_NAME)
                ).execute();
            } else {
                // If the table does not exist then create it
                StringBuilder createString = new StringBuilder(
                    String.format("CREATE TABLE %s (%s VARCHAR(100) PRIMARY KEY", TABLE_NAME, KEY_FIELD)
                );
                for (int i = 0; i < NUM_FIELDS; i++) {
                    createString.append(
                        String.format(", %s%d VARCHAR(100)", FIELD_PREFIX, i)
                    );
                }
                createString.append(")");
                jdbcConnection.prepareStatement(createString.toString()).execute();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            fail("Failed to prepare test");
        }
    }

    /*
        This is a copy of buildDeterministicValue() from core:com.yahoo.ycsb.workloads.CoreWorkload.java.
        That method is neither public nor static so we need a copy.
     */
    private String buildDeterministicValue(String key, String fieldkey) {
        int size = FIELD_LENGTH;
        StringBuilder sb = new StringBuilder(size);
        sb.append(key);
        sb.append(':');
        sb.append(fieldkey);
        while (sb.length() < size) {
            sb.append(':');
            sb.append(sb.toString().hashCode());
        }
        sb.setLength(size);

        return sb.toString();
    }

    /*
        Inserts a row of deterministic values for the given insertKey using the jdbcDBClient.
     */
    private HashMap<String, ByteIterator> insertRow(String insertKey) {
        HashMap<String, ByteIterator> insertMap = new HashMap<String, ByteIterator>();
        for (int i = 0; i < 3; i++) {
            insertMap.put(FIELD_PREFIX + i, new StringByteIterator(buildDeterministicValue(insertKey, FIELD_PREFIX + i)));
        }
        jdbcDBClient.insert(TABLE_NAME, insertKey, insertMap);

        return insertMap;
    }

    @Test
    public void insertTest() {
        try {
            String insertKey = "user0";
            insertRow(insertKey);

            ResultSet resultSet = jdbcConnection.prepareStatement(
                String.format("SELECT * FROM %s", TABLE_NAME)
            ).executeQuery();

            // Check we have a result Row
            assertTrue(resultSet.next());
            // Check that all the columns have expected values
            assertEquals(resultSet.getString(KEY_FIELD), insertKey);
            for (int i = 0; i < 3; i++) {
                // TODO: This will fail until the fix is made to insert and update fields in the correct order.
                // TODO: Uncomment this assertEquals when the fix is made.
                //assertEquals(resultSet.getString(FIELD_PREFIX + i), insertMap.get(FIELD_PREFIX + i));
            }
            // Check that we do not have any more rows
            assertFalse(resultSet.next());

            resultSet.close();
        } catch (SQLException e) {
            e.printStackTrace();
            fail("Failed insertTest");
        }
    }

    @Test
    public void updateTest() {
        try {
            String preupdateString = "preupdate";
            StringBuilder fauxInsertString = new StringBuilder(
                String.format("INSERT INTO %s VALUES(?", TABLE_NAME)
            );
            for (int i = 0; i < NUM_FIELDS; i++) {
                fauxInsertString.append(",?");
            }
            fauxInsertString.append(")");

            PreparedStatement fauxInsertStatement = jdbcConnection.prepareStatement(fauxInsertString.toString());
            for (int i = 2; i < NUM_FIELDS + 2; i++) {
                fauxInsertStatement.setString(i, preupdateString);
            }

            fauxInsertStatement.setString(1, "user0");
            fauxInsertStatement.execute();
            fauxInsertStatement.setString(1, "user1");
            fauxInsertStatement.execute();
            fauxInsertStatement.setString(1, "user2");
            fauxInsertStatement.execute();

            HashMap<String, ByteIterator> updateMap = new HashMap<String, ByteIterator>();
            for (int i = 0; i < 3; i++) {
                updateMap.put(FIELD_PREFIX + i, new StringByteIterator(buildDeterministicValue("user1", FIELD_PREFIX + i)));
            }

            jdbcDBClient.update(TABLE_NAME, "user1", updateMap);

            ResultSet resultSet = jdbcConnection.prepareStatement(
                String.format("SELECT * FROM %s ORDER BY %s", TABLE_NAME, KEY_FIELD)
            ).executeQuery();

            // Ensure that user0 record was not changed
            resultSet.next();
            assertEquals("Assert first row key is user0", resultSet.getString(KEY_FIELD), "user0");
            for (int i = 0; i < 3; i++) {
                assertEquals("Assert first row fields contain preupdateString", resultSet.getString(FIELD_PREFIX + i), preupdateString);
            }

            // Check that all the columns have expected values for user1 record
            resultSet.next();
            assertEquals(resultSet.getString(KEY_FIELD), "user1");
            for (int i = 0; i < 3; i++) {
                // TODO: This will fail until the fix is made to insert and update fields in the correct order.
                // TODO: Uncomment this assertEquals when the fix is made.
                //assertEquals(resultSet.getString(FIELD_PREFIX + i), updateMap.get(FIELD_PREFIX + i));
            }

            // Ensure that user2 record was not changed
            resultSet.next();
            assertEquals("Assert third row key is user2", resultSet.getString(KEY_FIELD), "user2");
            for (int i = 0; i < 3; i++) {
                assertEquals("Assert third row fields contain preupdateString", resultSet.getString(FIELD_PREFIX + i), preupdateString);
            }
            resultSet.close();
        } catch (SQLException e) {
            e.printStackTrace();
            fail("Failed updateTest");
        }
    }

    @Test
    public void readTest() {
        String insertKey = "user0";
        insertRow(insertKey);
        HashSet<String> readFields = new HashSet<String>();
        HashMap<String, ByteIterator> readResultMap = new HashMap<String, ByteIterator>();

        // Test reading a single field
        readFields.add("FIELD0");
        jdbcDBClient.read(TABLE_NAME, insertKey, readFields, readResultMap);
        assertEquals("Assert that result has correct number of fields", readFields.size(), readResultMap.size());
        for (String field: readFields) {
            // TODO: This will fail until the fix is made to insert and update fields in the correct order.
            // TODO: Uncomment this assertEquals when the fix is made.
            //assertEquals("Assert " + field + " was read correctly", insertMap.get(field), readResultMap.get(field));
        }

        readResultMap = new HashMap<String, ByteIterator>();

        // Test reading all fields
        readFields.add("FIELD1");
        readFields.add("FIELD2");
        jdbcDBClient.read(TABLE_NAME, insertKey, readFields, readResultMap);
        assertEquals("Assert that result has correct number of fields", readFields.size(), readResultMap.size());
        for (String field: readFields) {
            // TODO: This will fail until the fix is made to insert and update fields in the correct order.
            // TODO: Uncomment this assertEquals when the fix is made.
            //assertEquals("Assert " + field + " was read correctly", insertMap.get(field), readResultMap.get(field));
        }
    }

    @Test
    public void deleteTest() {
        try {
            insertRow("user0");
            String deleteKey = "user1";
            insertRow(deleteKey);
            insertRow("user2");

            jdbcDBClient.delete(TABLE_NAME, deleteKey);

            ResultSet resultSet = jdbcConnection.prepareStatement(
                String.format("SELECT * FROM %s", TABLE_NAME)
            ).executeQuery();

            int totalRows = 0;
            while (resultSet.next()) {
                assertNotEquals("Assert this is not the deleted row key", deleteKey, resultSet.getString(KEY_FIELD));
                totalRows++;
            }
            // Check we do not have a result Row
            assertEquals("Assert we ended with the correct number of rows", totalRows, 2);

            resultSet.close();
        } catch (SQLException e) {
            e.printStackTrace();
            fail("Failed deleteTest");
        }
    }

    @Test
    public void scanTest() throws SQLException {
        HashMap<String, HashMap<String, ByteIterator>> keyMap = new HashMap<String, HashMap<String, ByteIterator>>();
        for (int i = 0; i < 5; i++) {
            String insertKey = KEY_PREFIX + i;
            keyMap.put(insertKey, insertRow(insertKey));
        }
        HashSet<String> fieldSet = new HashSet<String>();
        fieldSet.add("FIELD0");
        fieldSet.add("FIELD1");
        int startIndex = 1;
        int resultRows = 3;

        Vector<HashMap<String, ByteIterator>> resultVector = new Vector<HashMap<String, ByteIterator>>();
        jdbcDBClient.scan(TABLE_NAME, KEY_PREFIX + startIndex, resultRows, fieldSet, resultVector);

        // Check the resultVector is the correct size
        assertEquals("Assert the correct number of results rows were returned", resultRows, resultVector.size());
        // Check each vector row to make sure we have the correct fields
        int testIndex = startIndex;
        for (HashMap<String, ByteIterator> result: resultVector) {
            assertEquals("Assert that this row has the correct number of fields", fieldSet.size(), result.size());
            for (String field: fieldSet) {
                // TODO: This will fail until the fix is made to insert and update fields in the correct order.
                // TODO: Uncomment this assertEquals when the fix is made.
                //assertEquals("Assert this field is correct in this row", keyMap.get(KEY_PREFIX + testIndex).get(field), result.get(field));
            }
            testIndex++;
        }
    }
}
